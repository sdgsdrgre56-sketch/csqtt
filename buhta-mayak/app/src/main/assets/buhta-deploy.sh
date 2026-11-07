#!/bin/sh
ACTION="$1"; ROOM="$2"; PUSER="$3"; PPASS="$4"
REPO="sdgsdrgre56-sketch/csqtt"
BIN=/usr/local/bin/buhta-joiner
need() { command -v "$1" >/dev/null 2>&1; }
fetch() {
  need curl || { apt-get update -qq >/dev/null 2>&1; apt-get install -y -qq curl >/dev/null 2>&1; }
  TAG=$(curl -fsSL "https://api.github.com/repos/sdgsdrgre56-sketch/csqtt/releases?per_page=100" | grep -o '"tag_name": *"buhta-build-[0-9]*"' | grep -o '[0-9]*' | sort -n | tail -1)
  [ -n "$TAG" ] || { echo "[ERR] не нашёл релиз сервера на GitHub"; exit 1; }
  echo "[LOG] Скачиваю сервер: buhta-build-$TAG"
  curl -fsSL -o $BIN.new "https://github.com/sdgsdrgre56-sketch/csqtt/releases/download/buhta-build-$TAG/buhta-joiner-linux-amd64" || { echo "[ERR] не удалось скачать файл"; exit 1; }
  chmod +x $BIN.new; mv $BIN.new $BIN
}
panelpass() {
  i=0
  while [ $i -lt 25 ]; do
    L=$(journalctl -u buhta --no-pager -n 300 2>/dev/null | grep 'ПЕРВЫЙ ЗАПУСК' | tail -1)
    if [ -n "$L" ]; then
      P=$(echo "$L" | sed 's/.*пароль[: ]*//; s/[ (].*//' | tr -d '\r')
    fi
    i=$((i+1)); sleep 1
  done
}
keeper() {
  cat > /etc/systemd/system/buhta-keep.service <<EOF
[Unit]
Description=Buhta room keeper (second participant keeps the Saby conference alive)
After=network-online.target buhta.service
[Service]
EnvironmentFile=-/etc/buhta/panel.env
ExecStart=$BIN -room \${BUHTA_ROOM} -name Страж -nomedia -hold 8760h -timeout 90s
Restart=always
RestartSec=2
[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable buhta-keep >/dev/null 2>&1
}
case "$ACTION" in
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
  ( umask 077; printf 'BUHTA_PANEL_USER=%s\nBUHTA_PANEL_PASS=%s\nBUHTA_ROOM=%s\n' "$PUSER" "$PPASS" "$ROOM" > /etc/buhta/panel.env )
  chmod 600 /etc/buhta/panel.env
  echo "[LOG] Создаю службу buhta"
  cat > /etc/systemd/system/buhta.service <<EOF
[Unit]
Description=Buhta tunnel server
After=network-online.target
Wants=network-online.target
[Service]
EnvironmentFile=-/etc/buhta/panel.env
ExecStartPre=/usr/local/bin/buhta-net.sh
ExecStart=$BIN -room \${BUHTA_ROOM} -tunnel server -hold 8760h -timeout 90s -data /etc/buhta -panel :8787
Restart=always
RestartSec=1
[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable buhta >/dev/null 2>&1
  need ufw && ufw status 2>/dev/null | grep -q "Status: active" && ufw allow 8787/tcp >/dev/null 2>&1
  if [ -z "$ROOM" ]; then
    systemctl stop buhta >/dev/null 2>&1
    echo "[OK] Сервер и панель установлены. Нужна ссылка на комнату Saby Meet"
    echo "CSQTT_DEPLOY_OK"
    exit 0
  fi
  systemctl restart buhta
  sleep 2
  if systemctl is-active --quiet buhta; then echo "[OK] Служба buhta запущена"; else echo "[ERR] служба не запустилась"; journalctl -u buhta --no-pager -n 20; exit 1; fi
  echo "CSQTT_DEPLOY_OK"
  ;;
update)
  fetch
  systemctl restart buhta
  echo "[OK] Обновлено и перезапущено"
  echo "CSQTT_DEPLOY_OK"
  ;;
restart)
  systemctl restart buhta && echo "[OK] Перезапущено"
  echo "CSQTT_DEPLOY_OK"
  ;;
logs)
  journalctl -u buhta --no-pager -n 80 | cut -c1-220
  echo "CSQTT_DEPLOY_OK"
  ;;
resetpass)
  [ -f /etc/buhta/panel.env ] && . /etc/buhta/panel.env
  ( umask 077; printf 'BUHTA_PANEL_USER=%s\nBUHTA_PANEL_PASS=%s\nBUHTA_ROOM=%s\n' "$PUSER" "$PPASS" "$BUHTA_ROOM" > /etc/buhta/panel.env )
  chmod 600 /etc/buhta/panel.env
  fetch
  sed -i -E 's/-timeout [0-9]+[hms]/-timeout 90s/; s/RestartSec=[0-9]+/RestartSec=1/' /etc/systemd/system/buhta.service
  systemctl daemon-reload
  keeper
  systemctl restart buhta
  systemctl restart buhta-keep
  echo "[OK] Логин и пароль панели записаны на сервер"
  sleep 3
  . /etc/buhta/panel.env
  C=$(curl -sk -m 8 -o /dev/null -w '%{http_code}' -H 'X-Buhta: 1' -H 'Content-Type: application/json' -d "{\"login\":\"$BUHTA_PANEL_USER\",\"password\":\"$BUHTA_PANEL_PASS\"}" https://127.0.0.1:8787/api/login)
  if [ "$C" = "200" ]; then echo "[OK] Вход в панель проверен: логин $BUHTA_PANEL_USER"; else echo "[WARN] Вход в панель не прошёл (код $C), логин $BUHTA_PANEL_USER"; fi
  echo "[LOG] Журнал сервера:"
  journalctl -u buhta --no-pager -n 25 -o cat 2>/dev/null | grep -v '^<\|<-- \|--> ' | cut -c1-160 | sed 's/^/[LOG] /'
  echo "CSQTT_DEPLOY_OK"
  ;;
setroom)
  [ -f /etc/buhta/panel.env ] || { echo "[ERR] сервер не установлен"; exit 1; }
  . /etc/buhta/panel.env
  [ -n "$PUSER" ] && BUHTA_PANEL_USER="$PUSER"
  [ -n "$PPASS" ] && BUHTA_PANEL_PASS="$PPASS"
  ( umask 077; printf 'BUHTA_PANEL_USER=%s\nBUHTA_PANEL_PASS=%s\nBUHTA_ROOM=%s\n' "$BUHTA_PANEL_USER" "$BUHTA_PANEL_PASS" "$ROOM" > /etc/buhta/panel.env )
  chmod 600 /etc/buhta/panel.env
  fetch
  sed -i -E 's/-timeout [0-9]+[hms]/-timeout 90s/; s/RestartSec=[0-9]+/RestartSec=1/' /etc/systemd/system/buhta.service
  systemctl daemon-reload
  keeper
  systemctl restart buhta
  systemctl restart buhta-keep
  sleep 2
  if systemctl is-active --quiet buhta; then echo "[OK] Комната задана, сервер запущен"; else echo "[ERR] служба не запустилась"; journalctl -u buhta --no-pager -n 20; exit 1; fi
  echo "[LOG] Создаю ключ в панели сервера"
  B=https://127.0.0.1:8787
  need curl || { apt-get update -qq >/dev/null 2>&1; apt-get install -y -qq curl >/dev/null 2>&1; }
  . /etc/buhta/panel.env
  i=0
  while [ $i -lt 40 ]; do curl -sk -m 4 -o /dev/null "$B/api/me" && break; i=$((i+1)); sleep 2; done
  JAR=/tmp/buhta-jar.$$
  curl -sk -m 10 -c $JAR -H 'X-Buhta: 1' -H 'Content-Type: application/json' -d "{\"login\":\"$BUHTA_PANEL_USER\",\"password\":\"$BUHTA_PANEL_PASS\"}" "$B/api/login" >/dev/null
  CID=$(curl -sk -m 10 -b $JAR -H 'X-Buhta: 1' "$B/api/clients" | grep -o '"id": *"[^"]*"' | head -1 | sed 's/.*: *"//; s/"$//')
  if [ -z "$CID" ]; then
    CID=$(curl -sk -m 10 -b $JAR -H 'X-Buhta: 1' -H 'Content-Type: application/json' -d '{"name":"phone","limit_mbps":0}' "$B/api/clients" | grep -o '"id": *"[^"]*"' | head -1 | sed 's/.*: *"//; s/"$//')
  fi
  LINK=$(curl -sk -m 10 -b $JAR -H 'X-Buhta: 1' "$B/api/clients/$CID/link" | grep -o 'buhta://[A-Za-z0-9_=-]*')
  rm -f $JAR
  if [ -n "$LINK" ]; then echo "BUHTA_KEY|$LINK"; else echo "[WARN] ключ не удалось создать на сервере"; fi
  sleep 6
  echo "[LOG] Журнал сервера:"
  journalctl -u buhta --no-pager -n 25 -o cat 2>/dev/null | grep -v '^<\|<-- \|--> ' | cut -c1-160 | sed 's/^/[LOG] /'
  echo "CSQTT_DEPLOY_OK"
  ;;
uninstall)
  systemctl disable --now buhta buhta-keep >/dev/null 2>&1
  rm -f /etc/systemd/system/buhta.service /etc/systemd/system/buhta-keep.service /usr/local/bin/buhta-joiner /usr/local/bin/buhta-net.sh
  rm -rf /etc/buhta
  systemctl daemon-reload
  echo "[OK] Сервер Бухты удалён"
  echo "CSQTT_DEPLOY_OK"
  ;;
*) echo "[ERR] неизвестное действие"; exit 2;;
esac
