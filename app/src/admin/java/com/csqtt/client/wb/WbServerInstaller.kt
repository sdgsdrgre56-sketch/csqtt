package com.csqtt.client.wb

import android.content.Context
import com.csqtt.client.ui.createSSHClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.xfer.FileSystemFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Result of a server action: the join link when there is one, a short status and the raw log lines. */
data class WbServerResult(
    val ok: Boolean,
    val link: String = "",
    val status: String = "",
    val error: String = "",
    val log: List<String> = emptyList(),
)

data class WbSsh(val host: String, val port: Int, val user: String, val password: String)

/**
 * Installs / manages the WB Stream test node over SSH. Everything lives in /opt/mayak-wb and the
 * "mayak-wb" systemd service; CSQTT and the rest of the server are not touched.
 */
object WbServerInstaller {

    private const val STAGE = "/tmp/mayak-wb"

    suspend fun install(context: Context, ssh: WbSsh, wbToken: String): WbServerResult =
        withSession(ssh) { client ->
            val machine = exec(client, ssh, "uname -m", asRoot = false).second.trim().lowercase()
            val asset = when {
                machine.startsWith("x86_64") || machine == "amd64" -> "wbstream-linux-amd64"
                machine.startsWith("aarch64") || machine.startsWith("arm64") -> "wbstream-linux-arm64"
                else -> return@withSession WbServerResult(false, error = "Сервер $machine не поддерживается (нужен amd64 или arm64)")
            }
            WbState.log("Сервер: $machine, ставлю $asset")

            val work = File(context.cacheDir, "wb-install-${System.nanoTime()}").apply { mkdirs() }
            try {
                val binary = File(work, "wbstream")
                context.assets.open("wb/$asset").use { input -> binary.outputStream().use { input.copyTo(it) } }
                val script = File(work, "wb-node.sh")
                context.assets.open("wb/wb-node.sh").use { input -> script.outputStream().use { input.copyTo(it) } }
                val cookies = File(work, "cookies.json").apply {
                    writeText(JSONArray().put(JSONObject().put("name", "wb_access_token").put("value", wbToken)).toString())
                }

                exec(client, ssh, "rm -rf $STAGE && mkdir -p $STAGE && chmod 700 $STAGE", asRoot = false)
                upload(client, binary, "$STAGE/wbstream")
                upload(client, script, "$STAGE/wb-node.sh")
                upload(client, cookies, "$STAGE/cookies.json")
                WbState.log("Файлы загружены, запускаю узел")
                // Keep the script itself so later actions can call it.
                val (code, out) = exec(
                    client, ssh,
                    "install -D -m 0755 $STAGE/wb-node.sh /opt/mayak-wb/wb-node.sh && bash /opt/mayak-wb/wb-node.sh install",
                    asRoot = true,
                    timeoutSec = 120,
                )
                parse(code, out)
            } finally {
                work.deleteRecursively()
            }
        }

    suspend fun newRoom(ssh: WbSsh) = runScript(ssh, "newroom")
    suspend fun status(ssh: WbSsh) = runScript(ssh, "status")
    suspend fun uninstall(ssh: WbSsh) = runScript(ssh, "uninstall")

    private suspend fun runScript(ssh: WbSsh, action: String): WbServerResult =
        withSession(ssh) { client ->
            val (code, out) = exec(
                client, ssh,
                "if [ -x /opt/mayak-wb/wb-node.sh ]; then bash /opt/mayak-wb/wb-node.sh $action; " +
                    "else echo 'WB_STATUS=не установлен'; fi",
                asRoot = true,
                timeoutSec = 90,
            )
            parse(code, out)
        }

    private fun parse(code: Int, out: String): WbServerResult {
        var link = ""
        var status = ""
        var error = ""
        val log = mutableListOf<String>()
        out.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
            when {
                line.startsWith("WB_LINK=") -> link = line.removePrefix("WB_LINK=")
                line.startsWith("WB_STATUS=") -> status = line.removePrefix("WB_STATUS=")
                line.startsWith("WB_ERROR=") -> error = line.removePrefix("WB_ERROR=")
                line.startsWith("LOG: ") -> log += line.removePrefix("LOG: ")
                else -> log += line
            }
        }
        log.forEach { WbState.log("сервер: $it") }
        if (error.isEmpty() && code != 0 && link.isEmpty() && status.isEmpty()) {
            error = log.lastOrNull()?.take(200) ?: "команда завершилась с кодом $code"
        }
        return WbServerResult(ok = error.isEmpty(), link = link, status = status, error = error, log = log)
    }

    private suspend fun withSession(ssh: WbSsh, block: (SSHClient) -> WbServerResult): WbServerResult =
        withContext(Dispatchers.IO) {
            var client: SSHClient? = null
            try {
                WbState.log("SSH: подключаюсь к ${ssh.host}:${ssh.port}")
                client = createSSHClient(ssh.host, ssh.user, ssh.password, ssh.port)
                block(client)
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                WbState.log("SSH: $msg")
                WbServerResult(false, error = "SSH: $msg")
            } finally {
                runCatching { client?.disconnect() }
            }
        }

    private fun upload(client: SSHClient, file: File, remote: String) {
        client.newSFTPClient().use { sftp -> sftp.put(FileSystemFile(file), remote) }
    }

    /** Runs a command; as root directly, or through sudo with the SSH password when logged in as a user. */
    private fun exec(
        client: SSHClient,
        ssh: WbSsh,
        command: String,
        asRoot: Boolean,
        timeoutSec: Long = 30,
    ): Pair<Int, String> {
        val quoted = "'" + command.replace("'", "'\\''") + "'"
        val full = if (!asRoot) {
            command
        } else {
            "if [ \"\$(id -u)\" = 0 ]; then bash -c $quoted; else sudo -S -p '' bash -c $quoted; fi"
        }
        client.startSession().use { session ->
            val cmd = session.exec(full)
            if (asRoot && ssh.user != "root") {
                runCatching {
                    cmd.outputStream.write("${ssh.password}\n".toByteArray())
                    cmd.outputStream.flush()
                }
            }
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val errThread = Thread {
                runCatching { cmd.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
            }.apply { isDaemon = true; start() }
            runCatching { cmd.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) } }
            cmd.join(timeoutSec, TimeUnit.SECONDS)
            errThread.join(2000)
            val text = stdout.toString() + stderr.toString()
            return (cmd.exitStatus ?: -1) to text
        }
    }
}
