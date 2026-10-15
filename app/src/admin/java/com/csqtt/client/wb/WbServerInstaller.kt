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
 * Installs / manages the call-tunnel test node over SSH. Everything lives in /opt/mayak-call and the
 * "mayak-call" systemd service; CSQTT and the rest of the server are not touched.
 */
object WbServerInstaller {

    private const val STAGE = "/tmp/mayak-wb"

    private const val TOOL = "/usr/local/lib/mayak-call/node.sh"
    private const val LEGACY_TOOL = "/opt/mayak-wb/wb-node.sh"

    /** What the server node signs in with. */
    data class NodeLogin(
        val roomId: String = "",
        val wbToken: String = "",
        val dionEmail: String = "",
        val dionPassword: String = "",
    )

    /**
     * DION: the node signs in with the e-mail/password (it re-logs in by itself) and creates or rejoins its room.
     * WB: with roomId it joins that existing room as a guest (WB lets only its staff create rooms);
     * without it, it needs a staff wbToken to create one.
     */
    suspend fun install(context: Context, ssh: WbSsh, platform: CallPlatform, login: NodeLogin): WbServerResult =
        withSession(ssh) { client ->
            val machine = exec(client, ssh, "uname -m", asRoot = false).second.trim().lowercase()
            val arch = when {
                machine.startsWith("x86_64") || machine == "amd64" -> "amd64"
                machine.startsWith("aarch64") || machine.startsWith("arm64") -> "arm64"
                else -> return@withSession WbServerResult(false, error = "Сервер $machine не поддерживается (нужен amd64 или arm64)")
            }
            val asset = "${platform.assetPrefix}-linux-$arch"
            WbState.log("Сервер: $machine, ставлю узел ${platform.title}")

            val work = File(context.cacheDir, "wb-install-${System.nanoTime()}").apply { mkdirs() }
            try {
                val binary = File(work, "creator")
                context.assets.open("wb/$asset").use { input -> binary.outputStream().use { input.copyTo(it) } }
                val script = File(work, "node.sh")
                context.assets.open("wb/wb-node.sh").use { input -> script.outputStream().use { input.copyTo(it) } }
                val cookiesJson = when (platform) {
                    CallPlatform.DION -> if (login.dionEmail.isNotBlank() && login.dionPassword.isNotBlank()) {
                        JSONObject()
                            .put("email", login.dionEmail.trim())
                            .put("password", login.dionPassword)
                            .put("cookies", JSONArray())
                            .toString()
                    } else {
                        null
                    }
                    CallPlatform.WB -> if (login.wbToken.isNotBlank()) {
                        JSONArray().put(JSONObject().put("name", "wb_access_token").put("value", login.wbToken)).toString()
                    } else {
                        null
                    }
                }
                val cookies = cookiesJson?.let { json -> File(work, "cookies.json").apply { writeText(json) } }
                val room = login.roomId.takeIf { it.isNotBlank() }?.let { id -> File(work, "room").apply { writeText(id) } }
                if (cookies == null && room == null) {
                    return@withSession WbServerResult(false, error = "Нет ни входа, ни ссылки на комнату")
                }

                exec(client, ssh, "rm -rf $STAGE && mkdir -p $STAGE && chmod 700 $STAGE", asRoot = false)
                upload(client, binary, "$STAGE/creator")
                upload(client, script, "$STAGE/node.sh")
                cookies?.let { upload(client, it, "$STAGE/cookies.json") }
                room?.let { upload(client, it, "$STAGE/room") }
                WbState.log(
                    when {
                        room != null && cookies == null -> "Файлы загружены, узел заходит гостем в твою комнату"
                        room != null -> "Файлы загружены, узел заходит в свою комнату"
                        else -> "Файлы загружены, узел создаёт комнату"
                    },
                )
                // Keep the script on the server so later actions can call it.
                val (code, out) = exec(
                    client, ssh,
                    "install -D -m 0755 $STAGE/node.sh $TOOL && bash $TOOL install ${platform.id}",
                    asRoot = true,
                    timeoutSec = 150,
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
                // The first test build kept its script under /opt/mayak-wb.
                "S=$TOOL; [ -x \$S ] || S=$LEGACY_TOOL; " +
                    "if [ -x \$S ]; then bash \$S $action; else echo 'WB_STATUS=не установлен'; fi",
                asRoot = true,
                timeoutSec = 100,
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
