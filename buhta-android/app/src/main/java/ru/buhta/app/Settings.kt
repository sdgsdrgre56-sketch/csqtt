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

    fun servers(): MutableList<Server> {
        val out = mutableListOf<Server>()
        val raw = sp.getString("servers", null)
        if (raw != null) {
            try {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    out.add(Server(o.getString("name"), o.getString("room"), o.optString("key", "")))
                }
            } catch (e: Exception) {
            }
        }
        if (out.isEmpty()) {
            val old = sp.getString("room", DEFAULT_ROOM) ?: DEFAULT_ROOM
            out.add(Server("Мой сервер", old))
            saveServers(out)
        }
        return out
    }

    fun saveServers(l: List<Server>) {
        val a = JSONArray()
        for (s in l) a.put(JSONObject().put("name", s.name).put("room", s.room).put("key", s.key))
        sp.edit().putString("servers", a.toString()).apply()
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

    var selected: Int
        get() = sp.getInt("selected", 0)
        set(v) { sp.edit().putInt("selected", v).apply() }

    fun currentServer(): Server {
        val l = servers()
        return l[selected.coerceIn(0, l.size - 1)]
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
        get() = sp.getString("d_host", "") ?: ""
        set(v) { sp.edit().putString("d_host", v).apply() }
    var deployPort: String
        get() = sp.getString("d_port", "22") ?: "22"
        set(v) { sp.edit().putString("d_port", v).apply() }
    var deployUser: String
        get() = sp.getString("d_user", "root") ?: "root"
        set(v) { sp.edit().putString("d_user", v).apply() }
    var deployRoom: String
        get() = sp.getString("d_room", "") ?: ""
        set(v) { sp.edit().putString("d_room", v).apply() }

    // ---- исключения: приложения мимо VPN / только через VPN ----
    var excluded: Set<String>
        get() = sp.getStringSet("excluded", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("excluded", v).apply() }
    var whitelist: Boolean
        get() = sp.getBoolean("whitelist", false)
        set(v) { sp.edit().putBoolean("whitelist", v).apply() }
}
