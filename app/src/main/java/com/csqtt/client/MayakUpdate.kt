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
    val body = httpText(CsqttConstants.Update.MAYAK_RELEASES_URL) ?: return@withContext null
    val releases = try {
        JSONArray(body)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "[WARN] Update: cannot parse releases list", e)
        return@withContext null
    }

    var best: AppReleaseInfo? = null
    var bestNumber = -1
    for (i in 0 until releases.length()) {
        val release = releases.optJSONObject(i) ?: continue
        if (release.optBoolean("draft")) continue
        val tag = release.optString("tag_name").trim()
        // Only Mayak's own "build-N" releases: the same repo also publishes other apps
        // (for example "ya-build-N"), and their APKs must never be offered as a Mayak update.
        val number = MAYAK_TAG.matchEntire(tag)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
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
    best
}

/** True when the fork has a build newer than the one running now. */
fun isMayakUpdate(release: AppReleaseInfo): Boolean {
    val remote = buildNumberOf(release.versionTag) ?: return false
    return remote > localBuildNumber()
}

/**
 * Downloads the release APK and hands it to the system installer. The person still confirms the
 * install in the Android dialog; this only removes the manual "open the page, find the file,
 * tap it" steps. Returns false (so the caller can fall back to opening the release page) when the
 * APK link is missing, the download fails, or the installer cannot be launched.
 */
suspend fun downloadAndInstall(context: Context, release: AppReleaseInfo): Boolean {
    val url = release.downloadUrl ?: return false
    val apk = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "mayak-${release.versionTag}.apk")
            var current = URL(url)
            var connection: HttpURLConnection
            var hops = 0
            while (true) {
                connection = (current.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "Mayak/${BuildConfig.VERSION_NAME}")
                    setRequestProperty("Accept", "application/octet-stream")
                }
                val code = connection.responseCode
                if (code in 300..399 && hops < 5) {
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
            connection.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
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
    } ?: return false

    return launchInstaller(context, apk)
}

private fun launchInstaller(context: Context, apk: File): Boolean {
    // Android O+ needs the app to be allowed to install unknown apps. If it is not, send the
    // person to that one toggle first; they can tap "Обновить" again afterwards.
    if (!context.packageManager.canRequestPackageInstalls()) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(LOG_TAG, "[WARN] Update: cannot open install-permission settings", e)
        }
        return false
    }
    return try {
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
