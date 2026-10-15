package com.csqtt.client.wb

import android.content.Context
import com.csqtt.client.SecureStringStore
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WB Stream test transport (Mayak Admin only). Kept completely apart from Mayak's own tunnel:
 * own VPN service, own relay process, own settings file.
 */
enum class WbPhase(val label: String) {
    OFF("Выключено"),
    STARTING("Запуск…"),
    CONNECTING("Подключаюсь к звонку WB…"),
    CONNECTED("Подключено через WB Stream"),
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

    /** WB Stream login token, kept only on this phone, encrypted with the app's keystore key. */
    fun token(context: Context): String =
        runCatching {
            SecureStringStore(context.applicationContext).decrypt(prefs(context).getString("wb_token_enc", null))
        }.getOrNull().orEmpty()

    fun setToken(context: Context, value: String) {
        val clean = value.trim()
        val enc = if (clean.isEmpty()) null else SecureStringStore(context.applicationContext).encrypt(clean)
        prefs(context).edit().putString("wb_token_enc", enc).apply()
    }
}

/** Accepts wbstream://id, https://stream.wb.ru/room/id or a bare id; returns the room id or "". */
fun parseWbRoomId(input: String): String {
    val text = input.trim()
    if (text.isEmpty()) return ""
    if (text.startsWith("wbstream://")) return text.removePrefix("wbstream://").trim('/')
    if (text.startsWith("http://") || text.startsWith("https://")) {
        val parts = text.substringAfter("://").substringBefore('?').split('/')
        val i = parts.indexOf("room")
        return if (i >= 0 && i + 1 < parts.size) parts[i + 1] else ""
    }
    return text.trim('/').takeIf { Regex("[A-Za-z0-9_-]{4,128}").matches(it) } ?: ""
}
