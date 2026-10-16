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
