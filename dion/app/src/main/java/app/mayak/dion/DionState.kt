package app.mayak.dion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

const val MAX_CHANNELS = 8
const val DEFAULT_CHANNELS = 5

enum class Phase(val label: String) {
    OFF("Выключено"),
    STARTING("Запуск…"),
    CONNECTING("Подключаюсь к звонкам…"),
    CONNECTED("Подключено"),
    FAILED("Не удалось подключиться"),
}

enum class ChannelState(val label: String) {
    OFF("выкл"),
    CONNECTING("подключается"),
    UP("работает"),
    RECONNECTING("переподключается"),
    FAILED("ошибка"),
}

/** One channel as the screen shows it. Speeds are in bytes per second. */
data class ChannelInfo(
    val n: Int,
    val state: ChannelState = ChannelState.OFF,
    val active: Int = 0,
    val down: Long = 0,
    val up: Long = 0,
    val note: String = "",
)

object DionState {
    val phase = MutableStateFlow(Phase.OFF)
    val detail = MutableStateFlow("")
    val channels = MutableStateFlow<List<ChannelInfo>>(emptyList())
    val logs = MutableStateFlow<List<String>>(emptyList())

    private const val MAX_LOGS = 600
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(line: String) {
        val clean = line.trim()
        if (clean.isEmpty()) return
        val stamped = "${timeFormat.format(Date())}  $clean"
        synchronized(this) { logs.value = (logs.value + stamped).takeLast(MAX_LOGS) }
    }

    fun clearLogs() = synchronized(this) { logs.value = emptyList() }

    fun updateChannel(n: Int, change: (ChannelInfo) -> ChannelInfo) = synchronized(this) {
        channels.value = channels.value.map { if (it.n == n) change(it) else it }
    }

    val isActive: Boolean
        get() = phase.value != Phase.OFF && phase.value != Phase.FAILED
}

/** Settings of this app only. The DION password is encrypted with a key from the Android keystore. */
object DionPrefs {
    private const val FILE = "dion_mayak"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun count(context: Context): Int = prefs(context).getInt("count", DEFAULT_CHANNELS).coerceIn(1, MAX_CHANNELS)
    fun setCount(context: Context, value: Int) = prefs(context).edit().putInt("count", value.coerceIn(1, MAX_CHANNELS)).apply()

    /** Links of channels 1..count, "" where none. */
    fun links(context: Context): List<String> =
        (1..MAX_CHANNELS).map { prefs(context).getString("link$it", "") ?: "" }

    fun setLinks(context: Context, values: List<String>) {
        val e = prefs(context).edit()
        (1..MAX_CHANNELS).forEach { n -> e.putString("link$n", values.getOrNull(n - 1)?.trim().orEmpty()) }
        e.apply()
    }

    fun host(context: Context): String = prefs(context).getString("ssh_host", "") ?: ""
    fun port(context: Context): Int = prefs(context).getInt("ssh_port", 22)
    fun user(context: Context): String = prefs(context).getString("ssh_user", "root") ?: "root"
    fun saveSsh(context: Context, host: String, port: Int, user: String) =
        prefs(context).edit().putString("ssh_host", host.trim()).putInt("ssh_port", port).putString("ssh_user", user.trim()).apply()

    /** SSH password, encrypted with the phone's keystore key like the DION password. */
    fun sshPassword(context: Context): String = Secret.decrypt(prefs(context).getString("ssh_password", null))
    fun setSshPassword(context: Context, value: String) {
        val e = prefs(context).edit()
        if (value.isEmpty()) e.remove("ssh_password") else e.putString("ssh_password", Secret.encrypt(value))
        e.apply()
    }

    fun email(context: Context, n: Int = 0): String = prefs(context).getString("email$n", "") ?: ""
    fun password(context: Context, n: Int = 0): String = Secret.decrypt(prefs(context).getString("password$n", null))

    /** n = 0 is the shared account, 1..8 an own account for that channel. Empty password keeps the saved one. */
    fun saveAccount(context: Context, n: Int, email: String, password: String) {
        val e = prefs(context).edit().putString("email$n", email.trim())
        if (email.isBlank()) e.remove("password$n")
        else if (password.isNotEmpty()) e.putString("password$n", Secret.encrypt(password))
        e.apply()
    }

    fun ownAccounts(context: Context): Boolean = prefs(context).getBoolean("own_accounts", false)
    fun setOwnAccounts(context: Context, value: Boolean) = prefs(context).edit().putBoolean("own_accounts", value).apply()
}

private object Secret {
    private const val ALIAS = "dion_mayak_key"
    private const val PREFIX = "v1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(value: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val data = c.doFinal(value.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    fun decrypt(value: String?): String {
        if (value.isNullOrBlank() || !value.startsWith(PREFIX)) return ""
        val parts = value.removePrefix(PREFIX).split(":", limit = 2)
        if (parts.size != 2) return ""
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }
}

private val SLUG_RE = Regex("[A-Za-z0-9_-]{4,128}")

/** DION room slug from dion://slug, https://dion.vc/event/<slug> or a bare slug; "" if none. */
fun parseSlug(input: String): String {
    val text = Regex("(?:dion://|https?://)\\S+").find(input)?.value?.trimEnd('.', ',', ')') ?: input.trim()
    if (text.isEmpty()) return ""
    if (text.startsWith("dion://")) {
        return text.removePrefix("dion://").trim('/').substringBefore('?').takeIf { SLUG_RE.matches(it) } ?: ""
    }
    if (text.startsWith("http://") || text.startsWith("https://")) {
        val parts = text.substringAfter("://").substringBefore('?').substringBefore('#').split('/').filter { it.isNotEmpty() }
        val i = parts.indexOf("event")
        val slug = if (i >= 0 && i + 1 < parts.size) parts[i + 1] else parts.drop(1).lastOrNull().orEmpty()
        return slug.takeIf { SLUG_RE.matches(it) } ?: ""
    }
    if (text.contains("://")) return ""
    return text.trim('/').takeIf { SLUG_RE.matches(it) } ?: ""
}

/** All DION links found in a pasted text, in order. */
fun parseAllLinks(input: String): List<String> =
    Regex("(?:dion://|https?://\\S*dion\\S*/event/)[A-Za-z0-9_-]{4,128}").findAll(input)
        .map { "dion://" + parseSlug(it.value) }
        .filter { it != "dion://" }
        .distinct()
        .toList()

fun formatSpeed(bytesPerSec: Long): String {
    val mbit = bytesPerSec * 8 / 1_000_000.0
    return when {
        mbit >= 10 -> String.format(Locale.US, "%.0f Мбит/с", mbit)
        mbit >= 0.1 -> String.format(Locale.US, "%.1f Мбит/с", mbit)
        bytesPerSec > 0 -> "${bytesPerSec * 8 / 1000} кбит/с"
        else -> "0"
    }
}
