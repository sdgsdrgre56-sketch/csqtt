// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client.ui

import android.content.Context
import com.csqtt.client.CsqttConstants
import com.csqtt.client.BuhtaPanel
import com.csqtt.client.SettingsStore
import com.csqtt.client.BuhtaRoom
import com.csqtt.client.DeployManager
import com.csqtt.client.TunnelManager
import net.schmizz.sshj.SSHClient as SshjClient
import net.schmizz.sshj.xfer.FileSystemFile
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.Security
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private const val CMD_TIMEOUT = CsqttConstants.Timeouts.DEPLOY_CMD_TIMEOUT_MS
private const val SFTP_UPLOAD_ATTEMPTS = 2
private const val UPLOAD_RECONNECT_ATTEMPTS = 1

/**
 * SSH passwords are protocol data, not shell text. Spaces and punctuation are
 * valid and must arrive at SSHJ unchanged; only pasted line breaks are removed
 * because neither password SSH nor sudo can represent them as one prompt.
 */
internal fun sanitizeSshPassword(value: String): String =
    value.replace("\r", "").replace("\n", "")

internal fun isSuccessfulDeployResult(exitStatus: Int, output: String): Boolean =
    exitStatus == 0 && output.lineSequence().any { it.trim() == "CSQTT_DEPLOY_OK" }

private fun deployFailureDetail(output: String): String? =
    output.lineSequence()
        .map(String::trim)
        .lastOrNull { it.startsWith("CSQTT_DEPLOY_ERROR|") }
        ?.split("|", limit = 3)
        ?.getOrNull(2)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(360)

internal fun deployFailureMessage(exitStatus: Int, output: String): String {
    val detail = deployFailureDetail(output)
    fun withDetail(message: String): String =
        if (detail == null) message else "$message Причина: $detail"

    return when {
        output.lineSequence().any { it.trim() == "CSQTT_DEPLOY_ROLLED_BACK" } || exitStatus == 30 ->
            withDetail("Новый релиз не прошёл проверку; предыдущая установка восстановлена")
        output.lineSequence().any { it.trim() == "CSQTT_DEPLOY_ROLLBACK_FAILED" } || exitStatus == 31 ->
            withDetail("Новый релиз не запустился, а автоматический rollback завершился с ошибкой — требуется проверка VPS")
        exitStatus == 20 ->
            withDetail("Кандидат не прошёл предзапусковую проверку; работающий сервер не останавливался")
        exitStatus == 2 ->
            withDetail("Установщик отклонил параметры развёртывания")
        else ->
            detail ?: "Установщик завершился с кодом $exitStatus; подробности сохранены в errors.log"
    }
}

internal fun friendlyDeployError(message: String?): String {
    val text = message.orEmpty()
    return when {
        text.contains("Auth fail (public key)", ignoreCase = true) ||
            text.contains("Auth fail (key parse)", ignoreCase = true) ->
            "SSH: Auth fail — ключ не принят сервером. Проверьте: 1) приватный ключ вставлен целиком (-----BEGIN...-----END), 2) публичный ключ добавлен в authorized_keys на VPS, 3) логин верный"
        text.contains("Auth fail", ignoreCase = true) ->
            "SSH-аутентификация отклонена: проверьте логин, пароль и разрешение PasswordAuthentication на VPS"
        text.contains("authentication", ignoreCase = true) ->
            "SSH-аутентификация отклонена: проверьте логин, пароль и разрешение PasswordAuthentication на VPS"
        text.contains("SFTP/SCP upload failed", ignoreCase = true) ->
            "Загрузка на VPS оборвалась даже после автоматического переподключения SSH; проверьте стабильность Wi-Fi и подробную причину в errors.log"
        text.contains("reject HostKey", ignoreCase = true) ||
            text.contains("HostKey", ignoreCase = true) ||
            text.contains("host key", ignoreCase = true) ->
            "SSH-ключ сервера изменился или не принят"
        text.contains("timeout", ignoreCase = true) ->
            "Истекло время ожидания SSH/SFTP"
        text.contains("UnknownHost", ignoreCase = true) ||
            text.contains("No route to host", ignoreCase = true) ->
            "VPS недоступен по указанному адресу"
        text.contains("session is down", ignoreCase = true) ||
            text.contains("connection is closed", ignoreCase = true) ->
            "SSH-соединение было разорвано"
        text.contains("Connection refused", ignoreCase = true) ->
            "SSH: соединение отклонено — проверьте порт и доступность VPS"
        text.isBlank() -> "Неизвестная ошибка установки"
        else -> text.take(240)
    }
}

internal enum class DeployOutputLevel { LOG, OK, ERR }

internal data class DeployOutputLine(
    val message: String,
    val level: DeployOutputLevel,
)

private val ANSI_ESCAPE = Regex("\u001B\\[[;\\d]*m")
private val DEPLOY_SYMBOLS = Regex("[\\p{So}\\p{Sk}\\uFE0F\\u200D]")
private val DEPLOY_STATUS_PREFIX = Regex("^\\[(?:OK|ERR|LOG|WARN|✓|!|✗|►)]\\s*", RegexOption.IGNORE_CASE)

internal fun parseDeployOutputLine(rawLine: String): DeployOutputLine? {
    val raw = rawLine.replace(ANSI_ESCAPE, "").trim()
    if (
        raw.isEmpty() ||
            raw.startsWith("CSQTT_PROGRESS|") ||
            raw.startsWith("CSQTT_DEPLOY_ERROR|") ||
            raw == "CSQTT_DEPLOY_OK" ||
            raw == "CSQTT_DEPLOY_ROLLED_BACK" ||
            raw == "CSQTT_DEPLOY_ROLLBACK_FAILED"
    ) return null

    val warning = raw.startsWith("[WARN]", ignoreCase = true) || raw.startsWith("[!]") || raw.startsWith("⚠")
    val explicitError = raw.startsWith("[ERR]", ignoreCase = true) || raw.startsWith("[✗]") || raw.startsWith("✗")
    val success = raw.startsWith("[OK]", ignoreCase = true) || raw.startsWith("[✓]") || raw.startsWith("✓")
    val lower = raw.lowercase()
    val looksLikeError = explicitError || (!warning && (
        lower.contains("error:") ||
            lower.contains("failed") ||
            lower.contains("fatal") ||
            lower.contains("не запуст") ||
            lower.contains("ошибка")
        ))

    var clean = raw
        .replace(DEPLOY_STATUS_PREFIX, "")
        .replace(DEPLOY_SYMBOLS, "")
        .trim { it.isWhitespace() || it in "═║╔╗╚╝─━" }
        .replace(Regex("\\s+"), " ")
        .trim()
    if (clean.isEmpty() || clean.all { it == '=' || it == '-' || it == '_' }) return null
    if (warning && !clean.startsWith("Предупреждение:", ignoreCase = true)) {
        clean = "Предупреждение: $clean"
    }

    val level = when {
        looksLikeError -> DeployOutputLevel.ERR
        success -> DeployOutputLevel.OK
        else -> DeployOutputLevel.LOG
    }
    return DeployOutputLine(clean.take(500), level)
}

private fun publishDeployOutput(rawLine: String) {
    val line = parseDeployOutputLine(rawLine) ?: return
    when (line.level) {
        DeployOutputLevel.LOG -> TunnelManager.addDeployInfoLog(line.message)
        DeployOutputLevel.OK -> TunnelManager.addDeploySuccessLog(line.message)
        DeployOutputLevel.ERR -> TunnelManager.addDeployErrorLog(line.message)
    }
}

internal enum class ServerArchitecture(
    val assetName: String,
    val displayName: String,
) {
    AMD64("csqtt-linux-amd64", "amd64"),
    ARM64("csqtt-linux-arm64", "ARM64"),
    ARMV7("csqtt-linux-armv7", "ARM32"),
}

internal fun serverArchitectureForMachine(output: String): ServerArchitecture {
    val machine = output.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }
        ?.lowercase(Locale.ROOT)
        ?: throw IOException("VPS не вернул архитектуру через uname -m")
    return when (machine) {
        "x86_64", "amd64" -> ServerArchitecture.AMD64
        "aarch64", "arm64" -> ServerArchitecture.ARM64
        "armv7l", "armv7", "armhf" -> ServerArchitecture.ARMV7
        else -> throw IOException(
            "Архитектура VPS $machine пока не поддерживается. Поддерживаются: x86_64, aarch64, armv7l",
        )
    }
}

private fun deployAssetLabel(fileName: String): String = when (fileName) {
    "deploy.sh" -> "скрипт установки"
    "csqtt-linux-amd64" -> "сервер CSQTT (amd64)"
    "csqtt-linux-arm64" -> "сервер CSQTT (ARM64)"
    "csqtt-linux-armv7" -> "сервер CSQTT (ARM32)"
    "csqtt.env" -> "настройки веб-панели"
    "csqtt-deploy.json" -> "настройки доступа"
    "hev-socks5-tunnel" -> "C-движок туннеля"
    else -> fileName
}

private fun readableFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f МБ", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f КБ", bytes / 1024.0)
    else -> "$bytes Б"
}

private data class SSHExecResult(val output: String, val exitStatus: Int)

private class DeploySSHClient(
    private var ssh: SshjClient,
    private val sudoPass: String,
    private val reconnect: ((String) -> SshjClient)? = null,
) {

    private fun reconnectFor(stage: String): Boolean {
        val factory = reconnect ?: return false
        return try {
            runCatching { ssh.disconnect() }
            TunnelManager.addDeployInfoLog("SSH-соединение восстановление: $stage")
            ssh = factory(stage)
            DeployManager.activeSession = ssh
            TunnelManager.addDeploySuccessLog("SSH-соединение восстановлено")
            true
        } catch (error: Exception) {
            DeployManager.writeError(
                "SSH reconnect failed during $stage (${error.javaClass.simpleName}): ${error.message}",
            )
            false
        }
    }

    private fun ensureConnected(stage: String): Boolean =
        ssh.isConnected || reconnectFor(stage)

    fun exec(command: String, timeout: Long = CMD_TIMEOUT): String =
        execResult(command, timeout).output

    fun execResult(command: String, timeout: Long = CMD_TIMEOUT): SSHExecResult {
        if (!ensureConnected("команда")) {
            DeployManager.writeError("SSH exec: клиент отключён перед командой: ${command.take(80)}")
            return SSHExecResult("error: session is down", -1)
        }

        val result = StringBuilder()
        val progressRegex = Regex("^CSQTT_PROGRESS\\|(\\d+\\.?\\d*)\\|(.+)$")
        val cmdAdjusted = if (command.contains("sudo") && !command.contains("sudo -S")) {
            command.replace("sudo ", "sudo -S ")
        } else command

        val sshSession = ssh.startSession()
        return try {
            val remoteCmd = sshSession.exec(cmdAdjusted)

            if (cmdAdjusted.contains("sudo -S")) {
                runCatching {
                    remoteCmd.outputStream.write("$sudoPass\n".toByteArray())
                    remoteCmd.outputStream.flush()
                }
            }

            val stdoutDone = CountDownLatch(1)
            val stderrDone = CountDownLatch(1)

            val stdoutThread = Thread({
                try {
                    remoteCmd.inputStream.bufferedReader().forEachLine { line ->
                        val clean = line.replace(ANSI_ESCAPE, "")
                        val match = progressRegex.find(clean.trim())
                        when {
                            match != null -> {
                                val p = match.groupValues[1].toFloatOrNull() ?: 0f
                                val step = match.groupValues[2].trim()
                                DeployManager.updateProgress(p, step)
                                TunnelManager.addDeployInfoLog("Этап: $step")
                            }
                            !clean.contains("CSQTT_PROGRESS") -> {
                                synchronized(result) { result.appendLine(clean) }
                                publishDeployOutput(clean)
                                val parsed = parseDeployOutputLine(clean)
                                if (parsed?.level == DeployOutputLevel.ERR) {
                                    DeployManager.writeError("REMOTE: $clean")
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
                stdoutDone.countDown()
            }, "deploy-stdout")
            stdoutThread.isDaemon = true
            stdoutThread.start()

            val stderrThread = Thread({
                try {
                    remoteCmd.errorStream.bufferedReader().forEachLine { line ->
                        if (!line.contains("password for")) {
                            val clean = line.replace(ANSI_ESCAPE, "")
                            if (clean.isNotBlank() && !isBenignStderr(clean)) {
                                synchronized(result) { result.appendLine(clean) }
                                DeployManager.writeError("STDERR: $clean")
                                val display = parseDeployOutputLine(clean)?.message ?: clean.trim().take(500)
                                TunnelManager.addDeployErrorLog("Сервер: $display")
                            }
                        }
                    }
                } catch (_: Exception) {}
                stderrDone.countDown()
            }, "deploy-stderr")
            stderrThread.isDaemon = true
            stderrThread.start()

            val finished = stdoutDone.await(timeout, TimeUnit.MILLISECONDS)
            stderrDone.await(2, TimeUnit.SECONDS)

            if (!finished) {
                DeployManager.writeError("SSH timeout (${timeout / 1000}s): ${command.take(80)}")
                return SSHExecResult("error: timeout", -1)
            }

            remoteCmd.join(3, TimeUnit.SECONDS)
            SSHExecResult(result.toString(), remoteCmd.exitStatus ?: -1)
        } catch (e: Exception) {
            DeployManager.writeError("SSH exec error: ${e.message} | cmd: ${command.take(80)}")
            TunnelManager.addDeployErrorLog("SSH exec error: ${e.message}")
            SSHExecResult("error: ${e.message}", -1)
        } finally {
            runCatching { sshSession.close() }
        }
    }

    fun upload(localFile: File, remotePath: String) {
        uploadInternal(localFile, remotePath, UPLOAD_RECONNECT_ATTEMPTS)
    }

    private fun uploadInternal(localFile: File, remotePath: String, reconnectsLeft: Int) {
        if (!localFile.isFile || !localFile.canRead()) {
            throw IOException("Local deploy file is unavailable: ${localFile.name}")
        }
        if (!ensureConnected("загрузка ${deployAssetLabel(localFile.name)}")) {
            throw IOException("SSH client disconnected before upload: ${localFile.name}")
        }

        val label = deployAssetLabel(localFile.name)
        TunnelManager.addDeployInfoLog("Загрузка: $label (${readableFileSize(localFile.length())})")
        var sftpSuccess = false
        var lastError = "SFTP channel did not accept the file"
        repeat(SFTP_UPLOAD_ATTEMPTS) { attempt ->
            try {
                ssh.newSFTPClient().use { sftp ->
                    sftp.put(FileSystemFile(localFile), remotePath)
                    val remoteSize = sftp.lstat(remotePath).size
                    if (remoteSize == localFile.length()) {
                        TunnelManager.addDeploySuccessLog("Загружено: $label")
                        sftpSuccess = true
                        return
                    }
                }
            } catch (e: Exception) {
                lastError = "SFTP: ${e.message ?: e.javaClass.simpleName}"
                DeployManager.writeError(
                    "SFTP upload attempt ${attempt + 1}/$SFTP_UPLOAD_ATTEMPTS failed: " +
                        "${e.message} | file: ${localFile.name}"
                )
                if (attempt + 1 < SFTP_UPLOAD_ATTEMPTS && ssh.isConnected) {
                    Thread.sleep(500)
                }
            }
        }

        if (!sftpSuccess && ssh.isConnected) {
            try {
                DeployManager.writeError("Falling back to SCP for ${localFile.name}...")
                ssh.newSCPFileTransfer().upload(FileSystemFile(localFile), remotePath)
                TunnelManager.addDeploySuccessLog("Загружено (SCP): $label")
                return
            } catch (e: Exception) {
                lastError = "SCP: ${e.message ?: e.javaClass.simpleName}"
                DeployManager.writeError("SCP upload failed: ${e.message} | file: ${localFile.name}")
            }
        }

        if (ssh.isConnected) {
            try {
                DeployManager.writeError("Falling back to chunked stream upload for ${localFile.name}...")
                val tempRemote = "$remotePath.tmp"
                execResult(rootCommand("rm -f '$tempRemote'"), 30000L)
                localFile.inputStream().buffered(64 * 1024).use { inputStream ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    var totalUploaded = 0L
                    val totalLength = localFile.length()
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        val chunk = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
                        val base64 = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
                        val cmd = "printf '%s' '$base64' | base64 -d >> '$tempRemote'"
                        val res = execResult(rootCommand(cmd), 60000L)
                        if (res.exitStatus != 0) {
                            throw IOException("Chunk upload failed at offset $totalUploaded")
                        }
                        totalUploaded += bytesRead
                        val percent = (totalUploaded.toFloat() / totalLength.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        DeployManager.updateProgress(percent * 0.5f, "Загрузка: $label (${(percent * 100).toInt()}%)")
                    }
                }
                val moveRes = execResult(rootCommand("mv -f '$tempRemote' '$remotePath' && chmod 0755 '$remotePath'"), 30000L)
                if (moveRes.exitStatus == 0) {
                    TunnelManager.addDeploySuccessLog("Загружено (Stream): $label")
                    return
                }
            } catch (e: Exception) {
                lastError = "stream: ${e.message ?: e.javaClass.simpleName}"
                DeployManager.writeError("Stream upload failed: ${e.message} | file: ${localFile.name}")
            }
        }

        if (reconnectsLeft > 0 && reconnectFor("повторная загрузка $label")) {
            uploadInternal(localFile, remotePath, reconnectsLeft - 1)
            return
        }

        val message = "SFTP/SCP upload failed for ${localFile.name}: $lastError"
        TunnelManager.addDeployErrorLog("Не удалось загрузить $label: $lastError")
        throw IOException(message)
    }
}

private val bcInitialized = AtomicBoolean(false)

private fun ensureBouncyCastle() {
    if (bcInitialized.compareAndSet(false, true)) {
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}

private fun createSSHClient(
    host: String,
    user: String,
    pass: String,
    port: Int = 22,
    privateKey: String = "",
    keyPassphrase: String = "",
): SshjClient {
    require(host.isNotBlank()) { "SSH host is empty" }
    require(user.isNotBlank()) { "SSH login is empty" }
    val keysMode = privateKey.isNotBlank()
    require(keysMode || pass.isNotEmpty()) { "SSH password is empty" }
    require(port in 1..65535) { "Invalid SSH port: $port" }

    ensureBouncyCastle()
    val ssh = SshjClient()
    ssh.connectTimeout = 30000
    ssh.timeout = 300000
    ssh.addHostKeyVerifier(PromiscuousVerifier())

    try {
        ssh.connect(host, port)
        runCatching { ssh.connection.keepAlive.keepAliveInterval = 10 }
    } catch (e: Exception) {
        DeployManager.writeError("SSH connect failed (${e.javaClass.simpleName}): ${e.message}")
        throw e
    }

    if (keysMode) {
        try {
            DeployManager.writeError("SSH key mode: key=${privateKey.length}b, passphrase=${keyPassphrase.isNotBlank()}")
            val passwordFinder = if (keyPassphrase.isNotBlank()) {
                PasswordUtils.createOneOff(keyPassphrase.toCharArray())
            } else {
                null
            }
            val keyProvider = ssh.loadKeys(privateKey, null, passwordFinder)
            ssh.authPublickey(user, keyProvider)
        } catch (e: UserAuthException) {
            DeployManager.writeError("SSH pubkey auth failed: ${e.message}")
            throw IOException("Auth fail (public key): ${e.message}", e)
        } catch (e: IOException) {
            DeployManager.writeError("SSH key parse error: ${e.message}")
            throw IOException("Auth fail (key parse): ${e.message}", e)
        }
    } else {
        try {
            // `authPassword` carries the exact Kotlin String to SSHJ, which
            // is important for passwords containing spaces or punctuation.
            ssh.authPassword(user, pass)
        } catch (passwordError: UserAuthException) {
            try {
                // Some hosting panels expose password auth only through the
                // keyboard-interactive method. Use a fresh character array;
                // a one-off provider may consume its input while answering.
                ssh.auth(
                    user,
                    listOf(
                        AuthKeyboardInteractive(
                            PasswordResponseProvider(
                                PasswordUtils.createOneOff(pass.toCharArray()),
                            ),
                        ),
                    ),
                )
            } catch (keyboardError: UserAuthException) {
                DeployManager.writeError(
                    "SSH password auth failed: password=${passwordError.message}; " +
                        "keyboard-interactive=${keyboardError.message}",
                )
                throw IOException("Auth fail (password): ${keyboardError.message}", keyboardError)
            }
        }
    }

    return ssh
}

private fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
}

private fun systemdEnvironmentValue(value: String): String = buildString {
    append('"')
    value.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n', '\r' -> append(' ')
            else -> append(ch)
        }
    }
    append('"')
}

private fun dockerEnvironmentValue(value: String): String =
    value.replace('\n', ' ').replace('\r', ' ')

internal fun deployEnvironmentValue(value: String, installInDocker: Boolean): String =
    if (installInDocker) dockerEnvironmentValue(value) else systemdEnvironmentValue(value)

internal fun deployMode(installInDocker: Boolean): String =
    if (installInDocker) "docker" else "systemd"

private fun rootCommand(command: String): String {
    val quoted = shellQuote(command)
    return "if [ \"\$(id -u)\" = \"0\" ]; then bash -c $quoted; " +
        "elif command -v sudo >/dev/null 2>&1; then sudo bash -c $quoted; " +
        "else echo 'error: root privileges required and sudo not found'; exit 1; fi"
}

private val BENIGN_STDERR = Regex(
    "debconf|dpkg-preconfigure|TERM is not set|falling back to frontend|" +
    "unable to re-open stdin|controlling tty|unable to initialize frontend|" +
    "^Warning:|frontend:\\s*(Readline|Teletype|Dialog)",
    RegexOption.IGNORE_CASE
)

private fun isBenignStderr(line: String): Boolean = BENIGN_STDERR.containsMatchIn(line)

private const val BUHTA_SCRIPT = "/tmp/buhta-deploy.sh"

private suspend fun runBuhtaScript(
    context: Context,
    host: String, user: String, pass: String, port: Int,
    privateKey: String, keyPassphrase: String,
    action: String, room: String, panelUser: String, panelPass: String,
    onProgress: (Float, String) -> Unit,
    startProgress: Float,
    title: String,
): SSHExecResult {
    var ssh: SshjClient? = null
    var tmp: File? = null
    try {
        TunnelManager.beginDeployLog("$title: $host:$port")
        onProgress(0.02f, "Подключение к серверу...")
        val initialSsh = createSSHClient(host, user, pass, port, privateKey, keyPassphrase)
        ssh = initialSsh
        DeployManager.activeSession = initialSsh
        val client = DeploySSHClient(initialSsh, pass) { stage ->
            TunnelManager.addDeployInfoLog("Повторное SSH-подключение: $stage")
            createSSHClient(host, user, pass, port, privateKey, keyPassphrase).also { ssh = it }
        }
        TunnelManager.addDeploySuccessLog("SSH-соединение установлено")
        onProgress(0.08f, "Подготовка установщика...")
        val file = File(context.cacheDir, "buhta-deploy-${System.nanoTime()}.sh")
        tmp = file
        context.assets.open("buhta-deploy.sh").use { i -> FileOutputStream(file).use { o -> i.copyTo(o) } }
        client.upload(file, BUHTA_SCRIPT)
        onProgress(startProgress, "$title...")
        val args = "$action ${shellQuote(room)} ${shellQuote(panelUser)} ${shellQuote(panelPass)}"
        return client.execResult(rootCommand("sh $BUHTA_SCRIPT $args"), timeout = CMD_TIMEOUT)
    } finally {
        try { ssh?.disconnect() } catch (_: Exception) {}
        DeployManager.activeSession = null
        runCatching { tmp?.delete() }
    }
}

private fun finishDeploy(result: SSHExecResult, okText: String): Boolean {
    return if (isSuccessfulDeployResult(result.exitStatus, result.output)) {
        TunnelManager.addDeploySuccessLog(okText)
        DeployManager.stopDeploy("success")
        true
    } else {
        val msg = deployFailureMessage(result.exitStatus, result.output)
        DeployManager.writeError("Deploy failed: exit=${result.exitStatus}\n${result.output.takeLast(1200)}")
        TunnelManager.addDeployErrorLog(msg)
        DeployManager.stopDeploy(msg)
        false
    }
}

private fun deployFailed(e: Exception): Boolean {
    val friendly = friendlyDeployError(e.message)
    DeployManager.writeError("Deploy error (${e.javaClass.simpleName}): ${e.message}")
    TunnelManager.addDeployErrorLog(friendly)
    DeployManager.stopDeploy(friendly)
    return false
}

/** Шаг 1: ставит сервер и панель. Комнату приложение спросит после установки. */
internal suspend fun performDeploy(
    context: Context,
    host: String, user: String, pass: String, port: Int,
    mainPass: String, webLogin: String, webPass: String,
    peerPort: Int, webPort: Int,
    onProgress: (Float, String) -> Unit,
    privateKey: String = "",
    keyPassphrase: String = "",
    certificate: String = "",
    installInDocker: Boolean = false,
): Boolean = withContext(Dispatchers.IO) {
    try {
        val r = runBuhtaScript(
            context, host, user, pass, port, privateKey, keyPassphrase,
            "install", "", webLogin, webPass, onProgress, 0.2f, "Установка сервера и панели",
        )
        val ok = finishDeploy(r, "Сервер и панель установлены")
        if (ok && r.output.contains("Нужна ссылка на комнату")) {
            onProgress(0.9f, "Нужна ссылка на комнату")
            DeployManager.needRoom.value = true
        }
        ok
    } catch (e: CancellationException) {
        DeployManager.stopDeploy("Установка отменена")
        throw e
    } catch (e: Exception) {
        deployFailed(e)
    }
}

/** Шаг 2: задаёт комнату на сервере, создаёт ключ в панели и сохраняет его как «комнату» клиента. */
internal suspend fun performSetRoom(
    context: Context,
    settingsStore: SettingsStore,
    host: String, user: String, pass: String, port: Int,
    room: String, webLogin: String, webPass: String, webPort: Int,
    onProgress: (Float, String) -> Unit,
    privateKey: String = "",
    keyPassphrase: String = "",
): Boolean = withContext(Dispatchers.IO) {
    try {
        val targets = BuhtaRoom.parseAll(room)
        if (targets.isEmpty()) throw IOException("Это не ссылка на комнату Saby Meet")
        val roomsArg = targets.joinToString(",") { it.room }
        val r = runBuhtaScript(
            context, host, user, pass, port, privateKey, keyPassphrase,
            "setroom", roomsArg, webLogin, webPass, onProgress, 0.3f,
            if (targets.size > 1) "Запуск сервера в ${targets.size} комнатах" else "Запуск сервера в комнате",
        )
        if (!isSuccessfulDeployResult(r.exitStatus, r.output)) return@withContext finishDeploy(r, "")
        if (targets.size > 1) {
            // Несколько потоков: панели/ключа нет, сохраняем все комнаты как есть.
            val save = targets.joinToString("\n") { if (it.key.isNotEmpty()) it.key else it.room }
            settingsStore.saveParticipantTunnelSettings(save, targets.size)
            TunnelManager.addDeploySuccessLog("Сервер запущен в ${targets.size} комнатах (режим потоков)")
            onProgress(1.0f, "Готово!")
            DeployManager.stopDeploy("success")
            return@withContext true
        }
        val target = targets.first()
        onProgress(0.8f, "Ключ подключения...")
        var key: String? = Regex("BUHTA_KEY\\|(buhta://[A-Za-z0-9_=-]+)").find(r.output)?.groupValues?.get(1)
        if (key == null) {
            key = BuhtaPanel.autoKey(host, webPort, webLogin, webPass, "phone") { line ->
                TunnelManager.addDeployInfoLog(line)
            }
        }
        val save = key ?: target.room
        settingsStore.saveParticipantTunnelSettings(save, 1)
        if (key != null) {
            TunnelManager.addDeploySuccessLog("Ключ создан и сохранён в приложении")
        } else {
            TunnelManager.addDeployWarningLog("Ключ не создан — сохранена ссылка на комнату (режим без ключа)")
        }
        onProgress(1.0f, "Готово!")
        DeployManager.stopDeploy("success")
        true
    } catch (e: CancellationException) {
        DeployManager.stopDeploy("Отменено")
        throw e
    } catch (e: Exception) {
        deployFailed(e)
    }
}

internal suspend fun performUninstall(
    context: Context,
    host: String, user: String, pass: String, port: Int,
    peerPort: Int,
    onProgress: (Float, String) -> Unit,
    privateKey: String = "",
    keyPassphrase: String = "",
    certificate: String = "",
): Boolean = withContext(Dispatchers.IO) {
    try {
        val r = runBuhtaScript(
            context, host, user, pass, port, privateKey, keyPassphrase,
            "uninstall", "", "", "", onProgress, 0.3f, "Удаление сервера",
        )
        finishDeploy(r, "Сервер Бухты удалён")
    } catch (e: CancellationException) {
        DeployManager.stopDeploy("Удаление отменено")
        throw e
    } catch (e: Exception) {
        deployFailed(e)
    }
}

/** Применяет логин и пароль веб-панели на уже установленном сервере. */
internal suspend fun performResetPanelPass(
    context: Context,
    host: String, user: String, pass: String, port: Int,
    webLogin: String, webPass: String,
    onProgress: (Float, String) -> Unit,
    privateKey: String = "",
    keyPassphrase: String = "",
): Boolean = withContext(Dispatchers.IO) {
    try {
        val r = runBuhtaScript(
            context, host, user, pass, port, privateKey, keyPassphrase,
            "resetpass", "", webLogin, webPass, onProgress, 0.4f, "Смена логина и пароля панели",
        )
        finishDeploy(r, "Логин и пароль панели применены на сервере")
    } catch (e: CancellationException) {
        DeployManager.stopDeploy("Отменено")
        throw e
    } catch (e: Exception) {
        deployFailed(e)
    }
}

/** Читает журнал сервера по SSH (данные из вкладки «Деплой») — для диагностики подключения. */
internal suspend fun fetchServerJournal(context: Context): List<String> = withContext(Dispatchers.IO) {
    val d = SettingsStore(context).deploySettingsSnapshot.first()
    if (d.ip.isBlank()) return@withContext listOf("Сервер не указан во вкладке «Деплой»")
    if (!d.sshKeysMode && d.sshPassword.isBlank()) return@withContext listOf("Нет SSH-пароля во вкладке «Деплой» — журнал сервера прочитать нельзя")
    val port = d.sshPort.toIntOrNull() ?: 22
    var ssh: SshjClient? = null
    try {
        ssh = createSSHClient(d.ip, d.sshLogin.ifBlank { "root" }, if (d.sshKeysMode) "" else d.sshPassword, port,
            if (d.sshKeysMode) d.sshPrivateKey else "", if (d.sshKeysMode) d.sshKeyPassphrase else "")
        val client = DeploySSHClient(ssh, d.sshPassword) { _ -> throw IOException("reconnect") }
        val cmd = "for s in buhta buhta-link-0 buhta-link-1 buhta-keep-0 buhta-keep-1; do systemctl list-unit-files | grep -q \"^$s.service\" || continue; echo \"== $s: $(systemctl is-active $s) ==\"; journalctl -u $s --no-pager -n 6 -o cat 2>/dev/null | grep -v '^<' | grep -v -- '<-- ' | grep -v -- '--> ' | cut -c1-180; done; grep BUHTA_ROOM /etc/buhta/panel.env"
        val r = client.execResult(rootCommand(cmd), timeout = 30_000)
        r.output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    } catch (e: Exception) {
        listOf("SSH к серверу не удался: ${e.message}")
    } finally {
        runCatching { ssh?.disconnect() }
    }
}
