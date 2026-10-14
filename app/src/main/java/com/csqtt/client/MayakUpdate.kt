// Mayak: a simplified noncommercial build based on CSQTT (Copyright 2026 amurcanov).
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mayak's own updater: it looks at the releases of the owner's fork, not the upstream CSQTT repo.
 *
 * Every CI build publishes a pre-release tagged "build-N" (N = the build number) that carries the
 * APK as a release asset. The installed app knows its own N from versionCode (CI sets it to
 * 1000 + N), so "is there a newer build" is a plain integer comparison and does not depend on the
 * version-name text. The download goes over the device's default network, which is the Mayak
 * tunnel while it is connected, so the update reaches GitHub even on a whitelisted mobile network.
 */
private const val LOG_TAG = "Mayak"
private const val APK_MIME = "application/vnd.android.package-archive"
private val MAYAK_TAG = Regex("build-(\\d+)")
private val MAYAK_CLIENT_APK = Regex("Mayak-\\d+-[A-Za-z0-9_]+\\.apk")
private val MAYAK_ADMIN_APK = Regex("MayakAdmin-\\d+-[A-Za-z0-9_]+\\.apk")

/** The build number baked into this APK. CI sets versionCode to 1000 + build number. */
fun localBuildNumber(): Int = (BuildConfig.VERSION_CODE - 1000).coerceAtLeast(0)

/** The build number inside a "build-N" tag or a "1.0.N" version name, or null if there is none. */
fun buildNumberOf(version: String): Int? =
    Regex("(\\d+)\\s*$").find(version.trim())?.groupValues?.getOrNull(1)?.toIntOrNull()

/**
 * Latest Mayak release of the fork, with the APK download link for this edition.
 * Returns null when the check could not be made (no network, GitHub unreachable, parse error).
 */
suspend fun fetchMayakRelease(): AppReleaseInfo? = withContext(Dispatchers.IO) {
    // GitHub first; when it cannot be reached (mobile whitelists), the owner's Yandex Disk.
    val body = httpText(CsqttConstants.Update.MAYAK_RELEASES_URL)
        ?: return@withContext fetchYandexRelease()
    val releases = try {
        JSONArray(body)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "[WARN] Update: cannot parse releases list", e)
        return@withContext null
    }

    var best: AppReleaseInfo? = null
    var bestNumber = -1
    val local = localBuildNumber()
    // "Что нового" of every build the person has not installed yet, keyed by build number.
    val notesByBuild = java.util.TreeMap<Int, List<String>>(Comparator.reverseOrder())
    for (i in 0 until releases.length()) {
        val release = releases.optJSONObject(i) ?: continue
        if (release.optBoolean("draft")) continue
        val tag = release.optString("tag_name").trim()
        // Only Mayak's own "build-N" releases: the same repo also publishes other apps
        // (for example "ya-build-N"), and their APKs must never be offered as a Mayak update.
        val number = MAYAK_TAG.matchEntire(tag)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
        val allLines = whatsNewLines(release.optString("body"))
        // People's Mayak skips builds that only changed Mayak Admin and does not list admin news.
        val lines = if (MayakConfig.ADMIN) allLines else allLines.filterNot(::isAdminOnlyNote)
        if (!MayakConfig.ADMIN && allLines.isNotEmpty() && lines.isEmpty()) continue
        if (number > local && lines.isNotEmpty()) notesByBuild[number] = lines
        if (number <= bestNumber) continue

        val assets = release.optJSONArray("assets") ?: JSONArray()
        var apkUrl: String? = null
        var fallbackApk: String? = null
        for (j in 0 until assets.length()) {
            val asset = assets.optJSONObject(j) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url").takeIf { it.isNotBlank() } ?: continue
            // CI names them Mayak-N-universal.apk, Mayak-N-arm64.apk and MayakAdmin-N-arm64.apk.
            val ownApk = if (MayakConfig.ADMIN) MAYAK_ADMIN_APK else MAYAK_CLIENT_APK
            if (!ownApk.matches(name)) continue
            if (name.contains("universal", ignoreCase = true) || MayakConfig.ADMIN) apkUrl = url
            if (fallbackApk == null) fallbackApk = url
        }
        best = AppReleaseInfo(
            versionTag = tag,
            releaseUrl = release.optString("html_url").trim(),
            source = RemoteVersionSource.Release,
            downloadUrl = apkUrl ?: fallbackApk
        )
        bestNumber = number
    }
    best?.copy(notes = notesByBuild.values.flatten().distinct().take(MAX_NOTES))
}

private const val MAX_NOTES = 8

/**
 * The newest build from the owner's public Yandex Disk folder: CI writes "client.json" and
 * "admin.json" there ({"build": N, "apk": "<file>", "notes": [...]}) next to the APK files.
 * Returns null when Yandex cannot be reached or has nothing.
 */
private fun fetchYandexRelease(): AppReleaseInfo? {
    val text = MayakYandex.fetchText(if (MayakConfig.ADMIN) "admin.json" else "client.json") ?: return null
    return runCatching {
        val obj = org.json.JSONObject(text)
        val number = obj.optInt("build", 0).takeIf { it > 0 } ?: return null
        val apk = obj.optString("apk", "").takeIf { it.isNotBlank() } ?: return null
        val array = obj.optJSONArray("notes") ?: JSONArray()
        val allLines = (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
        val lines = if (MayakConfig.ADMIN) allLines else allLines.filterNot(::isAdminOnlyNote)
        AppReleaseInfo(
            versionTag = "build-$number",
            releaseUrl = "",
            source = RemoteVersionSource.Release,
            downloadUrl = MayakYandex.downloadApiUrl(apk),
            notes = lines.take(MAX_NOTES),
        )
    }.getOrNull()
}

/** A "Что нового" line about Mayak Admin only, which people's Mayak does not need. */
internal fun isAdminOnlyNote(line: String): Boolean {
    val text = line.trim().lowercase()
    return text.startsWith("в маяке админ") || text.startsWith("в админке") || text.startsWith("маяк админ")
}

/**
 * The "- " lines under the "Что нового:" heading that CI writes into the release notes
 * (taken from "Что нового: ..." lines of the commit messages). Stops at a blank line or "---".
 */
internal fun whatsNewLines(body: String): List<String> {
    val lines = body.replace("\r", "").lines()
    val start = lines.indexOfFirst { it.trim().equals("Что нового:", ignoreCase = true) }
    if (start < 0) return emptyList()
    val result = mutableListOf<String>()
    for (line in lines.drop(start + 1)) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("---")) break
        val text = trimmed.removePrefix("-").removePrefix("•").trim()
        if (text.isNotEmpty()) result += text.take(160)
    }
    return result
}

/** True when the fork has a build newer than the one running now. */
fun isMayakUpdate(release: AppReleaseInfo): Boolean {
    val remote = buildNumberOf(release.versionTag) ?: return false
    return remote > localBuildNumber()
}

/**
 * Downloads the release APK and hands it to the system installer; the person only confirms the
 * install in the Android dialog. Never opens a web page. Returns null when the installer was
 * opened, otherwise a short message saying what to do before pressing "Обновить" again.
 */
suspend fun downloadAndInstall(
    context: Context,
    release: AppReleaseInfo,
    onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
): String? {
    val url = release.downloadUrl
        ?: return "Новая версия ещё собирается. Попробуй чуть позже."

    // Ask for the "install unknown apps" permission first, so nothing is downloaded for nothing.
    if (!context.packageManager.canRequestPackageInstalls()) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(LOG_TAG, "[WARN] Update: cannot open install-permission settings", e)
        }
        return "Включи «Разрешить установку» для Маяка, вернись и нажми «Обновить» ещё раз."
    }

    val apk = withContext(Dispatchers.IO) {
        try {
            // A file on the owner's Yandex Disk is reached through a short-lived direct link.
            val directUrl = if (MayakYandex.isDownloadApiUrl(url)) {
                MayakYandex.resolveDirectLink(url) ?: return@withContext null
            } else {
                url
            }
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "mayak-${release.versionTag}.apk")
            var current = URL(directUrl)
            var connection: HttpURLConnection
            var hops = 0
            while (true) {
                connection = (current.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "Mayak/${BuildConfig.VERSION_NAME}")
                    setRequestProperty("Accept", "application/octet-stream")
                }
                val code = connection.responseCode
                if (code in 300..399 && hops < 8) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) return@withContext null
                    current = URL(current, location)
                    hops++
                    continue
                }
                if (code !in 200..299) {
                    Log.w(LOG_TAG, "[WARN] Update: download returned $code")
                    connection.disconnect()
                    return@withContext null
                }
                break
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var reported = -1L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        // Report about every 256 KB so the bar moves smoothly without flooding.
                        if (done - reported >= 256 * 1024) {
                            reported = done
                            onProgress(done, total)
                        }
                    }
                    onProgress(done, total)
                }
            }
            connection.disconnect()
            if (file.length() < 1_000_000L) {
                Log.w(LOG_TAG, "[WARN] Update: downloaded file is too small (${file.length()} bytes)")
                null
            } else {
                file
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "[WARN] Update: download failed", e)
            null
        }
    } ?: return "Не получилось скачать обновление. Проверь интернет (или включи Маяк) и нажми «Обновить» ещё раз."

    return if (launchInstaller(context, apk)) null else "Не получилось открыть установку. Нажми «Обновить» ещё раз."
}

private fun launchInstaller(context: Context, apk: File): Boolean = try {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, APK_MIME)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    true
} catch (e: Exception) {
    Log.w(LOG_TAG, "[WARN] Update: cannot launch installer", e)
    false
}

private fun httpText(url: String): String? {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Mayak/${BuildConfig.VERSION_NAME}")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        val code = connection.responseCode
        if (code in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            Log.w(LOG_TAG, "[WARN] Update: releases request returned $code")
            null
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "[WARN] Update: releases request failed", e)
        null
    } finally {
        connection?.disconnect()
    }
}
