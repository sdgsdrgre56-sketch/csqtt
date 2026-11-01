// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: server lists and announcements that reach people's phones without new links.

package com.csqtt.client

import com.csqtt.client.ui.utils.parseCsqttLink
import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * How it works
 *
 * Mayak Admin writes into a separate public GitHub repository ([OWNER]/[REPO]):
 *  - announce.json: {"id": <unix seconds>, "text": "..."} shown as a banner in every Mayak;
 *  - p/<id>.txt: one file per server password of every person. It holds that person's current
 *    list of links, encrypted with AES-256-GCM. Both the file name and the key are derived from
 *    the password, which only the person's phone, the server and the admin know, so nobody else
 *    can find or read a list. Nothing new has to be given to people: their phone already has the
 *    passwords in its links.
 *
 * Mayak (client) looks up the files of its own links from time to time and, when a newer list is
 * there, replaces its stored links with it. So a new server, a replaced server or a longer access
 * period reaches people by itself.
 */
internal object MayakSync {
    const val OWNER = "sdgsdrgre56-sketch"
    const val REPO = "mayak-sync"
    private const val BRANCH = "main"
    private const val RAW_BASE = "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH"
    private const val LOG_TAG = "Mayak"
    private const val PREFS = "mayak_sync"
    // Checked every time Mayak connects and when the app opens; this only stops a burst of
    // reconnects from asking GitHub again and again.
    private const val CHECK_EVERY_MS = 2L * 60 * 1000
    private const val KEY_ITERATIONS = 20_000

    // ---- shared crypto ----------------------------------------------------------------------

    /** File name (without folder) for one server password. */
    fun fileId(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("mayak-sync-id:v1:$password".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun filePath(password: String): String = "p/${fileId(password)}.txt"

    private fun key(password: String): SecretKeySpec {
        val spec = PBEKeySpec(
            password.toCharArray(),
            "mayak-sync-key:v1".toByteArray(Charsets.UTF_8),
            KEY_ITERATIONS,
            256,
        )
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(plain: String, password: String): String {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password), GCMParameterSpec(128, nonce))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "m1:" + Base64.encodeToString(nonce + sealed, Base64.NO_WRAP)
    }

    fun decrypt(text: String, password: String): String? = runCatching {
        val body = text.trim().removePrefix("m1:")
        val bytes = Base64.decode(body, Base64.NO_WRAP)
        if (bytes.size < 12 + 16) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password), GCMParameterSpec(128, bytes, 0, 12))
        cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
    }.getOrNull()

    data class PersonList(val updated: Long, val links: List<String>)

    fun encodeList(list: PersonList): String = JSONObject()
        .put("v", 1)
        .put("updated", list.updated)
        .put("links", JSONArray(list.links))
        .toString()

    fun decodeList(json: String): PersonList? = runCatching {
        val obj = JSONObject(json)
        val array = obj.optJSONArray("links") ?: JSONArray()
        PersonList(
            updated = obj.optLong("updated", 0L),
            links = (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() },
        )
    }.getOrNull()

    // ---- client side ------------------------------------------------------------------------

    data class Announcement(val id: Long, val text: String)

    private val announcementState = MutableStateFlow<Announcement?>(null)

    /** The current announcement the person has not closed yet, or null. */
    val announcement: StateFlow<Announcement?> = announcementState

    private val serverStatusState = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** host -> did the server answer the admin at the last mailout (green/red number). */
    val serverStatus: StateFlow<Map<String, Boolean>> = serverStatusState

    /** Loads the remembered server status (set by [syncClient]); safe to call on screen open. */
    fun loadServerStatus(context: Context) {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("server_status", null) ?: return
        runCatching {
            val up = JSONObject(raw).optJSONObject("up") ?: return
            serverStatusState.value = up.keys().asSequence().associateWith { up.optBoolean(it) }
        }
    }

    private val requiredBuildState = MutableStateFlow(0)

    /** Build the admin made obligatory for people's Mayak ("force.json"); 0 when there is none. */
    val requiredBuild: StateFlow<Int> = requiredBuildState

    /** Loads the remembered obligatory build (set by [syncClient]). */
    fun loadRequiredBuild(context: Context) {
        requiredBuildState.value = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("required_build", 0)
    }

    private val clientLock = Mutex()

    fun loadAnnouncement(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong("announce_id", 0L)
        val text = prefs.getString("announce_text", "").orEmpty()
        val closed = prefs.getLong("announce_closed", 0L)
        announcementState.value = if (id > 0L && text.isNotBlank() && id != closed) Announcement(id, text) else null
    }

    fun closeAnnouncement(context: Context) {
        val current = announcementState.value ?: return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong("announce_closed", current.id).apply()
        announcementState.value = null
    }

    /**
     * Mayak client: fetches the announcement and this phone's server list. Runs each time Mayak
     * connects and when the app opens (at most every 2 minutes unless [force]). Quietly does nothing
     * when GitHub cannot be reached.
     */
    suspend fun syncClient(context: Context, store: SettingsStore, force: Boolean = false) {
        if (!MayakConfig.SIMPLE_UI) return
        val appContext = context.applicationContext
        clientLock.withLock {
            withContext(Dispatchers.IO) {
                val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                if (!force && now - prefs.getLong("last_check", 0L) < CHECK_EVERY_MS) return@withContext
                prefs.edit().putLong("last_check", now).apply()

                (fetchText("$RAW_BASE/announce.json") ?: MayakYandex.fetchText("announce.json"))?.let { text ->
                    runCatching {
                        val obj = JSONObject(text)
                        prefs.edit()
                            .putLong("announce_id", obj.optLong("id", 0L))
                            .putString("announce_text", obj.optString("text", "").take(500))
                            .apply()
                    }
                }
                loadAnnouncement(appContext)

                (fetchText("$RAW_BASE/status.json") ?: MayakYandex.fetchText("status.json"))?.let { text ->
                    runCatching {
                        // Keep only a well-formed {"up": {...}} so a bad file never wipes the colours.
                        JSONObject(text).optJSONObject("up") ?: return@runCatching
                        prefs.edit().putString("server_status", text).apply()
                    }
                }
                loadServerStatus(appContext)

                // Obligatory update set by the admin. A missing file keeps what was known before.
                (fetchText("$RAW_BASE/force.json") ?: MayakYandex.fetchText("force.json"))?.let { text ->
                    runCatching {
                        val build = JSONObject(text).optInt("min_build", -1)
                        if (build >= 0) prefs.edit().putInt("required_build", build).apply()
                    }
                }
                loadRequiredBuild(appContext)

                if (!store.csqttLinkMode.first()) return@withContext
                val links = store.csqttLinks.first()
                // Builds up to 81 kept at most 10 links and marked the admin's (longer) list as applied,
                // so servers 11+ never arrived. Once after the update, take the last list again: one second
                // back lets that same file in, while older files (of deleted passwords) stay skipped.
                if (!prefs.getBoolean("relist_after_cap_fix", false)) {
                    val applied = prefs.getLong("list_updated", 0L)
                    prefs.edit()
                        .putLong("list_updated", if (applied > 0L) applied - 1L else 0L)
                        .putBoolean("relist_after_cap_fix", true)
                        .apply()
                }
                val lastApplied = prefs.getLong("list_updated", 0L)
                // Several links may be looked at now (old files are skipped), so the walk is bounded,
                // and GitHub is not asked again in this round once it failed on the network.
                val deadline = System.currentTimeMillis() + 30_000L
                var githubDown = false
                for (link in links) {
                    if (System.currentTimeMillis() > deadline) break
                    val password = parseCsqttLink(link)?.password ?: continue
                    // GitHub first, then the same file on the owner's Yandex Disk (mobile whitelists).
                    var fromGithub: String? = null
                    if (!githubDown) {
                        fromGithub = fetchText("$RAW_BASE/${filePath(password)}")
                        if (fromGithub == null && lastFetchNetworkError) githubDown = true
                    }
                    val text = fromGithub
                        ?: MayakYandex.fetchText(yandexPersonFile(password))
                        ?: continue
                    val list = decrypt(text, password)?.let(::decodeList) ?: continue
                    // An old file must not stop the search: when a person was deleted and created again on
                    // one server, the file of that old password is never rewritten, while the files of the
                    // other links already hold the new list. So skip it and look at the next link.
                    if (list.updated <= lastApplied) continue
                    val fresh = withCarriedHashes(list.links.filter { parseCsqttLink(it) != null }, links)
                    if (fresh.isEmpty()) continue
                    if (fresh != links) {
                        store.replaceCsqttLinksKeepingServer(fresh)
                        Log.i(LOG_TAG, "Sync: server list updated (${links.size} -> ${fresh.size})")
                    }
                    prefs.edit().putLong("list_updated", list.updated).apply()
                    break
                }
            }
        }
    }

    /** Links made by the admin carry no VK hashes; keep the ones the person's links already had. */
    private fun withCarriedHashes(fresh: List<String>, old: List<String>): List<String> {
        val hashes = old.firstNotNullOfOrNull { link ->
            Regex("[?&]hashes=([^&]+)").find(link)?.groupValues?.getOrNull(1)
        } ?: return fresh
        return fresh.map { link ->
            if (Regex("[?&]hashes=").containsMatchIn(link)) link else "$link&hashes=$hashes"
        }
    }

    /** True when the last [fetchText] failed on the network (not a mere "no such file"). */
    @Volatile
    private var lastFetchNetworkError = false

    private fun fetchText(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$url?t=${System.currentTimeMillis() / 60_000}").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "Mayak/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = connection.responseCode
            lastFetchNetworkError = false
            if (code == 200) {
                connection.inputStream.bufferedReader().use { it.readText() }.take(64 * 1024)
            } else {
                null
            }
        } catch (e: Exception) {
            lastFetchNetworkError = true
            null
        } finally {
            connection?.disconnect()
        }
    }
}
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: server lists and announcements that reach people's phones without new links.

package com.csqtt.client

import com.csqtt.client.ui.utils.parseCsqttLink
import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * How it works
 *
 * Mayak Admin writes into a separate public GitHub repository ([OWNER]/[REPO]):
 *  - announce.json: {"id": <unix seconds>, "text": "..."} shown as a banner in every Mayak;
 *  - p/<id>.txt: one file per server password of every person. It holds that person's current
 *    list of links, encrypted with AES-256-GCM. Both the file name and the key are derived from
 *    the password, which only the person's phone, the server and the admin know, so nobody else
 *    can find or read a list. Nothing new has to be given to people: their phone already has the
 *    passwords in its links.
 *
 * Mayak (client) looks up the files of its own links from time to time and, when a newer list is
 * there, replaces its stored links with it. So a new server, a replaced server or a longer access
 * period reaches people by itself.
 */
internal object MayakSync {
    const val OWNER = "sdgsdrgre56-sketch"
    const val REPO = "mayak-sync"
    private const val BRANCH = "main"
    private const val RAW_BASE = "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH"
    private const val LOG_TAG = "Mayak"
    private const val PREFS = "mayak_sync"
    // Checked every time Mayak connects and when the app opens; this only stops a burst of
    // reconnects from asking GitHub again and again.
    private const val CHECK_EVERY_MS = 2L * 60 * 1000
    private const val KEY_ITERATIONS = 20_000

    // ---- shared crypto ----------------------------------------------------------------------

    /** File name (without folder) for one server password. */
    fun fileId(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("mayak-sync-id:v1:$password".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun filePath(password: String): String = "p/${fileId(password)}.txt"

    private fun key(password: String): SecretKeySpec {
        val spec = PBEKeySpec(
            password.toCharArray(),
            "mayak-sync-key:v1".toByteArray(Charsets.UTF_8),
            KEY_ITERATIONS,
            256,
        )
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(plain: String, password: String): String {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password), GCMParameterSpec(128, nonce))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "m1:" + Base64.encodeToString(nonce + sealed, Base64.NO_WRAP)
    }

    fun decrypt(text: String, password: String): String? = runCatching {
        val body = text.trim().removePrefix("m1:")
        val bytes = Base64.decode(body, Base64.NO_WRAP)
        if (bytes.size < 12 + 16) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password), GCMParameterSpec(128, bytes, 0, 12))
        cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
    }.getOrNull()

    data class PersonList(val updated: Long, val links: List<String>)

    fun encodeList(list: PersonList): String = JSONObject()
        .put("v", 1)
        .put("updated", list.updated)
        .put("links", JSONArray(list.links))
        .toString()

    fun decodeList(json: String): PersonList? = runCatching {
        val obj = JSONObject(json)
        val array = obj.optJSONArray("links") ?: JSONArray()
        PersonList(
            updated = obj.optLong("updated", 0L),
            links = (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() },
        )
    }.getOrNull()

    // ---- client side ------------------------------------------------------------------------

    data class Announcement(val id: Long, val text: String)

    private val announcementState = MutableStateFlow<Announcement?>(null)

    /** The current announcement the person has not closed yet, or null. */
    val announcement: StateFlow<Announcement?> = announcementState

    private val serverStatusState = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** host -> did the server answer the admin at the last mailout (green/red number). */
    val serverStatus: StateFlow<Map<String, Boolean>> = serverStatusState

    /** Loads the remembered server status (set by [syncClient]); safe to call on screen open. */
    fun loadServerStatus(context: Context) {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("server_status", null) ?: return
        runCatching {
            val up = JSONObject(raw).optJSONObject("up") ?: return
            serverStatusState.value = up.keys().asSequence().associateWith { up.optBoolean(it) }
        }
    }

    private val requiredBuildState = MutableStateFlow(0)

    /** Build the admin made obligatory for people's Mayak ("force.json"); 0 when there is none. */
    val requiredBuild: StateFlow<Int> = requiredBuildState

    /** Loads the remembered obligatory build (set by [syncClient]). */
    fun loadRequiredBuild(context: Context) {
        requiredBuildState.value = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("required_build", 0)
    }

    private val clientLock = Mutex()

    fun loadAnnouncement(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong("announce_id", 0L)
        val text = prefs.getString("announce_text", "").orEmpty()
        val closed = prefs.getLong("announce_closed", 0L)
        announcementState.value = if (id > 0L && text.isNotBlank() && id != closed) Announcement(id, text) else null
    }

    fun closeAnnouncement(context: Context) {
        val current = announcementState.value ?: return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong("announce_closed", current.id).apply()
        announcementState.value = null
    }

    /**
     * Mayak client: fetches the announcement and this phone's server list. Runs each time Mayak
     * connects and when the app opens (at most every 2 minutes unless [force]). Quietly does nothing
     * when GitHub cannot be reached.
     */
    suspend fun syncClient(context: Context, store: SettingsStore, force: Boolean = false) {
        if (!MayakConfig.SIMPLE_UI) return
        val appContext = context.applicationContext
        clientLock.withLock {
            withContext(Dispatchers.IO) {
                val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                if (!force && now - prefs.getLong("last_check", 0L) < CHECK_EVERY_MS) return@withContext
                prefs.edit().putLong("last_check", now).apply()

                (fetchText("$RAW_BASE/announce.json") ?: MayakYandex.fetchText("announce.json"))?.let { text ->
                    runCatching {
                        val obj = JSONObject(text)
                        prefs.edit()
                            .putLong("announce_id", obj.optLong("id", 0L))
                            .putString("announce_text", obj.optString("text", "").take(500))
                            .apply()
                    }
                }
                loadAnnouncement(appContext)

                (fetchText("$RAW_BASE/status.json") ?: MayakYandex.fetchText("status.json"))?.let { text ->
                    runCatching {
                        // Keep only a well-formed {"up": {...}} so a bad file never wipes the colours.
                        JSONObject(text).optJSONObject("up") ?: return@runCatching
                        prefs.edit().putString("server_status", text).apply()
                    }
                }
                loadServerStatus(appContext)

                // Obligatory update set by the admin. A missing file keeps what was known before.
                (fetchText("$RAW_BASE/force.json") ?: MayakYandex.fetchText("force.json"))?.let { text ->
                    runCatching {
                        val build = JSONObject(text).optInt("min_build", -1)
                        if (build >= 0) prefs.edit().putInt("required_build", build).apply()
                    }
                }
                loadRequiredBuild(appContext)

                if (!store.csqttLinkMode.first()) return@withContext
                val links = store.csqttLinks.first()
                val lastApplied = prefs.getLong("list_updated", 0L)
                // Several links may be looked at now (old files are skipped), so the walk is bounded,
                // and GitHub is not asked again in this round once it failed on the network.
                val deadline = System.currentTimeMillis() + 30_000L
                var githubDown = false
                for (link in links) {
                    if (System.currentTimeMillis() > deadline) break
                    val password = parseCsqttLink(link)?.password ?: continue
                    // GitHub first, then the same file on the owner's Yandex Disk (mobile whitelists).
                    var fromGithub: String? = null
                    if (!githubDown) {
                        fromGithub = fetchText("$RAW_BASE/${filePath(password)}")
                        if (fromGithub == null && lastFetchNetworkError) githubDown = true
                    }
                    val text = fromGithub
                        ?: MayakYandex.fetchText(yandexPersonFile(password))
                        ?: continue
                    val list = decrypt(text, password)?.let(::decodeList) ?: continue
                    // An old file must not stop the search: when a person was deleted and created again on
                    // one server, the file of that old password is never rewritten, while the files of the
                    // other links already hold the new list. So skip it and look at the next link.
                    if (list.updated <= lastApplied) continue
                    val fresh = withCarriedHashes(list.links.filter { parseCsqttLink(it) != null }, links)
                    if (fresh.isEmpty()) continue
                    if (fresh != links) {
                        store.replaceCsqttLinksKeepingServer(fresh)
                        Log.i(LOG_TAG, "Sync: server list updated (${links.size} -> ${fresh.size})")
                    }
                    prefs.edit().putLong("list_updated", list.updated).apply()
                    break
                }
            }
        }
    }

    /** Links made by the admin carry no VK hashes; keep the ones the person's links already had. */
    private fun withCarriedHashes(fresh: List<String>, old: List<String>): List<String> {
        val hashes = old.firstNotNullOfOrNull { link ->
            Regex("[?&]hashes=([^&]+)").find(link)?.groupValues?.getOrNull(1)
        } ?: return fresh
        return fresh.map { link ->
            if (Regex("[?&]hashes=").containsMatchIn(link)) link else "$link&hashes=$hashes"
        }
    }

    /** True when the last [fetchText] failed on the network (not a mere "no such file"). */
    @Volatile
    private var lastFetchNetworkError = false

    private fun fetchText(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$url?t=${System.currentTimeMillis() / 60_000}").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "Mayak/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = connection.responseCode
            lastFetchNetworkError = false
            if (code == 200) {
                connection.inputStream.bufferedReader().use { it.readText() }.take(64 * 1024)
            } else {
                null
            }
        } catch (e: Exception) {
            lastFetchNetworkError = true
            null
        } finally {
            connection?.disconnect()
        }
    }
}
