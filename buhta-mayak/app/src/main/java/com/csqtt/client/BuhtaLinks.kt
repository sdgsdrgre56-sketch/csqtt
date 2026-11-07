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

    private val ready = CopyOnWriteArraySet<Int>()
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
                        "-udp", "127.0.0.1:$port", "-hold", "8760h", "-timeout", "8760h",
                    )
                    if (target.key.isNotEmpty()) { cmd.add("-key"); cmd.add(target.key) }
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
}
