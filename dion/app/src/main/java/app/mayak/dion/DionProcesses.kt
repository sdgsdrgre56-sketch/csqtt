package app.mayak.dion

import android.os.Build
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Starts a binary from the app's native library folder and feeds its stdout lines to [onLine]. */
private class NativeProcess(
    private val name: String,
    private val command: List<String>,
    private val onLine: (String) -> Unit,
    private val onExit: (Int) -> Unit,
) {
    @Volatile private var process: Process? = null
    @Volatile private var stdin: BufferedWriter? = null
    @Volatile private var stopping = false

    fun start() {
        val proc = ProcessBuilder(command).redirectErrorStream(true).start()
        process = proc
        stdin = BufferedWriter(OutputStreamWriter(proc.outputStream))
        Thread({
            runCatching { proc.inputStream.bufferedReader().forEachLine(onLine) }
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            if (!stopping) onExit(code)
        }, name).apply { isDaemon = true; start() }
    }

    @Synchronized
    fun write(line: String) {
        runCatching {
            stdin?.apply {
                write(line)
                newLine()
                flush()
            }
        }
    }

    @Synchronized
    fun stop() {
        stopping = true
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
    }
}

/**
 * One DION channel: the whitelist-bypass joiner (libwbrelay.so) in its own process. It joins one room
 * as a guest, carries traffic over that call and offers it on its own local SOCKS5 port.
 * Protocol on stdout: "STATUS:<x>", "RESOLVE:<host>" (answered on stdin), anything else is a log line.
 */
class ChannelRelay(
    val n: Int,
    private val nativeDir: String,
    val socksPort: Int,
    private val socksUser: String,
    private val socksPass: String,
    private val onStatus: (Int, String) -> Unit,
) {
    private var proc: NativeProcess? = null

    fun start(slug: String) {
        stop()
        val binary = File(nativeDir, "libwbrelay.so")
        if (!binary.exists()) {
            onStatus(n, "ERROR:нет файла libwbrelay.so")
            return
        }
        lateinit var p: NativeProcess
        p = NativeProcess(
            "dion-ch$n",
            listOf(
                binary.absolutePath,
                "--mode", "dion-headless-joiner",
                "--ws-port", (19100 + n).toString(),
                "--socks-host", "127.0.0.1",
                "--socks-port", socksPort.toString(),
                "--socks-user", socksUser,
                "--socks-pass", socksPass,
            ),
            onLine = { line ->
                when {
                    line.startsWith("RESOLVE:") -> p.write(resolve(line.removePrefix("RESOLVE:")))
                    line.startsWith("STATUS:") -> onStatus(n, line.removePrefix("STATUS:"))
                    // The joiner is chatty (a line per connection); keep only the useful part.
                    line.contains("SOCKS CONNECT") || line.contains("SOCKS CONNECTED") ||
                        line.contains("first send") || line.contains("SOCKS ") && line.contains("read error") -> Unit
                    else -> DionState.log("[$n] $line")
                }
            },
            onExit = { code -> onStatus(n, "ERROR:процесс канала завершился (код $code)") },
        )
        proc = p
        p.start()
        p.write("JOIN:" + JSONObject().put("roomId", slug).put("displayName", "Mayak").toString())
    }

    fun stop() {
        proc?.stop()
        proc = null
    }

    private fun resolve(host: String): String =
        try {
            val all = InetAddress.getAllByName(host)
            (all.firstOrNull { it is Inet4Address } ?: all.first()).hostAddress ?: ""
        } catch (e: Exception) {
            DionState.log("[$n] не нашёл адрес $host: ${e.message}")
            ""
        }
}

/**
 * The balancer (libdionmux.so): one SOCKS5 port for tun2socks, connections spread over the channels.
 * We tell it which channels are up ("UP n" / "DOWN n"); it reports traffic as "STAT ..." lines.
 */
class Mux(
    private val nativeDir: String,
    private val listenPort: Int,
    private val user: String,
    private val pass: String,
    private val channelPorts: List<Int>,
    private val chUser: String,
    private val chPass: String,
    private val onStat: (List<MuxStat>) -> Unit,
    private val onExit: () -> Unit,
) {
    data class MuxStat(val n: Int, val up: Boolean, val active: Int, val rx: Long, val tx: Long)

    private var proc: NativeProcess? = null

    fun start(): Boolean {
        val binary = File(nativeDir, "libdionmux.so")
        if (!binary.exists()) {
            DionState.log("Нет файла libdionmux.so")
            return false
        }
        proc = NativeProcess(
            "dion-mux",
            listOf(
                binary.absolutePath,
                "--listen", "127.0.0.1:$listenPort",
                "--user", user,
                "--pass", pass,
                "--up", channelPorts.joinToString(",") { "127.0.0.1:$it" },
                "--up-user", chUser,
                "--up-pass", chPass,
            ),
            onLine = { line ->
                if (line.startsWith("STAT ")) parse(line)?.let(onStat) else DionState.log("[балансир] $line")
            },
            onExit = { code ->
                DionState.log("Балансир завершился (код $code)")
                onExit()
            },
        ).also { it.start() }
        return true
    }

    fun setUp(n: Int, up: Boolean) = proc?.write(if (up) "UP $n" else "DOWN $n")

    fun stop() {
        proc?.stop()
        proc = null
    }

    private fun parse(line: String): List<MuxStat>? = runCatching {
        line.removePrefix("STAT ").trim().split(' ').map { item ->
            val f = item.split(':')
            MuxStat(f[0].toInt(), f[1] == "up", f[2].toInt(), f[3].toLong(), f[4].toLong())
        }
    }.getOrNull()
}
