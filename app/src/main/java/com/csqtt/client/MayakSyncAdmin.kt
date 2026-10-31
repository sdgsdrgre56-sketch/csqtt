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

    // ---- Yandex Disk (second place, reachable on mobile whitelists) -------------------------

    private const val YANDEX_TOKEN_KEY = "yandex_token"

    fun hasYandex(context: Context): Boolean = yandex(context) != null

    /** Saves the Yandex Disk OAuth token; blank removes it. */
    fun saveYandex(context: Context, token: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Old WebDAV login/app password (no longer usable) are dropped either way.
        val edit = prefs.edit().remove("yandex_login").remove("yandex_app_password")
        if (token.isBlank()) {
            edit.remove(YANDEX_TOKEN_KEY).apply()
        } else {
            edit.putString(YANDEX_TOKEN_KEY, SecureStringStore(context).encrypt(token.trim())).apply()
        }
    }

    private fun yandex(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SecureStringStore(context).decrypt(prefs.getString(YANDEX_TOKEN_KEY, null))?.takeIf { it.isNotBlank() }
    }

    fun canPublish(context: Context): Boolean = hasToken(context) || hasYandex(context)

    /**
     * Shows [text] as a banner in every Mayak. Empty text removes the banner. Written to GitHub
     * and to Yandex Disk (whichever is set up); fails only if it reached neither.
     */
    suspend fun publishAnnouncement(context: Context, text: String): String = withContext(Dispatchers.IO) {
        val token = token(context)
        val yandex = yandex(context)
        if (token == null && yandex == null) throw SyncException("Сначала вставь токен GitHub или токен Яндекс Диска")
        val body = JSONObject()
            .put("id", if (text.isBlank()) 0L else System.currentTimeMillis() / 1000L)
            .put("text", text.trim().take(500))
            .toString()
        val done = mutableListOf<String>()
        val failed = mutableListOf<String>()
        if (token != null) {
            try {
                putFile(token, "announce.json", body, if (text.isBlank()) "Убрать объявление" else "Объявление")
                done += "GitHub"
            } catch (failure: SyncException) {
                failed += "GitHub: ${failure.message}"
            }
        }
        if (yandex != null) {
            try {
                MayakYandex.putText(yandex, "announce.json", body)
                done += "Яндекс Диск"
            } catch (failure: MayakYandex.WriteException) {
                failed += "Яндекс Диск: ${failure.message}"
            }
        }
        if (done.isEmpty()) throw SyncException(failed.joinToString("; "))
        buildString {
            append(if (text.isBlank()) "Объявление убрано" else "Объявление отправлено")
            append(" (${done.joinToString(", ")}).")
            if (failed.isNotEmpty()) append(" Не получилось: ${failed.joinToString("; ")}")
        }
    }

    data class PublishResult(val people: Int, val changedFiles: Int, val problems: List<String>)

    /**
     * Collects every person from every configured server (people are matched by name), builds
     * each person's current list of links and writes it, encrypted, under each of that person's
     * passwords. Files whose content did not change are left alone.
     */
    suspend fun publishPeople(context: Context, store: SettingsStore): PublishResult = withContext(Dispatchers.IO) {
        val token = token(context)
        val yandex = yandex(context)
        if (token == null && yandex == null) throw SyncException("Сначала вставь токен GitHub или токен Яндекс Диска")
        val configured = store.adminPanelProfiles(MayakConfig.ADMIN_SERVERS).filter { it.isConfigured }
        // server number -> the admin's note about it, carried inside each link
        val notes = (0 until MayakConfig.ADMIN_SERVERS).associate { it + 1 to runCatching { store.serverNote(it) }.getOrDefault("") }

        // One server that answered its panel: its number, address and current people.
        data class Answered(val number: Int, val host: String, val peerPort: Int, val server: PanelServer, val people: List<PanelPerson>)
        // Everything one round of asking the panels gives us.
        data class Collected(
            val byName: LinkedHashMap<String, MutableList<Triple<Int, String, PanelPerson>>>,
            val silent: LinkedHashMap<String, Int>,
            val answered: List<Answered>,
            val problems: List<String>,
        )

        // Asks every configured server who it knows (people are matched by name). A server that does
        // not answer is remembered as "silent" so its links are carried over and nobody is created on it.
        suspend fun collect(): Collected {
            val byName = linkedMapOf<String, MutableList<Triple<Int, String, PanelPerson>>>()
            val silent = linkedMapOf<String, Int>()
            val answered = mutableListOf<Answered>()
            val problems = mutableListOf<String>()
            for (profile in configured) {
                val number = profile.index + 1
                val server = PanelServer(profile.host, profile.webPort, profile.webLogin, profile.webPassword)
                val people = try {
                    PanelApi(context, server).listPeople()
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    // One server that is down or answers nonsense must not stop the lists for everyone else.
                    problems += "сервер $number: ${failure.message ?: "не отвечает"} — у людей он остаётся как был"
                    silent[profile.host] = number
                    continue
                }
                answered += Answered(number, profile.host, profile.peerPort, server, people)
                for (person in people) {
                    if (person.isMain || person.password.isBlank()) continue
                    byName.getOrPut(person.name) { mutableListOf() } += Triple(number, profile.host, person)
                }
            }
            return Collected(byName, silent, answered, problems)
        }

        var collected = collect()

        // Convenience: before sending the lists, put every active person on every server that answered,
        // so a server just added in the panel reaches all phones after one "Разослать" — without new links.
        // Only people switched on and not expired are copied, each with the latest end date they have;
        // silent servers are never created on, so nobody is made twice.
        val fillProblems = mutableListOf<String>()
        run {
            val nowSeconds = System.currentTimeMillis() / 1000L
            // (server, person name, days left) for every seat that is missing right now.
            val seats = mutableListOf<Triple<Answered, String, Int>>()
            for ((name, entries) in collected.byName) {
                if (name.isBlank()) continue
                val active = entries.map { it.third }.filter { it.enabled && (it.expiresAt <= 0L || it.expiresAt > nowSeconds) }
                if (active.isEmpty()) continue
                val days = if (active.any { it.expiresAt <= 0L }) 0
                    else (((active.maxOf { it.expiresAt } - nowSeconds) + 86_399L) / 86_400L).toInt().coerceIn(1, 3650)
                val present = entries.map { it.first }.toSet()
                for (srv in collected.answered) {
                    if (srv.number !in present) seats += Triple(srv, name, days)
                }
            }
            var created = 0
            for ((srv, name, days) in seats) {
                try {
                    PanelApi(context, srv.server).createClient(name, days, srv.peerPort)
                    created += 1
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    fillProblems += "сервер ${srv.number}: не добавить $name (${failure.message ?: "ошибка"})"
                }
            }
            // Re-ask the panels only if we actually added someone, so the new seats get into the lists.
            if (created > 0) collected = collect()
        }

        val problems = (collected.problems + fillProblems).toMutableList()
        val byName = collected.byName
        val silent = collected.silent
        if (byName.isEmpty() && problems.isNotEmpty()) {
            throw SyncException("Серверы не ответили: " + problems.joinToString("; "))
        }

        val now = System.currentTimeMillis() / 1000L
        var changed = 0
        val githubProblems = linkedSetOf<String>()
        val yandexProblems = linkedSetOf<String>()
        for ((_, entries) in byName) {
            val sorted = entries.sortedBy { it.first }
            val current = sorted
                .filter { (_, _, person) -> person.enabled && (person.expiresAt == 0L || person.expiresAt > now) }
                // The same name twice on one server (made twice by mistake): one link per server,
                // the one with the longest access (no end wins).
                .groupBy { it.first }
                .map { (_, onServer) -> onServer.maxBy { (_, _, p) -> if (p.expiresAt == 0L) Long.MAX_VALUE else p.expiresAt } }
                .sortedBy { it.first }
                .map { (number, host, person) ->
                    val port = if (person.dtlsPort in 1..65535) person.dtlsPort else CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT
                    number to PanelApi.buildLink(
                        host, port, person.password, person.expiresAt, notes[number].orEmpty(),
                        ServerFlags.manual(context, host).orEmpty(),
                    )
                }
            if (current.isEmpty()) continue // switched off or expired everywhere: leave the old list
            // A server whose panel did not answer must not vanish from people's phones: its links
            // are carried over from the list published last time.
            val carried = if (silent.isEmpty() || token == null) emptyList() else runCatching {
                val previous = sorted.firstNotNullOfOrNull { (_, _, person) ->
                    getFile(token, MayakSync.filePath(person.password))?.first
                        ?.let { MayakSync.decrypt(it, person.password) }
                        ?.let(MayakSync::decodeList)
                }
                previous?.links.orEmpty().mapNotNull { link ->
                    val host = com.csqtt.client.ui.utils.parseCsqttLink(link)?.host ?: return@mapNotNull null
                    silent[host]?.let { number -> number to link }
                }
            }.getOrDefault(emptyList())
            val links = (current + carried).sortedBy { it.first }.map { it.second }.distinct()
            for ((_, _, person) in sorted) {
                val fresh by lazy {
                    MayakSync.encrypt(
                        MayakSync.encodeList(MayakSync.PersonList(updated = now, links = links)),
                        person.password,
                    )
                }
                // The payload written to Yandex Disk is the same one GitHub holds.
                var payload: String? = null
                if (token != null) {
                    val path = MayakSync.filePath(person.password)
                    try {
                        val existing = getFile(token, path)
                        val same = existing?.let { (text, _) ->
                            MayakSync.decrypt(text, person.password)?.let(MayakSync::decodeList)?.links == links
                        } ?: false
                        if (same) {
                            payload = existing?.first
                        } else {
                            putFile(token, path, fresh, "Список серверов", existing?.second)
                            payload = fresh
                            changed += 1
                        }
                    } catch (failure: SyncException) {
                        githubProblems += failure.message.orEmpty()
                    }
                }
                if (yandex != null) {
                    try {
                        MayakYandex.putText(yandex, yandexPersonFile(person.password), payload ?: fresh)
                        if (token == null) changed += 1
                    } catch (failure: MayakYandex.WriteException) {
                        yandexProblems += failure.message.orEmpty()
                    }
                }
            }
        }
        if (githubProblems.isNotEmpty()) problems += "GitHub: " + githubProblems.joinToString("; ")
        if (yandexProblems.isNotEmpty()) problems += "Яндекс Диск: " + yandexProblems.joinToString("; ")

        // Which servers answered their panel, for the green/red number in people's Mayak. Public (not
        // secret): just host -> true/false. A snapshot as of this "Разослать".
        runCatching {
            val up = JSONObject()
            for (srv in collected.answered) up.put(srv.host, true)
            for ((host, _) in collected.silent) up.put(host, false)
            val statusBody = JSONObject().put("updated", now).put("up", up).toString()
            if (token != null) runCatching { putFile(token, "status.json", statusBody, "Статус серверов") }
            if (yandex != null) runCatching { MayakYandex.putText(yandex, "status.json", statusBody) }
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
            // A new server often drops the first request; try twice before giving up, and say which one.
            val targetPeople = try {
                targetApi.listPeople()
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (first: Exception) {
                kotlinx.coroutines.delay(3_000)
                try {
                    targetApi.listPeople()
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    throw SyncException(
                        "Сервер ${targetIndex + 1} не отвечает (${failure.message ?: "нет ответа"}). " +
                            "Проверь его в «Проверке связи» и попробуй ещё раз",
                    )
                }
            }
            val already = targetPeople.filter { !it.isMain }.map { it.name }.toSet()
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

    // GitHub lets one token create content at most ~80 times a minute ("secondary rate limit").
    // A list for 20+ people is hundreds of files, so writes are paced, and when GitHub still asks
    // to slow down the write waits and is repeated instead of being dropped.
    private const val PUT_GAP_MS = 900L
    private const val LIMIT_WAIT_MS = 65_000L
    private const val LIMIT_TRIES = 4
    private var lastPutAt = 0L

    private fun pace() {
        val wait = lastPutAt + PUT_GAP_MS - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastPutAt = System.currentTimeMillis()
    }

    private fun putFile(token: String, path: String, content: String, message: String, knownSha: String? = null) {
        var sha = knownSha ?: getFile(token, path)?.second
        repeat(LIMIT_TRIES) { attempt ->
            val body = JSONObject()
                .put("message", message)
                .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            if (!sha.isNullOrBlank()) body.put("sha", sha)
            pace()
            val (code, text) = http(token, "PUT", "$API/$path", body.toString())
            when {
                code in 200..201 -> return
                isRateLimited(code, text) && attempt < LIMIT_TRIES - 1 -> Thread.sleep(LIMIT_WAIT_MS)
                code == 409 && attempt < LIMIT_TRIES - 1 -> sha = getFile(token, path)?.second // changed meanwhile
                isRateLimited(code, text) -> throw SyncException("GitHub просит подождать (лимит записей). Нажми «Разослать» ещё раз через час.")
                else -> throw SyncException(githubError(code))
            }
        }
    }

    private fun isRateLimited(code: Int, text: String): Boolean =
        code == 429 || (code == 403 && (text.contains("rate limit", ignoreCase = true) || text.contains("abuse", ignoreCase = true)))

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
