package com.csqtt.client.wb

import android.os.Build
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Runs the whitelist-bypass WB Stream joiner (libwbrelay.so) as a child process.
 * It joins the call as a guest, carries traffic over the call's video track and
 * offers it on a local SOCKS5 port that tun2socks then feeds.
 *
 * Process protocol (stdout): "STATUS:<x>", "RESOLVE:<host>" (we answer on stdin), anything else is a log line.
 */
class WbRelay(
    private val nativeLibDir: String,
    private val socksPort: Int,
    private val socksUser: String,
    private val socksPass: String,
    private val onStatus: (String) -> Unit,
) {
    @Volatile private var process: Process? = null
    @Volatile private var stdin: BufferedWriter? = null
    private var reader: Thread? = null

    @Volatile var running = false
        private set

    fun start(roomId: String) {
        stop()
        val binary = File(nativeLibDir, "libwbrelay.so")
        if (!binary.exists()) {
            WbState.log("Нет файла libwbrelay.so — сборка без WB Stream")
            onStatus("ERROR:relay binary missing")
            return
        }
        running = true
        val proc = ProcessBuilder(
            binary.absolutePath,
            "--mode", "wbstream-headless-joiner",
            "--ws-port", "19001",
            "--socks-host", "127.0.0.1",
            "--socks-port", socksPort.toString(),
            "--socks-user", socksUser,
            "--socks-pass", socksPass,
        ).redirectErrorStream(true).start()
        process = proc
        stdin = BufferedWriter(OutputStreamWriter(proc.outputStream))

        val params = JSONObject().apply {
            put("roomId", roomId)
            put("displayName", "Mayak")
            put("tunnelMode", "video")
            put("vp8Fps", 24)
            put("vp8Batch", 30)
            put("dualTrack", false)
            put("reliable", false)
        }
        write("JOIN:$params")
        WbState.log("Процесс WB запущен, комната $roomId")

        reader = Thread({
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    when {
                        line.startsWith("RESOLVE:") -> write(resolve(line.removePrefix("RESOLVE:")))
                        line.startsWith("STATUS:") -> onStatus(line.removePrefix("STATUS:"))
                        else -> WbState.log(line)
                    }
                }
                val code = runCatching { proc.waitFor() }.getOrDefault(-1)
                if (running) {
                    WbState.log("Процесс WB завершился (код $code)")
                    running = false
                    onStatus("ERROR:relay exited ($code)")
                }
            } catch (e: Exception) {
                if (running) {
                    WbState.log("Ошибка процесса WB: ${e.message}")
                    running = false
                    onStatus("ERROR:${e.message}")
                }
            }
        }, "wb-relay").apply { isDaemon = true; start() }
    }

    private fun resolve(host: String): String =
        try {
            val all = InetAddress.getAllByName(host)
            (all.firstOrNull { it is Inet4Address } ?: all.first()).hostAddress ?: ""
        } catch (e: Exception) {
            WbState.log("Не удалось найти адрес $host: ${e.message}")
            ""
        }

    @Synchronized
    private fun write(line: String) {
        try {
            stdin?.apply {
                write(line)
                newLine()
                flush()
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { stdin?.close() }
        stdin = null
        val proc = process ?: return
        process = null
        proc.destroy()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !proc.waitFor(1500, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
            }
        }
        reader?.interrupt()
        reader = null
    }
}
