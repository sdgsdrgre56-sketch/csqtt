// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak Admin: who is connected right now, on every configured server.

package com.csqtt.client.ui

import androidx.compose.foundation.background
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
                    results += try {
                        val api = PanelApi(
                            context,
                            PanelServer(profile.host, profile.webPort, profile.webLogin, profile.webPassword),
                        )
                        ServerPeople(number, api.listPeople(), null)
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
                    servers.forEach { server -> ServerSection(server) }
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
private fun ServerSection(server: ServerPeople) {
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
            .forEach { person -> PersonRow(person) }
    }
}

@Composable
private fun PersonRow(person: PanelPerson) {
    val connected = person.sessions > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
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
