package com.csqtt.client

import android.util.Base64
import org.json.JSONObject

/** Комната Saby Meet: UUID из гостевой ссылки либо ключ buhta:// из веб-панели сервера. */
object BuhtaRoom {
    const val UDP_PORT = 17777
    const val DNS = "1.1.1.1,8.8.8.8"

    /** Список DNS для выбора во вкладке «Туннель» (значение, название). */
    val DNS_OPTIONS = listOf(
        "1.1.1.1,8.8.8.8" to "Cloudflare + Google",
        "1.1.1.1,1.0.0.1" to "Cloudflare",
        "8.8.8.8,8.8.4.4" to "Google",
        "77.88.8.8,77.88.8.1" to "Яндекс (базовый)",
        "77.88.8.88,77.88.8.2" to "Яндекс (безопасный)",
        "77.88.8.7,77.88.8.3" to "Яндекс (семейный)",
        "94.140.14.14,94.140.15.14" to "AdGuard (без рекламы)",
        "94.140.14.15,94.140.15.16" to "AdGuard (семейный)",
        "9.9.9.9,149.112.112.112" to "Quad9",
        "208.67.222.222,208.67.220.220" to "OpenDNS",
        "208.67.222.123,208.67.220.123" to "OpenDNS (семейный)",
        "45.90.28.181,45.90.30.181" to "NextDNS",
        "185.228.168.168,185.228.169.168" to "CleanBrowsing (семейный)",
        "84.200.69.80,84.200.70.40" to "DNS.Watch",
        "64.6.64.6,64.6.65.6" to "Verisign",
        "195.208.4.1,195.208.5.1" to "НСДИ",
        "95.189.32.74,195.208.5.1" to "Ростелеком",
        "111.88.96.50,111.88.96.51" to "Xbox DNS",
        "95.182.120.241,37.230.192.51" to "Geohide",
        "83.220.169.155,212.109.195.93" to "Comms",
        "none" to "Без DNS (системный)",
    )

    private const val PREFS = "buhta_prefs"
    private const val KEY_DNS = "dns"

    fun dns(ctx: android.content.Context?): String {
        val v = ctx?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)?.getString(KEY_DNS, null)
        return if (v.isNullOrBlank()) DNS else v
    }

    private const val KEY_VIDEO = "video_lane"

    /** Видеополоса (эксперимент): часть трафика через видеодорожку звонка. */
    fun videoLane(ctx: android.content.Context?): Boolean =
        ctx?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)?.getBoolean(KEY_VIDEO, false) ?: false

    fun setVideoLane(ctx: android.content.Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY_VIDEO, on).apply()
    }

    private const val KEY_RELAY = "relay_only"

    /** Прижать медиа к TCP-релею Saby (стабильно на Yota, без скачков на прямой UDP).
     *  По умолчанию включено: на мобильном флапинг важнее лишней скорости прямого UDP. */
    fun relayOnly(ctx: android.content.Context?): Boolean =
        ctx?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)?.getBoolean(KEY_RELAY, true) ?: true

    fun setRelayOnly(ctx: android.content.Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY_RELAY, on).apply()
    }

    fun setDns(ctx: android.content.Context, v: String) {
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString(KEY_DNS, v).apply()
    }

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
