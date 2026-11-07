package com.csqtt.client

import android.util.Base64
import org.json.JSONObject

/** Комната Saby Meet: UUID из гостевой ссылки либо ключ buhta:// из веб-панели сервера. */
object BuhtaRoom {
    const val UDP_PORT = 17777
    const val DNS = "1.1.1.1,8.8.8.8"

    data class Target(val room: String, val key: String)

    private val UUID_RE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** Разбирает все комнаты (по строке/запятой) для режима нескольких потоков. */
    fun parseAll(raw: String): List<Target> =
        raw.split(Regex("[,\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { parse(it) }

    fun parse(raw: String): Target? {
        val t = raw.trim().split(Regex("[,\\s]+")).firstOrNull { it.isNotEmpty() } ?: return null
        if (t.startsWith("buhta://")) {
            return try {
                val b = Base64.decode(
                    t.removePrefix("buhta://"),
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
                )
                val room = JSONObject(String(b, Charsets.UTF_8)).getString("room")
                if (UUID_RE.matches(room)) Target(room, t) else null
            } catch (_: Exception) {
                null
            }
        }
        val m = UUID_RE.find(t) ?: return null
        return Target(m.value, "")
    }
}
