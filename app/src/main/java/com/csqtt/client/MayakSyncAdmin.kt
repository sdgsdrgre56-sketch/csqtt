// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak Admin: publishes server lists and announcements for people's phones (see MayakSync).

package com.csqtt.client

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal class SyncException(message: String) : Exception(message)

internal object MayakSyncAdmin {
    private const val PREFS = "mayak_admin_sync"
    private const val TOKEN_KEY = "github_token"
    private const val API = "https://api.github.com/repos/${MayakSync.OWNER}/${MayakSync.REPO}/contents"

    fun hasToken(context: Context): Boolean = token(context) != null

    fun saveToken(context: Context, token: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (token.isBlank()) {
            prefs.edit().remove(TOKEN_KEY).apply()
        } else {
            prefs.edit().putString(TOKEN_KEY, SecureStringStore(context).encrypt(token.trim())).apply()
        }
    }

    private fun token(context: Context): String? {
        val stored = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(TOKEN_KEY, null)
        return SecureStringStore(context).decrypt(stored)?.takeIf { it.isNotBlank() }
    }

    /** Shows [text] as a banner in every Mayak. Empty text removes the banner. */
    suspend fun publishAnnouncement(context: Context, text: String) = withContext(Dispatchers.IO) {
        val token = token(context) ?: throw SyncException("Сначала вставь токен GitHub")
        val body = JSONObject()
            .put("id", if (text.isBlank()) 0L else System.currentTimeMillis() / 1000L)
            .put("text", text.trim().take(500))
            .toString()
        putFile(token, "announce.json", body, if (text.isBlank()) "Убрать объявление" else "Объявление")
    }

    data class PublishResult(val people: Int, val changedFiles: Int, val problems: List<String>)

    /**
     * Collects every person from every configured server (people are matched by name), builds
     * each person's current list of links and writes it, encrypted, under each of that person's
     * passwords. Files whose content did not change are left alone.
     */
    suspend fun publishPeople(context: Context, store: SettingsStore): PublishResult = withContext(Dispatchers.IO) {
        val token = token(context) ?: throw SyncException("Сначала вставь токен GitHub")
        val problems = mutableListOf<String>()
        // name -> list of (server number, host, person)
        val byName = linkedMapOf<String, MutableList<Triple<Int, String, PanelPerson>>>()
        for (profile in store.adminPanelProfiles(MayakConfig.ADMIN_SERVERS)) {
            if (!profile.isConfigured) continue
            val number = profile.index + 1
            val people = try {
                PanelApi(context, PanelServer(profile.host, profile.webPort, profile.webLogin, profile.webPassword))
                    .listPeople()
            } catch (failure: PanelException) {
                problems += "сервер $number: ${failure.message}"
                continue
            }
            for (person in people) {
                if (person.isMain || person.password.isBlank()) continue
                byName.getOrPut(person.name) { mutableListOf() } += Triple(number, profile.host, person)
            }
        }
        if (byName.isEmpty() && problems.isNotEmpty()) {
            throw SyncException("Серверы не ответили: " + problems.joinToString("; "))
        }

        val now = System.currentTimeMillis() / 1000L
        var changed = 0
        for ((name, entries) in byName) {
            val sorted = entries.sortedBy { it.first }
            val links = sorted
                .filter { (_, _, person) -> person.enabled && (person.expiresAt == 0L || person.expiresAt > now) }
                .map { (_, host, person) ->
                    val port = if (person.dtlsPort in 1..65535) person.dtlsPort else CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT
                    PanelApi.buildLink(host, port, person.password, person.expiresAt)
                }
            if (links.isEmpty()) continue // switched off or expired everywhere: leave the old list
            for ((number, _, person) in sorted) {
                val path = MayakSync.filePath(person.password)
                try {
                    val existing = getFile(token, path)
                    val same = existing?.let { (text, _) ->
                        MayakSync.decrypt(text, person.password)?.let(MayakSync::decodeList)?.links == links
                    } ?: false
                    if (same) continue
                    val payload = MayakSync.encrypt(
                        MayakSync.encodeList(MayakSync.PersonList(updated = now, links = links)),
                        person.password,
                    )
                    putFile(token, path, payload, "Список серверов", existing?.second)
                    changed += 1
                } catch (failure: SyncException) {
                    problems += "$name (сервер $number): ${failure.message}"
                }
            }
        }
        PublishResult(people = byName.size, changedFiles = changed, problems = problems)
    }

    /**
     * Creates on server [targetIndex] everyone who exists on the other servers but not there yet,
     * with the same name and the same days left, then publishes the new lists.
     */
    suspend fun addServerForAll(context: Context, store: SettingsStore, targetIndex: Int): String =
        withContext(Dispatchers.IO) {
            val profiles = store.adminPanelProfiles(MayakConfig.ADMIN_SERVERS)
            val target = profiles.firstOrNull { it.index == targetIndex && it.isConfigured }
                ?: throw SyncException("Сервер ${targetIndex + 1} не настроен: укажи IP и доступ к панели")
            val targetApi = PanelApi(context, PanelServer(target.host, target.webPort, target.webLogin, target.webPassword))
            val already = targetApi.listPeople().filter { !it.isMain }.map { it.name }.toSet()
            val now = System.currentTimeMillis() / 1000L
            val wanted = linkedMapOf<String, Long>() // name -> expiresAt (0 = no end)
            for (profile in profiles) {
                if (!profile.isConfigured || profile.index == targetIndex) continue
                val people = runCatching {
                    PanelApi(context, PanelServer(profile.host, profile.webPort, profile.webLogin, profile.webPassword))
                        .listPeople()
                }.getOrNull() ?: continue
                for (person in people) {
                    if (person.isMain || !person.enabled || person.name in already) continue
                    if (person.expiresAt != 0L && person.expiresAt <= now) continue
                    val known = wanted[person.name]
                    // Keep the longest access seen for this name; 0 (no end) wins.
                    if (known == null || (known != 0L && (person.expiresAt == 0L || person.expiresAt > known))) {
                        wanted[person.name] = person.expiresAt
                    }
                }
            }
            var created = 0
            val failed = mutableListOf<String>()
            for ((name, expiresAt) in wanted) {
                val days = if (expiresAt == 0L) 0 else (((expiresAt - now) + 86_399L) / 86_400L).toInt().coerceIn(1, 3650)
                try {
                    targetApi.createClient(name = name, days = days, peerPort = target.peerPort)
                    created += 1
                } catch (failure: PanelException) {
                    failed += "$name: ${failure.message}"
                }
            }
            val published = publishPeople(context, store)
            buildString {
                append("На сервере ${targetIndex + 1} заведено людей: $created.")
                if (failed.isNotEmpty()) append(" Не получилось: ${failed.joinToString("; ")}.")
                append(" Списки обновлены у людей: ${published.people}.")
                if (published.problems.isNotEmpty()) append(" Проблемы: ${published.problems.joinToString("; ")}.")
            }
        }

    // ---- GitHub contents API ----------------------------------------------------------------

    /** Returns (decoded text, sha) or null if the file does not exist. */
    private fun getFile(token: String, path: String): Pair<String, String>? {
        val (code, text) = http(token, "GET", "$API/$path", null)
        if (code == 404) return null
        if (code != 200) throw SyncException(githubError(code))
        val obj = JSONObject(text)
        val content = String(Base64.decode(obj.optString("content", ""), Base64.DEFAULT), Charsets.UTF_8)
        return content to obj.optString("sha", "")
    }

    private fun putFile(token: String, path: String, content: String, message: String, knownSha: String? = null) {
        val sha = knownSha ?: getFile(token, path)?.second
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        if (!sha.isNullOrBlank()) body.put("sha", sha)
        val (code, _) = http(token, "PUT", "$API/$path", body.toString())
        if (code !in 200..201) throw SyncException(githubError(code))
    }

    private fun githubError(code: Int): String = when (code) {
        401 -> "GitHub не принял токен. Проверь, что он вставлен целиком и не просрочен."
        403 -> "У токена нет права на запись в ${MayakSync.REPO} (нужно Contents: Read and write)."
        404 -> "Не найден репозиторий ${MayakSync.OWNER}/${MayakSync.REPO}, или у токена нет к нему доступа."
        409, 422 -> "GitHub отклонил запись (код $code). Попробуй ещё раз."
        else -> "GitHub ответил кодом $code"
    }

    private fun http(token: String, method: String, url: String, body: String?): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "MayakAdmin/${BuildConfig.VERSION_NAME}")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (e: java.io.IOException) {
            throw SyncException("Нет связи с GitHub. Включи Маяк или Wi-Fi и попробуй ещё раз.")
        } finally {
            connection.disconnect()
        }
    }
}
