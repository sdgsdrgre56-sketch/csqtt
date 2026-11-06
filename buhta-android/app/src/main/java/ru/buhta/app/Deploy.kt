package ru.buhta.app

import android.util.Base64
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.concurrent.TimeUnit

/** Установка сервера Бухты на VPS по SSH (как вкладка «Деплой» в Маяке). */
object Deploy {

    class Result(val ok: Boolean, val panelUser: String = "", val panelPass: String = "", val message: String = "")

    const val REPO = "sdgsdrgre56-sketch/csqtt"

    private val SCRIPT = """
#!/bin/sh
ACTION="${'$'}1"; ROOM="${'$'}2"
REPO="$REPO"
BIN=/usr/local/bin/buhta-joiner
need() { command -v "${'$'}1" >/dev/null 2>&1; }
fetch() {
  need curl || { apt-get update -qq >/dev/null 2>&1; apt-get install -y -qq curl >/dev/null 2>&1; }
  TAG=${'$'}(curl -fsSL "https://api.github.com/repos/${'$'}REPO/releases?per_page=100" | grep -o '"tag_name": *"buhta-build-[0-9]*"' | grep -o '[0-9]*' | sort -n | tail -1)
  [ -n "${'$'}TAG" ] || { echo "[ERR] не нашёл релиз сервера на GitHub"; exit 1; }
  echo "[LOG] Скачиваю сервер: buhta-build-${'$'}TAG"
  curl -fsSL -o ${'$'}BIN.new "https://github.com/${'$'}REPO/releases/download/buhta-build-${'$'}TAG/buhta-joiner-linux-amd64" || { echo "[ERR] не удалось скачать файл"; exit 1; }
  chmod +x ${'$'}BIN.new; mv ${'$'}BIN.new ${'$'}BIN
}
panelpass() {
  i=0
  while [ ${'$'}i -lt 25 ]; do
    L=${'$'}(journalctl -u buhta --no-pager -n 300 2>/dev/null | grep 'ПЕРВЫЙ ЗАПУСК' | tail -1)
    if [ -n "${'$'}L" ]; then
      P=${'$'}(echo "${'$'}L" | sed 's/.*пароль[: ]*//' | tr -d ' \r')
      echo "BUHTA_PANEL|admin|${'$'}P"; return
    fi
    i=${'$'}((i+1)); sleep 1
  done
}
case "${'$'}ACTION" in
install)
  echo "[LOG] Проверяю систему"
  need systemctl || { echo "[ERR] нужен systemd"; exit 1; }
  need iptables || { apt-get update -qq >/dev/null 2>&1; apt-get install -y -qq iptables >/dev/null 2>&1; }
  fetch
  cat > /usr/local/bin/buhta-net.sh <<'EOF'
#!/bin/sh
ip tuntap add dev buhta0 mode tun 2>/dev/null || true
ip addr replace 10.77.0.1/24 dev buhta0
ip link set buhta0 up mtu 1200
sysctl -w net.ipv4.ip_forward=1 >/dev/null
iptables -t nat -C POSTROUTING -s 10.77.0.0/24 ! -o buhta0 -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -s 10.77.0.0/24 ! -o buhta0 -j MASQUERADE
iptables -C FORWARD -i buhta0 -j ACCEPT 2>/dev/null || iptables -I FORWARD -i buhta0 -j ACCEPT
iptables -C FORWARD -o buhta0 -j ACCEPT 2>/dev/null || iptables -I FORWARD -o buhta0 -j ACCEPT
EOF
  chmod +x /usr/local/bin/buhta-net.sh
  mkdir -p /etc/buhta
  echo "[LOG] Создаю службу buhta"
  cat > /etc/systemd/system/buhta.service <<EOF
[Unit]
Description=Buhta tunnel server
After=network-online.target
Wants=network-online.target
[Service]
ExecStartPre=/usr/local/bin/buhta-net.sh
ExecStart=${'$'}BIN -room ${'$'}ROOM -tunnel server -hold 8760h -timeout 60s -data /etc/buhta -panel :8787
Restart=always
RestartSec=3
[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable buhta >/dev/null 2>&1
  systemctl restart buhta
  need ufw && ufw status 2>/dev/null | grep -q "Status: active" && ufw allow 8787/tcp >/dev/null 2>&1
  sleep 2
  if systemctl is-active --quiet buhta; then echo "[OK] Служба buhta запущена"; else echo "[ERR] служба не запустилась"; journalctl -u buhta --no-pager -n 20; exit 1; fi
  panelpass
  echo "BUHTA_DEPLOY_OK"
  ;;
update)
  fetch
  systemctl restart buhta
  echo "[OK] Обновлено и перезапущено"
  echo "BUHTA_DEPLOY_OK"
  ;;
restart)
  systemctl restart buhta && echo "[OK] Перезапущено"
  echo "BUHTA_DEPLOY_OK"
  ;;
logs)
  journalctl -u buhta --no-pager -n 80 | cut -c1-220
  echo "BUHTA_DEPLOY_OK"
  ;;
resetpass)
  rm -f /etc/buhta/panel.json
  systemctl restart buhta
  panelpass
  echo "BUHTA_DEPLOY_OK"
  ;;
uninstall)
  systemctl disable --now buhta >/dev/null 2>&1
  rm -f /etc/systemd/system/buhta.service /usr/local/bin/buhta-joiner /usr/local/bin/buhta-net.sh
  rm -rf /etc/buhta
  systemctl daemon-reload
  echo "[OK] Сервер Бухты удалён"
  echo "BUHTA_DEPLOY_OK"
  ;;
*) echo "[ERR] неизвестное действие"; exit 2;;
esac
""".trimIndent()

    private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Выполняет действие на VPS. onLine вызывается для каждой строки вывода. */
    fun run(
        host: String, port: Int, user: String, password: String,
        action: String, room: String, onLine: (String) -> Unit,
    ): Result {
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        val ssh = SSHClient()
        try {
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connectTimeout = 15000
            ssh.timeout = 30000
            onLine("[LOG] Подключаюсь к $host:$port")
            ssh.connect(host, port)
            ssh.authPassword(user, password.replace("\r", "").replace("\n", ""))
            onLine("[OK] SSH: вход выполнен")
            val b64 = Base64.encodeToString(SCRIPT.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val args = "$action ${shq(room)}"
            val sudo = user != "root"
            val cmd = "echo $b64 | base64 -d > /tmp/buhta-deploy.sh && " +
                (if (sudo) "sudo -S -p '' sh /tmp/buhta-deploy.sh $args" else "sh /tmp/buhta-deploy.sh $args")
            val session = ssh.startSession()
            try {
                session.allocateDefaultPTY()
                val c = session.exec(cmd)
                if (sudo) {
                    c.outputStream.write((password + "\n").toByteArray())
                    c.outputStream.flush()
                }
                var panelUser = ""; var panelPass = ""; var ok = false
                c.inputStream.bufferedReader().useLines { seq ->
                    for (raw in seq) {
                        val line = raw.trim()
                        if (line.isEmpty()) continue
                        when {
                            line.startsWith("BUHTA_PANEL|") -> {
                                val p = line.split("|")
                                if (p.size >= 3) { panelUser = p[1]; panelPass = p[2] }
                                onLine("[OK] Пароль панели получен")
                            }
                            line == "BUHTA_DEPLOY_OK" -> ok = true
                            else -> onLine(line)
                        }
                    }
                }
                c.join(10, TimeUnit.SECONDS)
                val code = c.exitStatus ?: -1
                if (!ok || code != 0) return Result(false, message = "Скрипт завершился с кодом $code")
                return Result(true, panelUser, panelPass)
            } finally {
                try { session.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            val m = e.message.orEmpty()
            val friendly = when {
                m.contains("Auth fail", true) || m.contains("authentication", true) -> "SSH: неверный логин или пароль (или вход по паролю запрещён)"
                m.contains("refused", true) -> "SSH: соединение отклонено — проверь порт"
                m.contains("timed out", true) || m.contains("timeout", true) -> "Истекло время ожидания — проверь адрес VPS"
                m.contains("UnknownHost", true) -> "Адрес VPS не найден"
                else -> m.take(200).ifEmpty { e.javaClass.simpleName }
            }
            return Result(false, message = friendly)
        } finally {
            try { ssh.disconnect() } catch (_: Exception) {}
        }
    }
}
