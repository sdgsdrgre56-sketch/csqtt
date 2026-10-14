// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: the owner's public Yandex Disk folder as a second place for updates, server lists and
// announcements. Yandex is reachable on mobile networks with whitelists, GitHub often is not.

package com.csqtt.client

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Reading needs no account: the folder is public ("Поделиться" → link), and files inside it are
 * fetched through Yandex's public API. Writing (CI and Mayak Admin) goes through WebDAV with the
 * owner's Yandex login and an app password ("Пароль приложения", type "Файлы").
 */
internal object MayakYandex {
    /** The owner's public folder "mayak". */
    const val PUBLIC_FOLDER = "https://disk.yandex.ru/d/i7uxPmhYZdXpVQ"
    private const val API = "https://cloud-api.yandex.net/v1/disk/public/resources/download"
    private const val WEBDAV = "https://webdav.yandex.ru/mayak"

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** API address that answers with a temporary direct link to [path] inside the public folder. */
    fun downloadApiUrl(path: String): String =
        "$API?public_key=${enc(PUBLIC_FOLDER)}&path=${enc(if (path.startsWith("/")) path else "/$path")}"

    fun isDownloadApiUrl(url: String): Boolean = url.startsWith(API)

    /** Turns a [downloadApiUrl] into the direct file link, or null. Blocking. */
    fun resolveDirectLink(apiUrl: String): String? {
        val text = get(apiUrl, 64 * 1024) ?: return null
        return runCatching { JSONObject(text).optString("href").takeIf { it.isNotBlank() } }.getOrNull()
    }

    /** A small text file from the public folder ([path] like "client.json"). Blocking. */
    fun fetchText(path: String, limit: Int = 64 * 1024): String? {
        val href = resolveDirectLink(downloadApiUrl(path)) ?: return null
        return get(href, limit)
    }

    private fun get(url: String, limit: Int): String? {
        var current = URL(url)
        repeat(6) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Mayak/${BuildConfig.VERSION_NAME}")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return null
                    current = URL(current, location)
                    return@repeat
                }
                if (code != 200) return null
                val bytes = connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (out.size() < limit) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
                return String(bytes, Charsets.UTF_8)
            } catch (e: Exception) {
                return null
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    // ---- writing (Mayak Admin) ----------------------------------------------------------------

    class WriteException(message: String) : Exception(message)

    /** Writes [content] to "mayak/[path]" (WebDAV PUT). Blocking. */
    fun putText(login: String, password: String, path: String, content: String) {
        val code = request(login, password, "PUT", "$WEBDAV/${path.trim('/')}", content.toByteArray(Charsets.UTF_8))
        if (code !in 200..299) throw WriteException(writeError(code))
    }

    private fun writeError(code: Int): String = when (code) {
        -1 -> "Нет связи с Яндекс Диском"
        401 -> "Яндекс не принял логин или пароль приложения"
        403 -> "Яндекс запретил запись (проверь, что пароль приложения для «Файлов»)"
        404, 409 -> "На Яндекс Диске нет папки mayak"
        507 -> "На Яндекс Диске закончилось место"
        else -> "Яндекс Диск ответил кодом $code"
    }

    private fun request(login: String, password: String, method: String, url: String, body: ByteArray?): Int {
        val connection = (URL(url).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            val auth = Base64.encodeToString("$login:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            connection.setRequestProperty("Authorization", "Basic $auth")
            connection.setRequestProperty("User-Agent", "MayakAdmin/${BuildConfig.VERSION_NAME}")
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                connection.outputStream.use { it.write(body) }
            }
            connection.responseCode
        } catch (e: java.io.IOException) {
            -1
        } finally {
            connection.disconnect()
        }
    }
}

/** Person list file name on Yandex Disk (flat, no sub-folders): "p-<id>.txt". */
internal fun yandexPersonFile(password: String): String = "p-${MayakSync.fileId(password)}.txt"
