package app.mayak.dion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import net.schmizz.sshj.xfer.FileSystemFile
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.Security
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class Ssh(val host: String, val port: Int, val user: String, val password: String)

data class Account(val email: String, val password: String)

/** Result of a server action. links[n-1] is channel n's link ("" when that channel failed). */
data class ServerResult(
    val ok: Boolean,
    val links: Map<Int, String> = emptyMap(),
    val fails: Map<Int, String> = emptyMap(),
    val status: String = "",
    val error: String = "",
)

/**
 * Installs and manages the server node over SSH: /opt/mayak-dion, services mayak-dion@1..N.
 * CSQTT, 3x-ui and the Mayak Admin test node (mayak-call) are not touched.
 */
object DionInstaller {
    private const val STAGE = "/tmp/mayak-dion"
    private const val TOOL = "/usr/local/lib/mayak-dion/node.sh"
    private val bcReady = AtomicBoolean(false)

    /**
     * shared: the account for every channel without its own one; own[n]: an own account for channel n.
     * rooms[n]: a room to keep for channel n (otherwise the server keeps its old room or creates one).
     */
    suspend fun install(
        context: Context,
        ssh: Ssh,
        count: Int,
        shared: Account?,
        own: Map<Int, Account>,
        rooms: Map<Int, String>,
    ): ServerResult = withSession(ssh) { client ->
        val machine = exec(client, ssh, "uname -m", asRoot = false).second.trim().lowercase()
        val arch = when {
            machine.startsWith("x86_64") || machine == "amd64" -> "amd64"
            machine.startsWith("aarch64") || machine.startsWith("arm64") -> "arm64"
            else -> return@withSession ServerResult(false, error = "Сервер $machine не поддерживается (нужен amd64 или arm64)")
        }
        DionState.log("Сервер: $machine, ставлю узел на $count каналов")
        val work = File(context.cacheDir, "install-${System.nanoTime()}").apply { mkdirs() }
        try {
            val files = mutableListOf<Pair<File, String>>()
            fun local(name: String, text: String) = File(work, name).apply { writeText(text) }
            val binary = File(work, "creator")
            context.assets.open("server/dion-linux-$arch").use { i -> binary.outputStream().use { i.copyTo(it) } }
            files += binary to "creator"
            val script = File(work, "node.sh")
            context.assets.open("server/dion-node.sh").use { i -> script.outputStream().use { i.copyTo(it) } }
            files += script to "node.sh"
            shared?.let { files += local("cookies.json", cookies(it)) to "cookies.json" }
            own.forEach { (n, acc) -> files += local("cookies$n.json", cookies(acc)) to "cookies$n.json" }
            rooms.forEach { (n, slug) -> if (slug.isNotBlank()) files += local("room$n", slug) to "room$n" }

            exec(client, ssh, "rm -rf $STAGE && mkdir -p $STAGE && chmod 700 $STAGE", asRoot = false)
            client.newSFTPClient().use { sftp -> files.forEach { (f, name) -> sftp.put(FileSystemFile(f), "$STAGE/$name") } }
            DionState.log("Файлы загружены, каналы заходят в комнаты (до 2–3 минут)")
            val (code, out) = exec(
                client, ssh,
                "install -D -m 0755 $STAGE/node.sh $TOOL && bash $TOOL install $count",
                asRoot = true,
                timeoutSec = 240,
            )
            parse(code, out)
        } finally {
            work.deleteRecursively()
        }
    }

    suspend fun status(ssh: Ssh) = run(ssh, "status")
    suspend fun newRooms(ssh: Ssh) = run(ssh, "newroom all")
    suspend fun uninstall(ssh: Ssh) = run(ssh, "uninstall")

    private fun cookies(acc: Account): String =
        JSONObject().put("email", acc.email.trim()).put("password", acc.password).put("cookies", JSONArray()).toString()

    private suspend fun run(ssh: Ssh, action: String): ServerResult = withSession(ssh) { client ->
        val (code, out) = exec(
            client, ssh,
            "if [ -x $TOOL ]; then bash $TOOL $action; else echo 'DION_STATUS=не установлен'; fi",
            asRoot = true,
            timeoutSec = 240,
        )
        parse(code, out)
    }

    private fun parse(code: Int, out: String): ServerResult {
        val links = mutableMapOf<Int, String>()
        val fails = mutableMapOf<Int, String>()
        var status = ""
        var error = ""
        val log = mutableListOf<String>()
        val keyed = Regex("^DION_(LINK|FAIL|STATE)(\\d+)=(.*)$")
        out.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
            val m = keyed.find(line)
            when {
                m != null -> {
                    val n = m.groupValues[2].toInt()
                    val v = m.groupValues[3]
                    when (m.groupValues[1]) {
                        "LINK" -> links[n] = v
                        "FAIL" -> { fails[n] = v; log += "канал $n: $v" }
                        "STATE" -> log += "канал $n: $v"
                    }
                }
                line.startsWith("DION_STATUS=") -> status = line.removePrefix("DION_STATUS=")
                line.startsWith("DION_ERROR=") -> error = line.removePrefix("DION_ERROR=")
                line.startsWith("LOG: ") -> log += line.removePrefix("LOG: ")
                else -> log += line
            }
        }
        log.forEach { DionState.log("сервер: $it") }
        if (error.isEmpty() && code != 0 && links.isEmpty() && status.isEmpty()) {
            error = log.lastOrNull()?.take(200) ?: "команда завершилась с кодом $code"
        }
        return ServerResult(error.isEmpty(), links, fails, status, error)
    }

    private suspend fun withSession(ssh: Ssh, block: (SSHClient) -> ServerResult): ServerResult =
        withContext(Dispatchers.IO) {
            var client: SSHClient? = null
            try {
                DionState.log("SSH: подключаюсь к ${ssh.host}:${ssh.port}")
                client = connect(ssh)
                block(client)
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                DionState.log("SSH: $msg")
                ServerResult(false, error = "SSH: $msg")
            } finally {
                runCatching { client?.disconnect() }
            }
        }

    private fun connect(ssh: Ssh): SSHClient {
        if (bcReady.compareAndSet(false, true)) {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
        val c = SSHClient()
        c.connectTimeout = 30000
        c.timeout = 300000
        c.addHostKeyVerifier(PromiscuousVerifier())
        c.connect(ssh.host, ssh.port)
        runCatching { c.connection.keepAlive.keepAliveInterval = 10 }
        try {
            c.authPassword(ssh.user, ssh.password)
        } catch (e: UserAuthException) {
            // Some hosting panels allow passwords only through keyboard-interactive.
            c.auth(
                ssh.user,
                listOf(AuthKeyboardInteractive(PasswordResponseProvider(PasswordUtils.createOneOff(ssh.password.toCharArray())))),
            )
        }
        return c
    }

    /** Runs a command; as root directly, or through sudo with the SSH password when logged in as a user. */
    private fun exec(client: SSHClient, ssh: Ssh, command: String, asRoot: Boolean, timeoutSec: Long = 30): Pair<Int, String> {
        val quoted = "'" + command.replace("'", "'\\''") + "'"
        val full = if (!asRoot) command else "if [ \"\$(id -u)\" = 0 ]; then bash -c $quoted; else sudo -S -p '' bash -c $quoted; fi"
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
            val errThread = Thread { runCatching { cmd.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } } }
                .apply { isDaemon = true; start() }
            runCatching { cmd.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) } }
            cmd.join(timeoutSec, TimeUnit.SECONDS)
            errThread.join(2000)
            return (cmd.exitStatus ?: -1) to (stdout.toString() + stderr.toString())
        }
    }
}
