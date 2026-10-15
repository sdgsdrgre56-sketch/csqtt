package app.mayak.dion

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Updates of DION Mayak, like Mayak Admin: GitHub releases "dion-build-N" of the owner's fork, and a copy
 * on the owner's public Yandex Disk folder (dion.json + 768 KB pieces) for mobile networks with whitelists.
 * The app's own traffic does not go through its VPN, so on a whitelisted network only Yandex answers.
 */
object DionUpdate {
    private const val RELEASES = "https://api.github.com/repos/sdgsdrgre56-sketch/csqtt/releases?per_page=30"
    private const val YANDEX_FOLDER = "https://disk.yandex.ru/d/i7uxPmhYZdXpVQ"
    private const val YANDEX_API = "https://cloud-api.yandex.net/v1/disk/public/resources/download"
    private const val MAX_PART = 1_000_000
    private val TAG = Regex("dion-build-(\\d+)")

    data class Release(
        val build: Int,
        val githubUrl: String = "",
        val parts: List<String> = emptyList(),
        val size: Long = 0,
        val sha256: String = "",
    )

    enum class Stage { IDLE, CHECKING, NONE, AVAILABLE, DOWNLOADING, FAILED }

    val stage = MutableStateFlow(Stage.IDLE)
    val available = MutableStateFlow<Release?>(null)
    val progress = MutableStateFlow(0f)
    val message = MutableStateFlow("")

    fun localBuild(): Int = (BuildConfig.VERSION_CODE - 1000).coerceAtLeast(0)

    suspend fun check() = withContext(Dispatchers.IO) {
        stage.value = Stage.CHECKING
        message.value = ""
        val gh = runCatching { fromGithub() }.getOrNull()
        val ya = runCatching { fromYandex() }.getOrNull()
        // Newest wins; the same build from both keeps both ways to download it.
        val best = listOfNotNull(gh, ya).maxByOrNull { it.build }?.let { top ->
            if (gh != null && ya != null && gh.build == ya.build) gh.copy(parts = ya.parts, size = ya.size, sha256 = ya.sha256) else top
        }
        when {
            best == null -> {
                stage.value = Stage.FAILED
                message.value = "Ни GitHub, ни Яндекс Диск не ответили"
            }
            best.build > localBuild() -> {
                available.value = best
                stage.value = Stage.AVAILABLE
                message.value = "Есть сборка ${best.build}"
            }
            else -> {
                available.value = null
                stage.value = Stage.NONE
                message.value = "Стоит последняя сборка (${localBuild()})"
            }
        }
    }

    suspend fun downloadAndInstall(context: Context) = withContext(Dispatchers.IO) {
        val release = available.value ?: return@withContext
        stage.value = Stage.DOWNLOADING
        progress.value = 0f
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.filter { it.name.endsWith(".apk") }?.forEach { it.delete() }
        val apk = File(dir, "DionMayak-${release.build}.apk")
        var ok = release.githubUrl.isNotBlank() && downloadDirect(release.githubUrl, apk)
        if (!ok && release.parts.isNotEmpty()) {
            message.value = "GitHub не ответил, качаю с Яндекс Диска"
            ok = downloadParts(release, dir, apk)
        }
        if (!ok) {
            stage.value = Stage.FAILED
            if (message.value.isBlank() || message.value.startsWith("GitHub")) message.value = "Не удалось скачать обновление"
            return@withContext
        }
        stage.value = Stage.AVAILABLE
        message.value = "Скачано, открываю установку"
        withContext(Dispatchers.Main) {
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
                context.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                stage.value = Stage.FAILED
                message.value = "Не открылась установка: ${it.message}"
            }
        }
    }

    private fun fromGithub(): Release? {
        val text = getBytes(RELEASES, 2_000_000, github = true)?.toString(Charsets.UTF_8) ?: return null
        val arr = JSONArray(text)
        val wanted = if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") "arm64" else "universal"
        var best: Release? = null
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            if (r.optBoolean("draft")) continue
            val n = TAG.matchEntire(r.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (best != null && n <= best.build) continue
            val assets = r.optJSONArray("assets") ?: continue
            var url = ""
            for (j in 0 until assets.length()) {
                val a = assets.optJSONObject(j) ?: continue
                if (a.optString("name") == "DionMayak-$n-$wanted.apk") url = a.optString("browser_download_url")
            }
            if (url.isNotBlank()) best = Release(n, githubUrl = url)
        }
        return best
    }

    private fun fromYandex(): Release? {
        val text = yandexFile("dion.json", 64 * 1024)?.toString(Charsets.UTF_8) ?: return null
        val o = JSONObject(text)
        val n = o.optInt("build", 0).takeIf { it > 0 } ?: return null
        val arr = o.optJSONArray("parts") ?: return null
        val parts = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        return Release(n, parts = parts, size = o.optLong("size"), sha256 = o.optString("sha256").lowercase())
    }

    private fun downloadDirect(url: String, file: File): Boolean {
        var current = URL(url)
        repeat(6) {
            val c = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "DionMayak/${BuildConfig.VERSION_NAME}")
            }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    current = URL(current, c.getHeaderField("Location") ?: return false)
                    return@repeat
                }
                if (code != 200) return false
                val total = c.contentLengthLong
                var done = 0L
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            done += r
                            if (total > 0) progress.value = done.toFloat() / total
                        }
                    }
                }
                return total <= 0 || done == total
            } catch (e: Exception) {
                return false
            } finally {
                c.disconnect()
            }
        }
        return false
    }

    /** Pieces three at a time, glued and checked by size and SHA-256 (same scheme as Mayak). */
    private fun downloadParts(release: Release, dir: File, file: File): Boolean {
        val partsDir = File(dir, "parts-${release.build}").apply { mkdirs() }
        val done = AtomicLong(0)
        val failure = AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(3)
        try {
            release.parts.mapIndexed { i, part ->
                pool.submit(Runnable {
                    val saved = File(partsDir, part)
                    if (saved.isFile && saved.length() in 1..MAX_PART.toLong()) {
                        progress.value = done.addAndGet(saved.length()).toFloat() / release.size
                        return@Runnable
                    }
                    var bytes: ByteArray? = null
                    for (attempt in 1..5) {
                        if (failure.get() != null) return@Runnable
                        bytes = yandexFile(part, MAX_PART + 1)?.takeIf { it.isNotEmpty() && it.size <= MAX_PART }
                        if (bytes != null) break
                        Thread.sleep(1500L * attempt)
                    }
                    val got = bytes
                    if (got == null) {
                        failure.compareAndSet(null, "кусок ${i + 1} из ${release.parts.size} не скачался")
                        return@Runnable
                    }
                    saved.writeBytes(got)
                    progress.value = done.addAndGet(got.size.toLong()).toFloat() / release.size
                })
            }.forEach { runCatching { it.get() } }
        } finally {
            pool.shutdownNow()
        }
        failure.get()?.let {
            message.value = "Яндекс Диск: $it. Нажми ещё раз, скачанное не пропадёт"
            return false
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var glued = 0L
        file.outputStream().use { out ->
            release.parts.forEach { part ->
                val b = File(partsDir, part).readBytes()
                out.write(b)
                digest.update(b)
                glued += b.size
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        partsDir.deleteRecursively()
        if (glued != release.size || sha != release.sha256) {
            message.value = "Файл собрался неправильно, попробуй ещё раз"
            return false
        }
        return true
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    /** A file from the public Yandex Disk folder (via a temporary direct link). */
    private fun yandexFile(path: String, limit: Int): ByteArray? {
        val api = "$YANDEX_API?public_key=${enc(YANDEX_FOLDER)}&path=${enc("/$path")}"
        val text = getBytes(api, 64 * 1024)?.toString(Charsets.UTF_8) ?: return null
        val href = runCatching { JSONObject(text).optString("href") }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return getBytes(href, limit)
    }

    private fun getBytes(url: String, limit: Int, github: Boolean = false): ByteArray? {
        var current = URL(url)
        repeat(6) {
            val c = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 6_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "DionMayak/${BuildConfig.VERSION_NAME}")
                if (github) setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    current = URL(current, c.getHeaderField("Location") ?: return null)
                    return@repeat
                }
                if (code != 200) return null
                return c.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8 * 1024)
                    while (out.size() < limit) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                    }
                    out.toByteArray()
                }
            } catch (e: Exception) {
                return null
            } finally {
                c.disconnect()
            }
        }
        return null
    }
}
