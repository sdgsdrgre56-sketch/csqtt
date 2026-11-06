package ru.buhta.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Настройки приложения: серверы (комнаты), DNS, тема. */
object Settings {
    const val DEFAULT_ROOM = "01a1a7f6-2bd2-7d7f-b921-15125aebf1c1"
    val UUID_RE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    class Server(val name: String, val room: String, val key: String = "")

    class Dns(val key: String, val title: String, val list: List<String>)

    val dnsPresets = listOf(
        Dns("cloudflare", "Cloudflare (1.1.1.1)", listOf("1.1.1.1", "1.0.0.1")),
        Dns("google", "Google (8.8.8.8)", listOf("8.8.8.8", "8.8.4.4")),
        Dns("yandex", "Яндекс (77.88.8.8)", listOf("77.88.8.8", "77.88.8.1")),
        Dns("quad9", "Quad9 (9.9.9.9)", listOf("9.9.9.9", "149.112.112.112")),
        Dns("adguard", "AdGuard (94.140.14.14)", listOf("94.140.14.14", "94.140.15.15")),
        Dns("custom", "Свой DNS", emptyList()),
    )

    private lateinit var sp: SharedPreferences

    @Synchronized
    fun init(c: Context) {
        if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("buhta", Context.MODE_PRIVATE)
    }

    /** Разбирает buhta://… ключ из веб-панели: возвращает (имя, UUID комнаты) или null. */
    fun parseKey(raw: String): Pair<String, String>? {
        val t = raw.trim()
        if (!t.startsWith("buhta://")) return null
        return try {
            val b = android.util.Base64.decode(t.removePrefix("buhta://"),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
            val o = JSONObject(String(b, Charsets.UTF_8))
            val room = o.getString("room")
            if (!UUID_RE.matches(room)) null else Pair(o.optString("n", ""), room)
        } catch (e: Exception) {
            null
        }
    }

    // ---- профили конфигурации (как в Маяке: 4 профиля, у каждого свой сервер) ----
    const val PROFILES = 4

    var profile: Int
        get() = sp.getInt("profile", 0).coerceIn(0, PROFILES - 1)
        set(v) { sp.edit().putInt("profile", v.coerceIn(0, PROFILES - 1)).apply() }

    private fun pk(base: String, p: Int = profile) = if (p == 0) base else "${base}_$p"

    private fun migrate() {
        val raw = sp.getString("servers", null) ?: return
        try {
            val a = JSONArray(raw)
            val e = sp.edit()
            for (i in 0 until minOf(a.length(), PROFILES)) {
                val o = a.getJSONObject(i)
                e.putString(pk("s_name", i), o.optString("name"))
                e.putString(pk("s_room", i), o.optString("room"))
                e.putString(pk("s_key", i), o.optString("key"))
            }
            e.remove("servers").apply()
        } catch (_: Exception) {
            sp.edit().remove("servers").apply()
        }
    }

    /** Сервер активного профиля. */
    fun currentServer(): Server {
        migrate()
        val room = sp.getString(pk("s_room"), "") ?: ""
        return Server(
            sp.getString(pk("s_name"), "")?.ifEmpty { null } ?: "Профиль $profile",
            room.ifEmpty { if (profile == 0) DEFAULT_ROOM else "" },
            sp.getString(pk("s_key"), "") ?: "",
        )
    }

    fun saveServer(name: String, room: String, key: String) {
        sp.edit().putString(pk("s_name"), name).putString(pk("s_room"), room).putString(pk("s_key"), key).apply()
    }

    var dnsKey: String
        get() = sp.getString("dns", "cloudflare") ?: "cloudflare"
        set(v) { sp.edit().putString("dns", v).apply() }

    var customDns: String
        get() = sp.getString("dns_custom", "") ?: ""
        set(v) { sp.edit().putString("dns_custom", v).apply() }

    fun dnsList(): List<String> {
        val p = dnsPresets.firstOrNull { it.key == dnsKey } ?: dnsPresets[0]
        if (p.key == "custom") {
            val l = customDns.split(Regex("[,\\s;]+")).filter { Regex("\\d{1,3}(\\.\\d{1,3}){3}").matches(it) }
            if (l.isNotEmpty()) return l.take(3)
            return dnsPresets[0].list
        }
        return p.list
    }

    var palette: String
        get() = sp.getString("palette", "brand") ?: "brand"
        set(v) { sp.edit().putString("palette", v).apply() }

    /** system | light | dark */
    var mode: String
        get() = sp.getString("mode", "system") ?: "system"
        set(v) { sp.edit().putString("mode", v).apply() }

    // ---- установка на VPS (пароль SSH не сохраняется) ----
    var deployHost: String
        get() = sp.getString(pk("d_host"), "") ?: ""
        set(v) { sp.edit().putString(pk("d_host"), v).apply() }
    var deployPort: String
        get() = sp.getString(pk("d_port"), "22") ?: "22"
        set(v) { sp.edit().putString(pk("d_port"), v).apply() }
    var deployUser: String
        get() = sp.getString(pk("d_user"), "root") ?: "root"
        set(v) { sp.edit().putString(pk("d_user"), v).apply() }
    var deployRoom: String
        get() = sp.getString(pk("d_room"), "") ?: ""
        set(v) { sp.edit().putString(pk("d_room"), v).apply() }

    // ---- исключения: приложения мимо VPN / только через VPN ----
    var excluded: Set<String>
        get() = sp.getStringSet("excluded", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("excluded", v).apply() }
    var whitelist: Boolean
        get() = sp.getBoolean("whitelist", false)
        set(v) { sp.edit().putBoolean("whitelist", v).apply() }

    // ---- логин и пароль веб-панели (придумываются здесь, до установки, как в Маяке) ----
    var panelUser: String
        get() = sp.getString(pk("p_user"), "admin") ?: "admin"
        set(v) { sp.edit().putString(pk("p_user"), v).apply() }
    var panelPass: String
        get() {
            val k = pk("p_pass")
            var v = sp.getString(k, null)
            if (v.isNullOrEmpty()) {
                val abc = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
                val r = java.security.SecureRandom()
                v = (1..12).map { abc[r.nextInt(abc.length)] }.joinToString("")
                sp.edit().putString(k, v).apply()
            }
            return v
        }
        set(v) { sp.edit().putString(pk("p_pass"), v).apply() }
}
