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
suspend fun fetchMayakRelease(): AppReleaseInfo? = checkMayakUpdate().release

/** Result of an update check plus a short report of where it looked, for the "Инфо" button. */
data class MayakUpdateCheck(val release: AppReleaseInfo?, val report: String)

/**
 * GitHub first; when it fails in any way (no connection, a whitelist stub page, too slow), the
 * owner's Yandex Disk.
 */
suspend fun checkMayakUpdate(alwaysAskYandex: Boolean = false): MayakUpdateCheck = withContext(Dispatchers.IO) {
    val github = githubWithin(GITHUB_LIMIT_MS)
    if (github.first != null && !alwaysAskYandex) {
        return@withContext MayakUpdateCheck(github.first, "GitHub: ${github.first!!.versionTag}")
    }
    val yandex = runCatching { fetchYandexRelease() }.getOrNull()
    val report = buildString {
        append("GitHub: ").append(github.second)
        append(". Яндекс Диск: ")
        append(yandex?.versionTag ?: MayakYandex.lastProblem.ifBlank { "не разобрал ответ" })
    }
    Log.i(LOG_TAG, "Update check: $report")
    MayakUpdateCheck(github.first ?: yandex, report)
}

private const val GITHUB_LIMIT_MS = 10_000L

/** GitHub release or null with the reason; gives up waiting after [limitMs]. */
private suspend fun githubWithin(limitMs: Long): Pair<AppReleaseInfo?, String> {
    val result = kotlinx.coroutines.CompletableDeferred<Pair<AppReleaseInfo?, String>>()
    // A plain thread: a slow blocking read cannot hold the check past the limit.
    Thread {
        result.complete(runCatching { fetchGithubRelease() }.getOrElse { null to "ошибка" })
    }.apply { isDaemon = true }.start()
    return kotlinx.coroutines.withTimeoutOrNull(limitMs) { result.await() } ?: (null to "слишком долго")
}

private fun fetchGithubRelease(): Pair<AppReleaseInfo?, String> {
    val body = httpText(CsqttConstants.Update.MAYAK_RELEASES_URL) ?: return null to "нет связи"
    val releases = try {
        JSONArray(body)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "[WARN] Update: cannot parse releases list", e)
        return null to "вместо списка пришла чужая страница"
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
    val found = best?.copy(notes = notesByBuild.values.flatten().distinct().take(MAX_NOTES))
    return found to (if (found == null) "версий нет" else found.versionTag)
}

private const val MAX_NOTES = 8

/** Marker download address of a release that is fetched from Yandex Disk in pieces. */
private const val YANDEX_PARTS_URL = "yandex-parts:"

/** Largest piece accepted (CI cuts 768 KB pieces; whitelists drop Yandex files of 1 MB). */
private const val MAX_PART_BYTES = 1_000_000

/** Why the last download failed, shown in the update window. */
@Volatile
private var lastDownloadProblem: String = ""

/**
 * Downloads every piece of [release] from Yandex Disk, glues them into [file] and checks the size
 * and SHA-256. Pieces already downloaded by an earlier attempt are kept in "updates/parts", so
 * "Повторить" continues where it stopped. Returns false when anything is missing or wrong.
 */
private fun downloadYandexParts(
    release: AppReleaseInfo,
    dir: File,
    file: File,
    onProgress: (downloaded: Long, total: Long) -> Unit,
): Boolean {
    val total = release.yandexSize
    val partsDir = File(dir, "parts-${release.versionTag}").apply { mkdirs() }
    val count = release.yandexParts.size
    val done = java.util.concurrent.atomic.AtomicLong(0L)
    val failure = java.util.concurrent.atomic.AtomicReference<String?>(null)
    // Three pieces at a time: one slow request does not hold up the rest.
    val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
    try {
        val jobs = release.yandexParts.mapIndexed { index, part ->
            pool.submit(Runnable {
                if (failure.get() != null) return@Runnable
                val saved = File(partsDir, part)
                if (saved.isFile && saved.length() in 1..MAX_PART_BYTES.toLong()) {
                    onProgress(done.addAndGet(saved.length()), total)
                    return@Runnable
                }
                var bytes: ByteArray? = null
                var problem = ""
                for (attempt in 1..5) {
                    bytes = MayakYandex.fetchBytes(part, MAX_PART_BYTES + 1)
                    if (bytes != null && bytes.isNotEmpty() && bytes.size <= MAX_PART_BYTES) break
                    problem = if (bytes != null) "кусок больше 1 МБ (${bytes.size})" else MayakYandex.lastProblem
                    bytes = null
                    if (failure.get() != null) return@Runnable
                    Thread.sleep(1_500L * attempt)
                }
                val got = bytes
                if (got == null) {
                    failure.compareAndSet(null, "кусок ${index + 1} из $count: ${problem.ifBlank { "нет ответа" }}")
                    return@Runnable
                }
                val tmp = File(partsDir, "$part.tmp")
                tmp.writeBytes(got)
                saved.delete()
                if (!tmp.renameTo(saved)) {
                    failure.compareAndSet(null, "не получилось сохранить кусок ${index + 1}")
                    return@Runnable
                }
                onProgress(done.addAndGet(got.size.toLong()), total)
            })
        }
        jobs.forEach { runCatching { it.get() } }
    } finally {
        pool.shutdownNow()
    }
    failure.get()?.let {
        lastDownloadProblem = it
        Log.w(LOG_TAG, "[WARN] Update: $it")
        return false
    }
    // Glue and check.
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    var glued = 0L
    file.outputStream().use { output ->
        for (part in release.yandexParts) {
            val bytes = File(partsDir, part).readBytes()
            output.write(bytes)
            digest.update(bytes)
            glued += bytes.size
        }
    }
    val sha = digest.digest().joinToString("") { "%02x".format(it) }
    if (glued != total || sha != release.yandexSha256) {
        lastDownloadProblem = "файл собрался неправильно ($glued из $total байт), кусочки скачаются заново"
        Log.w(LOG_TAG, "[WARN] Update: $lastDownloadProblem")
        partsDir.deleteRecursively()
        return false
    }
    partsDir.deleteRecursively()
    return true
}

/**
 * The newest build from the owner's public Yandex Disk folder: CI writes "client.json" and
 * "admin.json" there ({"build": N, "apk": "<file>", "notes": [...]}) next to the APK files.
 * Returns null when Yandex cannot be reached or has nothing.
 */
private fun fetchYandexRelease(): AppReleaseInfo? {
    val text = MayakYandex.fetchText(if (MayakConfig.ADMIN) "admin.json" else "client.json") ?: return null
    if (!text.trimStart().startsWith("{")) {
        Log.w(LOG_TAG, "[WARN] Update: Yandex answered not JSON: ${text.take(80)}")
    }
    return runCatching {
        val obj = org.json.JSONObject(text)
        val number = obj.optInt("build", 0).takeIf { it > 0 } ?: return null
        val apk = obj.optString("apk", "")
        val partsArray = obj.optJSONArray("parts") ?: JSONArray()
        val parts = (0 until partsArray.length()).map { partsArray.optString(it) }.filter { it.isNotBlank() }
        val size = obj.optLong("size", 0L)
        val sha256 = obj.optString("sha256", "").lowercase()
        if (parts.isEmpty() && apk.isBlank()) return null
        if (parts.isNotEmpty() && (size <= 0L || sha256.length != 64)) return null
        val array = obj.optJSONArray("notes") ?: JSONArray()
        val allLines = (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
        val lines = if (MayakConfig.ADMIN) allLines else allLines.filterNot(::isAdminOnlyNote)
        AppReleaseInfo(
            versionTag = "build-$number",
            releaseUrl = "",
            source = RemoteVersionSource.Release,
            // Pieces when there are any: a whole APK cannot get through mobile whitelists.
            downloadUrl = if (parts.isNotEmpty()) YANDEX_PARTS_URL else MayakYandex.downloadApiUrl(apk),
            notes = lines.take(MAX_NOTES),
            yandexParts = parts,
            yandexSize = size,
            yandexSha256 = sha256,
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

    lastDownloadProblem = ""
    val apk = (downloadApk(context, release, url, onProgress) ?: yandexFallback(context, release, onProgress))
        ?: return "Не получилось скачать обновление. Проверь интернет (или включи Маяк) и нажми «Обновить» ещё раз." +
            lastDownloadProblem.takeIf { it.isNotBlank() }?.let { "\n($it)" }.orEmpty()

    return if (launchInstaller(context, apk)) null else "Не получилось открыть установку. Нажми «Обновить» ещё раз."
}

/**
 * GitHub showed the new version but its file did not come (blocked or throttled): take the same
 * build from Yandex Disk in pieces.
 */
private suspend fun yandexFallback(
    context: Context,
    release: AppReleaseInfo,
    onProgress: (downloaded: Long, total: Long) -> Unit,
): File? = withContext(Dispatchers.IO) {
    if (release.yandexParts.isNotEmpty()) return@withContext null // already was the Yandex way
    val yandex = runCatching { fetchYandexRelease() }.getOrNull() ?: return@withContext null
    if (yandex.yandexParts.isEmpty()) return@withContext null
    if ((buildNumberOf(yandex.versionTag) ?: 0) < (buildNumberOf(release.versionTag) ?: Int.MAX_VALUE)) {
        return@withContext null
    }
    Log.i(LOG_TAG, "Update: GitHub file did not come, taking ${yandex.versionTag} from Yandex Disk")
    downloadApk(context, yandex, YANDEX_PARTS_URL, onProgress)
}

private suspend fun downloadApk(
    context: Context,
    release: AppReleaseInfo,
    url: String,
    onProgress: (downloaded: Long, total: Long) -> Unit,
): File? {
    return withContext(Dispatchers.IO) {
        try {
            if (release.yandexParts.isNotEmpty()) {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                // Keep this version's saved pieces; drop everything else.
                dir.listFiles()?.forEach {
                    if (it.name != "parts-${release.versionTag}") it.deleteRecursively()
                }
                val file = File(dir, "mayak-${release.versionTag}.apk")
                return@withContext if (downloadYandexParts(release, dir, file, onProgress)) file else null
            }
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
    }
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
