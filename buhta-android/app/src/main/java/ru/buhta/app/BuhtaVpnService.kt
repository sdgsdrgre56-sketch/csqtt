package ru.buhta.app

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Бухта: поднимает TUN, запускает Go-процесс (libbuhta.so, режим client), который
 * заходит в комнату Saby Meet, и гоняет IP-пакеты TUN <-> локальный UDP-порт Go-процесса.
 * Приложение исключено из VPN, поэтому соединение Go-процесса с Saby идёт мимо туннеля.
 */
class BuhtaVpnService : VpnService() {

    companion object {
        const val PORT = 17777
    }

    @Volatile private var running = false
    private var tun: ParcelFileDescriptor? = null
    private var proc: Process? = null
    private var socket: DatagramSocket? = null
    private var room = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        val r = intent?.getStringExtra("room") ?: return START_NOT_STICKY
        if (running) return START_STICKY
        room = r
        running = true
        BuhtaState.active = true
        Thread { supervise() }.start()
        return START_STICKY
    }

    override fun onRevoke() {
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun supervise() {
        val bin = applicationInfo.nativeLibraryDir + "/libbuhta.so"
        var attempt = 0
        while (running) {
            attempt++
            BuhtaState.connected = false
            BuhtaState.update(if (attempt == 1) "Подключаюсь к комнате…" else "Переподключаюсь (попытка $attempt)…")
            try {
                val p = ProcessBuilder(
                    bin, "-room", room, "-tunnel", "client",
                    "-udp", "127.0.0.1:$PORT", "-hold", "8760h", "-timeout", "60s"
                ).redirectErrorStream(true).start()
                proc = p
                p.inputStream.bufferedReader().useLines { seq ->
                    for (line in seq) onLine(line)
                }
                val code = p.waitFor()
                BuhtaState.log("процесс завершился, код " + code)
            } catch (e: Exception) {
                BuhtaState.log("Ошибка запуска: ${e.message}")
            }
            BuhtaState.connected = false
            if (!running) break
            BuhtaState.update("Соединение потеряно, повтор через 3 с")
            try {
                Thread.sleep(3000)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun onLine(line: String) {
        if (line.contains("[status] connected")) {
            BuhtaState.connected = true
            BuhtaState.update("Подключено")
            ensureTun()
            return
        }
        // XMPP-кадры огромные — их пропускаем, всё остальное показываем
        if (line.startsWith("<") || line.contains("<-- ") || line.contains("--> ") || line.length < 3) return
        BuhtaState.log(line.substringAfter(' ', line).take(200))
    }

    @Synchronized
    private fun ensureTun() {
        if (tun != null || !running) return
        fun build(v6: Boolean): ParcelFileDescriptor? {
            val b = Builder()
                .setSession("Бухта")
                .setMtu(1200)
                .addAddress("10.77.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
            if (v6) {
                // IPv6 заворачиваем в TUN и там отбрасываем, чтобы не утекал
                b.addAddress("fd00:77::2", 128).addRoute("::", 0)
            }
            try {
                b.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                BuhtaState.log("addDisallowedApplication: ${e.message}")
            }
            return b.establish()
        }
        val fd = try {
            build(true)
        } catch (e: Exception) {
            BuhtaState.log("VPN с IPv6 не вышел (${e.message}), пробую только IPv4")
            build(false)
        }
        if (fd == null) {
            BuhtaState.update("Не удалось поднять VPN (нет разрешения?)")
            return
        }
        tun = fd
        val sock = DatagramSocket()
        socket = sock
        val target = InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT)
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)

        Thread {
            val buf = ByteArray(32768)
            try {
                while (running) {
                    val n = input.read(buf)
                    if (n <= 0) continue
                    try {
                        sock.send(DatagramPacket(buf, 0, n, target))
                    } catch (e: Exception) {
                        // Go-процесс ещё не слушает порт — пакет теряется, TCP повторит
                    }
                }
            } catch (e: Exception) {
            }
        }.start()

        Thread {
            val buf = ByteArray(32768)
            try {
                while (running) {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    output.write(buf, 0, p.length)
                }
            } catch (e: Exception) {
            }
        }.start()
        BuhtaState.log("VPN-интерфейс поднят (10.77.0.2, DNS 1.1.1.1 / 8.8.8.8)")
    }

    @Synchronized
    private fun shutdown() {
        running = false
        try { proc?.destroy() } catch (e: Exception) {}
        try { socket?.close() } catch (e: Exception) {}
        try { tun?.close() } catch (e: Exception) {}
        proc = null
        socket = null
        tun = null
        BuhtaState.active = false
        BuhtaState.connected = false
        BuhtaState.update("Отключено")
    }
}
