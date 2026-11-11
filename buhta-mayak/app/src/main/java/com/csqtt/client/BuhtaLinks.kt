package com.csqtt.client

import android.content.Context
import android.util.Log
import java.util.Collections
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Потоки (несколько комнат Saby). Основной процесс (комната №1, порт 17777) ведёт
 * TunnelManager. Дополнительные комнаты поднимаются здесь отдельными процессами
 * libbuhta.so на портах 17778, 17779… TUN-насос (TunVpnService) раскладывает
 * исходящие пакеты по всем готовым портам по кругу, собирая скорость всех комнат.
 */
object BuhtaLinks {
    const val BASE_PORT = 17777

    // Приманка-keepalive: первый байт 0x00 (не IP), линк её не шлёт в Saby — только
    // запоминает обратный адрес насоса. Совпадает с KeepMagic в движке.
    val KEEP_MAGIC = byteArrayOf(0x00, 'B'.code.toByte(), 'U'.code.toByte(), 'H'.code.toByte(), 'T'.code.toByte(), 'A'.code.toByte(), 'K'.code.toByte(), 'A'.code.toByte())

    /** Сколько комнат настроено (1 = обычный режим). */
    @Volatile var total = 1

    private val ready = CopyOnWriteArraySet<Int>()
    fun isReady(port: Int) = ready.contains(port)
    fun readyPorts(): List<Int> = ready.sorted()
    fun markReady(port: Int) { ready.add(port) }
    fun markDown(port: Int) { ready.remove(port) }

    @Volatile private var running = false
    private val procs = Collections.synchronizedList(mutableListOf<Process>())

    /** Поднимает дополнительные комнаты (кроме первой) и держит их живыми. */
    fun startExtras(context: Context, extras: List<BuhtaRoom.Target>, onLog: (String) -> Unit) {
        stopExtras()
        if (extras.isEmpty()) return
        running = true
        val dir = context.applicationInfo.nativeLibraryDir
        val bin = "$dir/${CsqttConstants.Tunnel.BINARY_NAME}"
        extras.forEachIndexed { idx, target ->
            val port = BASE_PORT + idx + 1
            val label = idx + 2 // человеку: комната 2, 3…
            Thread {
                while (running) {
                    val cmd = mutableListOf(
                        bin, "-room", target.room, "-tunnel", "client",
                        "-udp", "127.0.0.1:$port", "-hold", "8760h", "-timeout", "60s",
                    )
                    if (target.key.isNotEmpty()) { cmd.add("-key"); cmd.add(target.key) }
                    if (BuhtaRoom.relayOnly(context)) cmd.add("-relayonly")
                    if (BuhtaRoom.videoLane(context)) cmd.add("-video")
                    val p = try {
                        ProcessBuilder(cmd).apply {
                            redirectErrorStream(true)
                            environment()["LD_LIBRARY_PATH"] = dir
                        }.start()
                    } catch (e: Exception) {
                        onLog("[ДВИЖОК-$label] не запустился: ${e.message}")
                        Thread.sleep(2000); continue
                    }
                    procs.add(p)
                    try {
                        p.inputStream.bufferedReader().forEachLine { line ->
                            if (!running) return@forEachLine
                            if (line.contains("connected ip=") || line.contains("[status] connected")) {
                                markReady(port)
                                onLog("[ДВИЖОК-$label] комната $label подключена ✓")
                            } else if (line.contains("waiting up to") && line.contains("session-initiate")) {
                                onLog("[ДВИЖОК-$label] комната $label: ждём сервер…")
                            } else if (line.contains("signalling closed")) {
                                markDown(port)
                                onLog("[ДВИЖОК-$label] комната $label: обрыв ${line.substringAfter("signalling closed").take(120)}")
                            } else if (line.contains("[sec]") || line.contains("[lane]") || line.contains("[status] error") ||
                                line.contains("ICE") || line.contains("[webrtc] пара") || line.contains("peer connection") || line.contains("[tun]")) {
                                onLog("[ДВИЖОК-$label] ${line.replace(Regex("^\\d{4}/\\d\\d/\\d\\d \\d\\d:\\d\\d:\\d\\d(\\.\\d+)? "), "").take(160)}")
                            }
                        }
                    } catch (_: Exception) {
                    }
                    markDown(port)
                    procs.remove(p)
                    if (running) {
                        onLog("[ДВИЖОК-$label] комната $label переподключается…")
                        Thread.sleep(1500)
                    }
                }
            }.apply { isDaemon = true; name = "buhta-link-$port" }.start()
        }
    }

    fun stopExtras() {
        running = false
        synchronized(procs) {
            procs.forEach { runCatching { it.destroy() } }
            procs.clear()
        }
        ready.clear()
    }

    // ---- Закрепление соединений за комнатами и подмена адреса ----
    // Комната k (k ≥ 1) на сервере — отдельный сервер с подсетью 10.77.k.0/24.
    // Телефон шлёт туда пакеты с адресом 10.77.k.2 вместо 10.77.0.2, ответы
    // возвращает к 10.77.0.2. Одно соединение всегда идёт через одну комнату.

    /** Номер комнаты для исходящего пакета. */
    fun pickLink(pkt: ByteArray, len: Int): Int {
        val n = total
        if (n <= 1 || len < 20) return 0
        if ((pkt[0].toInt() ushr 4) != 4) return 0
        val ihl = (pkt[0].toInt() and 0x0f) * 4
        val frag = ((pkt[6].toInt() and 0x3f) shl 8) or (pkt[7].toInt() and 0xff)
        if (frag != 0) return 0 // фрагменты (MF или смещение) — через первую комнату
        val proto = pkt[9].toInt() and 0xff
        if ((proto != 6 && proto != 17) || len < ihl + 4) return 0
        var h = 17
        for (i in 16..19) h = h * 31 + (pkt[i].toInt() and 0xff)
        for (i in 0..3) h = h * 31 + (pkt[ihl + i].toInt() and 0xff)
        h = h * 31 + proto
        val k = Math.floorMod(h, n)
        return if (k == 0 || isReady(BASE_PORT + k)) k else 0
    }

    private fun csum16(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        while (i + 1 < off + len) { sum += ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff); i += 2 }
        if (i < off + len) sum += (b[i].toInt() and 0xff) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv() and 0xffff
    }

    /** Меняет третий октет адреса (src при at=12, dst при at=16) и чинит контрольные суммы. */
    fun rewriteThirdOctet(pkt: ByteArray, len: Int, at: Int, newOctet: Int) {
        if (len < 20 || (pkt[0].toInt() ushr 4) != 4) return
        val ihl = (pkt[0].toInt() and 0x0f) * 4
        val oldWord = ((pkt[at + 2].toInt() and 0xff) shl 8) or (pkt[at + 3].toInt() and 0xff)
        pkt[at + 2] = newOctet.toByte()
        val newWord = ((pkt[at + 2].toInt() and 0xff) shl 8) or (pkt[at + 3].toInt() and 0xff)
        // заголовок IP
        pkt[10] = 0; pkt[11] = 0
        val hc = csum16(pkt, 0, ihl)
        pkt[10] = (hc ushr 8).toByte(); pkt[11] = hc.toByte()
        val frag = ((pkt[6].toInt() and 0x1f) shl 8) or (pkt[7].toInt() and 0xff)
        if (frag != 0) return // не первый фрагмент: заголовка TCP/UDP нет
        val proto = pkt[9].toInt() and 0xff
        val co = when (proto) { 6 -> ihl + 16; 17 -> ihl + 6; else -> return }
        if (len < co + 2) return
        val old = ((pkt[co].toInt() and 0xff) shl 8) or (pkt[co + 1].toInt() and 0xff)
        if (proto == 17 && old == 0) return // UDP без контрольной суммы
        // RFC 1624: HC' = ~(~HC + ~m + m')
        var sum = (old.inv() and 0xffff) + (oldWord.inv() and 0xffff) + newWord
        while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
        var res = sum.inv() and 0xffff
        if (proto == 17 && res == 0) res = 0xffff
        pkt[co] = (res ushr 8).toByte(); pkt[co + 1] = res.toByte()
    }
}
