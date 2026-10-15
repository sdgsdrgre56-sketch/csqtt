// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: the owner's public Yandex Disk folder as a second place for updates, server lists and
// announcements. Yandex is reachable on mobile networks with whitelists, GitHub often is not.

package com.csqtt.client

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Reading needs no account: the folder is public ("Поделиться" → link), and files inside it are
 * fetched through Yandex's public API. Writing (CI and Mayak Admin) goes through the Disk REST API
 * with the owner's OAuth token (cloud_api:disk.write).
 */
internal object MayakYandex {
    /** The owner's public folder "mayak". */
    const val PUBLIC_FOLDER = "https://disk.yandex.ru/d/i7uxPmhYZdXpVQ"
    private const val API = "https://cloud-api.yandex.net/v1/disk/public/resources/download"
    private const val RESOURCES = "https://cloud-api.yandex.net/v1/disk/resources"

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** API address that answers with a temporary direct link to [path] inside the public folder. */
    fun downloadApiUrl(path: String): String =
        "$API?public_key=${enc(PUBLIC_FOLDER)}&path=${enc(if (path.startsWith("/")) path else "/$path")}"

    fun isDownloadApiUrl(url: String): Boolean = url.startsWith(API)

    /** Turns a [downloadApiUrl] into the direct file link, or null. Blocking. */
    fun resolveDirectLink(apiUrl: String): String? {
        val text = get(apiUrl, 64 * 1024) ?: return null
        val href = runCatching { JSONObject(text).optString("href").takeIf { it.isNotBlank() } }.getOrNull()
        if (href == null) lastProblem = "Яндекс не дал ссылку: ${text.take(80)}"
        return href
    }

    /** A small text file from the public folder ([path] like "client.json"). Blocking. */
    fun fetchText(path: String, limit: Int = 64 * 1024): String? =
        fetchBytes(path, limit)?.toString(Charsets.UTF_8)

    /** A small file from the public folder as bytes (at most [limit]), or null. Blocking. */
    fun fetchBytes(path: String, limit: Int): ByteArray? {
        val href = resolveDirectLink(downloadApiUrl(path)) ?: return null
        return getBytes(href, limit)
    }

    private fun get(url: String, limit: Int): String? = getBytes(url, limit)?.toString(Charsets.UTF_8)

    /** What went wrong in the last read, for the "Проверить обновления" report. */
    @Volatile
    var lastProblem: String = ""
        private set

    private fun getBytes(url: String, limit: Int): ByteArray? {
        var current = URL(url)
        repeat(6) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Mayak/${BuildConfig.VERSION_NAME}")
            }
            val host = current.host
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    if (location == null) {
                        lastProblem = "$host: переадресация без адреса"
                        return null
                    }
                    current = URL(current, location)
                    return@repeat
                }
                if (code != 200) {
                    lastProblem = "$host: код $code"
                    return null
                }
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
                lastProblem = ""
                return bytes
            } catch (e: Exception) {
                lastProblem = "$host: ${e.javaClass.simpleName}${e.message?.let { ": " + it.take(60) } ?: ""}"
                return null
            } finally {
                connection.disconnect()
            }
        }
        lastProblem = "слишком много переадресаций"
        return null
    }

    // ---- writing (Mayak Admin) ----------------------------------------------------------------

    class WriteException(message: String) : Exception(message)

    /**
     * Writes [content] to "mayak/[path]" through the Disk REST API with the owner's OAuth token
     * (WebDAV is only for paid Yandex 360 plans now). Blocking.
     */
    fun putText(token: String, path: String, content: String) {
        val name = path.trim('/')
        val target = "$RESOURCES/upload?path=${enc("disk:/mayak/$name")}&overwrite=true"
        val (code, text) = request(token, "GET", target, null)
        if (code != 200) throw WriteException(writeError(code))
        val href = runCatching { JSONObject(text).optString("href") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: throw WriteException("Яндекс Диск не дал ссылку для записи")
        val (putCode, _) = request(null, "PUT", href, content.toByteArray(Charsets.UTF_8))
        if (putCode !in 200..299) throw WriteException(writeError(putCode))
    }

    private fun writeError(code: Int): String = when (code) {
        -1 -> "Нет связи с Яндекс Диском"
        401 -> "Яндекс не принял токен (возможно, он устарел, сделай новый)"
        403 -> "Токену не хватает прав на запись в Диск"
        404, 409 -> "На Яндекс Диске нет папки mayak"
        507 -> "На Яндекс Диске закончилось место"
        else -> "Яндекс Диск ответил кодом $code"
    }

    private fun request(token: String?, method: String, url: String, body: ByteArray?): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (token != null) connection.setRequestProperty("Authorization", "OAuth $token")
            connection.setRequestProperty("User-Agent", "MayakAdmin/${BuildConfig.VERSION_NAME}")
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val text = if (code in 200..299) {
                runCatching { connection.inputStream.bufferedReader().use { it.readText().take(16 * 1024) } }
                    .getOrDefault("")
            } else {
                ""
            }
            code to text
        } catch (e: java.io.IOException) {
            -1 to ""
        } finally {
            connection.disconnect()
        }
    }
}

/** Person list file name on Yandex Disk (flat, no sub-folders): "p-<id>.txt". */
internal fun yandexPersonFile(password: String): String = "p-${MayakSync.fileId(password)}.txt"
