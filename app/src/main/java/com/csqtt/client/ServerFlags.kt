// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: country flag of a server, found once by its IP and remembered.

package com.csqtt.client

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

object ServerFlags {
    private const val PREFS = "mayak_server_flags"

    /** Two-letter country code ("DE") as a flag emoji ("🇩🇪"); empty for anything else. */
    fun flagOf(code: String): String {
        val upper = code.trim().uppercase()
        if (upper.length != 2 || !upper.all { it in 'A'..'Z' }) return ""
        return upper.map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")
    }

    /** Remembered country code for [host], without going to the network. */
    fun cached(context: Context, host: String): String? =
        host.trim().takeIf { it.isNotEmpty() }?.let {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(it.lowercase(), null)
        }

    /**
     * Country code for [host] (IP or domain). Asks a public GeoIP service once and remembers the answer;
     * failures are not remembered, so the next screen open tries again.
     */
    suspend fun countryCode(context: Context, host: String): String? = withContext(Dispatchers.IO) {
        val key = host.trim().lowercase()
        if (key.isEmpty()) return@withContext null
        cached(context, key)?.let { return@withContext it }
        val ip = runCatching { InetAddress.getByName(key) }.getOrNull()
            ?.takeUnless { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }
            ?.hostAddress
            ?: return@withContext null
        val code = fromIpWho(ip) ?: fromIpApi(ip)
        if (code != null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, code).apply()
        }
        code
    }

    /** City, country and hosting company of a server, for the line under the server buttons. */
    data class Details(val city: String, val country: String, val hoster: String) {
        val text: String
            get() = listOf(listOf(city, country).filter { it.isNotBlank() }.joinToString(", "), hoster)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
    }

    private const val DETAILS_PREFS = "mayak_server_details"

    /** Remembered details for [host], without going to the network. */
    fun cachedDetails(context: Context, host: String): Details? {
        val key = host.trim().lowercase().takeIf { it.isNotEmpty() } ?: return null
        val raw = context.getSharedPreferences(DETAILS_PREFS, Context.MODE_PRIVATE).getString(key, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            Details(o.optString("city"), o.optString("country"), o.optString("hoster"))
        }.getOrNull()
    }

    /** Looks the details up once (public GeoIP service) and remembers them; failures are not remembered. */
    suspend fun details(context: Context, host: String): Details? = withContext(Dispatchers.IO) {
        val key = host.trim().lowercase()
        if (key.isEmpty()) return@withContext null
        cachedDetails(context, key)?.let { return@withContext it }
        val ip = runCatching { InetAddress.getByName(key) }.getOrNull()
            ?.takeUnless { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }
            ?.hostAddress
            ?: return@withContext null
        val found = runCatching {
            val o = JSONObject(get("https://ipwho.is/$ip?lang=ru&fields=success,country,city,connection"))
            if (!o.optBoolean("success")) null else {
                val c = o.optJSONObject("connection")
                Details(
                    city = o.optString("city"),
                    country = o.optString("country"),
                    hoster = shortHoster(c?.optString("org").orEmpty().ifBlank { c?.optString("isp").orEmpty() }),
                )
            }
        }.getOrNull() ?: runCatching {
            val o = JSONObject(get("https://ipapi.co/$ip/json/"))
            Details(o.optString("city"), o.optString("country_name"), shortHoster(o.optString("org")))
        }.getOrNull()
        if (found != null && (found.country.isNotBlank() || found.hoster.isNotBlank())) {
            context.getSharedPreferences(DETAILS_PREFS, Context.MODE_PRIVATE).edit().putString(
                key,
                JSONObject().put("city", found.city).put("country", found.country).put("hoster", found.hoster).toString(),
            ).apply()
            found
        } else {
            null
        }
    }

    /** "AEZA GROUP Ltd" -> "AEZA GROUP", "Hetzner Online GmbH" -> "Hetzner Online". */
    private fun shortHoster(org: String): String {
        val suffixes = setOf("ltd", "llc", "inc", "gmbh", "b.v.", "bv", "ab", "oy", "sa", "s.a.", "srl", "ou", "oü",
            "limited", "corp", "corporation", "co.", "plc", "sp.", "z", "o.o.", "s.r.o.", "as", "a/s")
        val words = org.replace(",", " ").split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        fun isSuffix(word: String): Boolean {
            val w = word.lowercase()
            return w in suffixes || w.trimEnd('.') in suffixes
        }
        while (words.size > 1 && isSuffix(words.last())) words.removeAt(words.lastIndex)
        return words.joinToString(" ").take(28)
    }

    private fun fromIpWho(ip: String): String? = runCatching {
        val obj = JSONObject(get("https://ipwho.is/$ip?fields=success,country_code"))
        obj.optString("country_code").takeIf { obj.optBoolean("success") && flagOf(it).isNotEmpty() }?.uppercase()
    }.getOrNull()

    private fun fromIpApi(ip: String): String? = runCatching {
        get("https://ipapi.co/$ip/country/").trim().takeIf { flagOf(it).isNotEmpty() }?.uppercase()
    }.getOrNull()

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.setRequestProperty("User-Agent", "Mayak")
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }.take(4096)
        } finally {
            connection.disconnect()
        }
    }
}
