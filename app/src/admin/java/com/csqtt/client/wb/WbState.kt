package com.csqtt.client.wb

import android.content.Context
import com.csqtt.client.SecureStringStore
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Call-tunnel test (Mayak Admin only): DION or WB Stream through whitelist-bypass.
 * Kept completely apart from Mayak's own tunnel: own VPN service, own relay process, own settings file.
 */
enum class CallPlatform(
    val id: String,
    val title: String,
    val relayMode: String,
    val linkScheme: String,
    val assetPrefix: String,
) {
    DION("dion", "DION", "dion-headless-joiner", "dion://", "dion"),
    WB("wb", "WB Stream", "wbstream-headless-joiner", "wbstream://", "wbstream");

    companion object {
        fun byId(id: String?): CallPlatform = entries.firstOrNull { it.id == id } ?: DION

        /** Guesses the service from a pasted link; null when the text doesn't say. */
        fun detect(text: String): CallPlatform? {
            val t = text.lowercase(Locale.ROOT)
            return when {
                "dion" in t -> DION
                "wbstream://" in t || "stream.wb.ru" in t -> WB
                else -> null
            }
        }
    }
}

enum class WbPhase(val label: String) {
    OFF("Выключено"),
    STARTING("Запуск…"),
    CONNECTING("Подключаюсь к звонку…"),
    CONNECTED("Подключено через звонок"),
    RECONNECTING("Связь пропала, переподключаюсь…"),
    FAILED("Не удалось подключиться"),
}

object WbState {
    val phase = MutableStateFlow(WbPhase.OFF)
    val detail = MutableStateFlow("")
    val logs = MutableStateFlow<List<String>>(emptyList())

    private const val MAX_LOGS = 400
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(line: String) {
        val clean = line.trim()
        if (clean.isEmpty()) return
        val stamped = "${timeFormat.format(Date())}  $clean"
        synchronized(this) {
            logs.value = (logs.value + stamped).takeLast(MAX_LOGS)
        }
    }

    fun clearLogs() {
        synchronized(this) { logs.value = emptyList() }
    }

    val isActive: Boolean
        get() = phase.value != WbPhase.OFF && phase.value != WbPhase.FAILED
}

/** Small settings file of its own, so nothing here can touch Mayak's settings. */
object WbPrefs {
    private const val FILE = "wbstream_test"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun platform(context: Context): CallPlatform = CallPlatform.byId(prefs(context).getString("platform", null))
    fun setPlatform(context: Context, value: CallPlatform) = prefs(context).edit().putString("platform", value.id).apply()

    fun link(context: Context): String = prefs(context).getString("link", "") ?: ""
    fun setLink(context: Context, value: String) = prefs(context).edit().putString("link", value.trim()).apply()

    fun host(context: Context): String = prefs(context).getString("ssh_host", "") ?: ""
    fun port(context: Context): Int = prefs(context).getInt("ssh_port", 22)
    fun user(context: Context): String = prefs(context).getString("ssh_user", "root") ?: "root"

    fun saveSsh(context: Context, host: String, port: Int, user: String) =
        prefs(context).edit()
            .putString("ssh_host", host.trim())
            .putInt("ssh_port", port)
            .putString("ssh_user", user.trim())
            .apply()

    /** WB Stream login token (staff accounts only), kept on this phone, encrypted with the app's key. */
    fun token(context: Context): String = secret(context, "wb_token_enc")
    fun setToken(context: Context, value: String) = setSecret(context, "wb_token_enc", value)

    /** DION account for the server node: e-mail in plain prefs, password encrypted. */
    fun dionEmail(context: Context): String = prefs(context).getString("dion_email", "") ?: ""
    fun dionPassword(context: Context): String = secret(context, "dion_password_enc")
    fun saveDion(context: Context, email: String, password: String) {
        prefs(context).edit().putString("dion_email", email.trim()).apply()
        if (password.isNotEmpty()) setSecret(context, "dion_password_enc", password)
    }

    private fun secret(context: Context, key: String): String =
        runCatching {
            SecureStringStore(context.applicationContext).decrypt(prefs(context).getString(key, null))
        }.getOrNull().orEmpty()

    private fun setSecret(context: Context, key: String, value: String) {
        val clean = value.trim()
        val enc = if (clean.isEmpty()) null else SecureStringStore(context.applicationContext).encrypt(clean)
        prefs(context).edit().putString(key, enc).apply()
    }
}

private val UUID_RE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val ID_RE = Regex("[A-Za-z0-9_-]{4,128}")

/** Picks the link itself out of a shared text (invites often carry words around it). */
fun extractLink(input: String): String =
    Regex("(?:dion://|wbstream://|https?://)\\S+").find(input)?.value?.trimEnd('.', ',', ')') ?: input.trim()

/**
 * Returns the room id / event slug for the given service, or "".
 * DION: dion://slug, https://dion.vc/event/<slug>. WB: wbstream://id, stream.wb.ru links, ?roomId=.
 */
fun parseRoomId(platform: CallPlatform, input: String): String {
    val text = extractLink(input)
    if (text.isEmpty()) return ""
    if (text.startsWith(platform.linkScheme)) {
        return text.removePrefix(platform.linkScheme).trim('/').substringBefore('?').takeIf { ID_RE.matches(it) } ?: ""
    }
    if (text.startsWith("http://") || text.startsWith("https://")) {
        val parts = text.substringAfter("://").substringBefore('?').substringBefore('#')
            .split('/').filter { it.isNotEmpty() }
        if (platform == CallPlatform.DION) {
            val i = parts.indexOf("event")
            val slug = if (i >= 0 && i + 1 < parts.size) parts[i + 1] else parts.drop(1).lastOrNull().orEmpty()
            return slug.takeIf { ID_RE.matches(it) } ?: ""
        }
        UUID_RE.findAll(text).lastOrNull()?.let { return it.value }
        Regex("[?&](?:roomId|room|id)=([A-Za-z0-9_-]{4,128})").find(text)?.let { return it.groupValues[1] }
        val i = parts.indexOfFirst { it == "room" || it == "join" || it == "r" }
        if (i >= 0 && i + 1 < parts.size) return parts[i + 1]
        return parts.drop(1).lastOrNull()?.takeIf { ID_RE.matches(it) } ?: ""
    }
    if (text.contains("://")) return ""
    return text.trim('/').takeIf { ID_RE.matches(it) } ?: ""
}
