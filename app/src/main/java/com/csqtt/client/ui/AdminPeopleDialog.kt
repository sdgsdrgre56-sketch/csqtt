// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak Admin: who is connected right now, on every configured server.

package com.csqtt.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.csqtt.client.MayakConfig
import com.csqtt.client.PanelApi
import com.csqtt.client.PanelException
import com.csqtt.client.PanelPerson
import com.csqtt.client.PanelServer
import com.csqtt.client.SettingsStore
import kotlinx.coroutines.launch

private data class ServerPeople(
    val serverNumber: Int,
    val people: List<PanelPerson>?,
    val error: String?,
    val server: PanelServer? = null,
)

/** One person as found on one server, for actions across all servers. */
private data class PersonOnServer(
    val serverNumber: Int,
    val server: PanelServer,
    val person: PanelPerson,
)

private val OnlineGreen = Color(0xFF43A047)

/**
 * Asks the panel of every configured server who it knows and how many connections each person
 * has right now. Nothing on the servers is changed.
 */
@Composable
internal fun AdminPeopleDialog(
    settingsStore: SettingsStore,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var servers by remember { mutableStateOf<List<ServerPeople>>(emptyList()) }

    fun refresh() {
        if (busy) return
        busy = true
        scope.launch {
            val results = mutableListOf<ServerPeople>()
            try {
                val profiles = settingsStore.adminPanelProfiles(MayakConfig.ADMIN_SERVERS)
                for (profile in profiles) {
                    if (!profile.isConfigured) continue
                    val number = profile.index + 1
                    val panelServer = PanelServer(profile.host, profile.webPort, profile.webLogin, profile.webPassword)
                    results += try {
                        val api = PanelApi(context, panelServer)
                        ServerPeople(number, api.listPeople(), null, panelServer)
                    } catch (failure: kotlinx.coroutines.CancellationException) {
                        throw failure
                    } catch (failure: PanelException) {
                        ServerPeople(number, null, failure.message ?: "ошибка")
                    } catch (failure: Exception) {
                        ServerPeople(number, null, "не удалось выполнить запрос")
                    }
                    servers = results.toList()
                }
                servers = results.toList()
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    var confirmUnbindAll by remember { mutableStateOf(false) }
    var unbindReport by remember { mutableStateOf<String?>(null) }

    fun unbindEveryone() {
        if (busy) return
        busy = true
        unbindReport = null
        scope.launch {
            var done = 0
            val failed = mutableListOf<String>()
            try {
                for (server in servers) {
                    val panelServer = server.server ?: continue
                    val api = PanelApi(context, panelServer)
                    for (person in server.people.orEmpty()) {
                        if (person.isMain) continue
                        try {
                            api.unbindPerson(person)
                            done += 1
                        } catch (failure: kotlinx.coroutines.CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            failed += "${person.name} (сервер ${server.serverNumber})"
                        }
                    }
                }
            } finally {
                busy = false
            }
            unbindReport = if (failed.isEmpty()) {
                "Телефоны отвязаны: $done. Каждого пустит первый телефон, с которого он подключится."
            } else {
                "Отвязано: $done. Не получилось: ${failed.joinToString(", ")}"
            }
        }
    }

    if (confirmUnbindAll) {
        AlertDialog(
            onDismissRequest = { confirmUnbindAll = false },
            title = { Text("Отвязать телефоны у всех?") },
            text = {
                Text(
                    "Нужно, если люди переустановили Маяк и сервер их не пускает. Ссылки и сроки не меняются. " +
                        "Каждого человека снова привяжет первый телефон, с которого он подключится."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnbindAll = false
                    unbindEveryone()
                }) { Text("Отвязать") }
            },
            dismissButton = { TextButton(onClick = { confirmUnbindAll = false }) { Text("Отмена") } },
        )
    }

    var selectedName by remember { mutableStateOf<String?>(null) }
    selectedName?.let { name ->
        val entries = servers.flatMap { server ->
            val panelServer = server.server ?: return@flatMap emptyList<PersonOnServer>()
            server.people.orEmpty()
                .filter { !it.isMain && it.name == name }
                .map { PersonOnServer(server.serverNumber, panelServer, it) }
        }
        PersonActionsDialog(
            name = name,
            entries = entries,
            onDone = {
                selectedName = null
                // The change (new end date, removal) also goes to the person's phone.
                if (com.csqtt.client.MayakSyncAdmin.hasToken(context)) {
                    scope.launch {
                        runCatching { com.csqtt.client.MayakSyncAdmin.publishPeople(context, settingsStore) }
                    }
                }
                refresh()
            },
            onDismiss = { selectedName = null },
        )
    }

    // People are created with the same name on every server, so the total counts names.
    val onlineNames = servers
        .flatMap { it.people.orEmpty() }
        .filter { it.sessions > 0 && !it.isMain }
        .map { it.name }
        .toSet()
    val knownNames = servers
        .flatMap { it.people.orEmpty() }
        .filter { !it.isMain }
        .map { it.name }
        .toSet()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Кто подключён",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = when {
                        servers.isEmpty() && busy -> "Спрашиваю серверы…"
                        servers.isEmpty() -> "Нет настроенных серверов: укажите IP и доступ к панели во вкладке «Деплой»."
                        else -> "Сейчас подключено: ${onlineNames.size} из ${knownNames.size}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = "Нажми на человека, чтобы продлить, выключить или удалить его на всех серверах.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { confirmUnbindAll = true },
                        enabled = !busy && servers.any { it.people != null },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Отвязать телефоны у всех")
                    }
                    unbindReport?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    servers.forEach { server -> ServerSection(server, onPerson = { selectedName = it.name }) }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { refresh() },
                        enabled = !busy,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Обновить", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text("Закрыть")
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerSection(server: ServerPeople, onPerson: (PanelPerson) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val people = server.people
        val online = people?.count { it.sessions > 0 } ?: 0
        Text(
            text = if (people == null) {
                "Сервер ${server.serverNumber}"
            } else {
                "Сервер ${server.serverNumber} · подключено $online из ${people.size}"
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        if (people == null) {
            Text(
                text = server.error ?: "ошибка",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }
        if (people.isEmpty()) {
            Text(
                text = "Никого нет",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Connected first, then by name.
        people.sortedWith(compareByDescending<PanelPerson> { it.sessions > 0 }.thenBy { it.name.lowercase() })
            .forEach { person -> PersonRow(person, onClick = { if (!person.isMain) onPerson(person) }) }
    }
}

@Composable
private fun PersonRow(person: PanelPerson, onClick: () -> Unit) {
    val connected = person.sessions > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !person.isMain, onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (connected) OnlineGreen else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                ),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (person.isMain) "${person.name} (ты)" else person.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = personDetails(person),
                style = MaterialTheme.typography.bodySmall,
                color = if (!person.enabled) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private fun personDetails(person: PanelPerson): String {
    val parts = mutableListOf<String>()
    parts += if (person.sessions > 0) "подключён" else "не подключён"
    if (!person.isMain) {
        parts += when {
            person.expiresAt > 0L && accessDaysLeft(person.expiresAt) <= 0 -> "срок закончился"
            !person.enabled -> "выключен"
            person.expiresAt > 0L -> accessText(accessDaysLeft(person.expiresAt)).replaceFirstChar { it.lowercase() }
            else -> "без срока"
        }
    }
    val traffic = person.downBytes.coerceAtLeast(0L) + person.upBytes.coerceAtLeast(0L)
    if (traffic > 0L) parts += "трафик ${formatTraffic(traffic)}"
    return parts.joinToString(" · ")
}

private fun formatTraffic(bytes: Long): String {
    val gb = bytes / 1_073_741_824.0
    if (gb >= 1.0) return String.format(java.util.Locale.US, "%.1f ГБ", gb)
    val mb = bytes / 1_048_576.0
    return String.format(java.util.Locale.US, "%.0f МБ", mb)
}

/** What to do with one person, applied on every server where that name exists. */
private enum class PersonAction { EXTEND, DISABLE, ENABLE, DELETE, UNBIND }

@Composable
private fun PersonActionsDialog(
    name: String,
    entries: List<PersonOnServer>,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var daysText by remember { mutableStateOf("30") }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val days = daysText.toIntOrNull()
    val anyEnabled = entries.any { it.person.enabled }

    fun run(action: PersonAction) {
        if (busy || entries.isEmpty()) return
        busy = true
        report = null
        scope.launch {
            val done = mutableListOf<Int>()
            val failed = mutableListOf<String>()
            try {
                for (entry in entries) {
                    try {
                        val api = PanelApi(context, entry.server)
                        when (action) {
                            PersonAction.EXTEND -> {
                                val add = days ?: 0
                                val left = if (entry.person.expiresAt > 0L) {
                                    accessDaysLeft(entry.person.expiresAt).coerceAtLeast(0)
                                } else {
                                    0
                                }
                                // 0 makes the access unlimited; otherwise add to what is left.
                                api.setPersonDays(entry.person, if (add == 0) 0 else (left + add).coerceAtMost(3650))
                            }
                            PersonAction.DISABLE -> api.setPersonEnabled(entry.person, false)
                            PersonAction.ENABLE -> api.setPersonEnabled(entry.person, true)
                            PersonAction.DELETE -> api.deletePerson(entry.person)
                            PersonAction.UNBIND -> api.unbindPerson(entry.person)
                        }
                        done += entry.serverNumber
                    } catch (failure: kotlinx.coroutines.CancellationException) {
                        throw failure
                    } catch (failure: PanelException) {
                        failed += "сервер ${entry.serverNumber}: ${failure.message}"
                    } catch (failure: Exception) {
                        failed += "сервер ${entry.serverNumber}: не удалось выполнить запрос"
                    }
                }
            } finally {
                busy = false
            }
            if (failed.isEmpty()) {
                onDone()
            } else {
                report = (if (done.isNotEmpty()) "Готово на серверах: ${done.joinToString(", ")}. " else "") +
                    "Не получилось: " + failed.joinToString("; ")
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить $name?") },
            text = { Text("Человек будет удалён на всех серверах, его ссылки перестанут работать. Это не отменить.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    run(PersonAction.DELETE)
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(name, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Есть на серверах: " + entries.joinToString(", ") { it.serverNumber.toString() },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = daysText,
                    onValueChange = { value -> daysText = value.filter(Char::isDigit).take(3) },
                    label = { Text("На сколько дней продлить (0 — без срока)") },
                    singleLine = true,
                    enabled = !busy,
                    isError = days == null || days !in 0..999,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { run(PersonAction.EXTEND) },
                    enabled = !busy && days != null && days in 0..999,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (days == 0) "Сделать без срока" else "Продлить на ${days ?: 0} дн.")
                }
                OutlinedButton(
                    onClick = { run(PersonAction.UNBIND) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Отвязать телефон")
                }
                Text(
                    text = "Если Маяк переустановили или человек сменил телефон, сервер его не пускает. " +
                        "После отвязки первый подключившийся телефон станет его.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { run(if (anyEnabled) PersonAction.DISABLE else PersonAction.ENABLE) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (anyEnabled) "Выключить на всех серверах" else "Включить на всех серверах")
                }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Удалить на всех серверах", color = MaterialTheme.colorScheme.error)
                }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Меняю на серверах…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                report?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Закрыть") }
        },
    )
}
