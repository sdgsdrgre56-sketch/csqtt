// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: a "check the connection" window for both editions.

package com.csqtt.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.csqtt.client.MayakConfig
import com.csqtt.client.SettingsStore
import com.csqtt.client.TunnelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** One line of the check: [ok] null means "nothing to say yet". */
private data class CheckLine(
    val title: String,
    val ok: Boolean?,
    val detail: String,
)

/** Milliseconds a direct TCP connection took, or null when it failed. */
private suspend fun tcpConnectMillis(host: String, port: Int, timeoutMs: Int = 5000): Long? =
    withContext(Dispatchers.IO) {
        runCatching {
            val startedAt = System.nanoTime()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            (System.nanoTime() - startedAt) / 1_000_000L
        }.getOrNull()
    }

private suspend fun runMayakCheck(
    settingsStore: SettingsStore?,
    hashNotice: String?,
    hashesKnown: Boolean,
): List<CheckLine> {
    val lines = mutableListOf<CheckLine>()

    // The app itself is kept outside its own tunnel, so these two go straight
    // through the phone's network — exactly the path the tunnel needs.
    val vkMillis = tcpConnectMillis("api.vk.com", 443)
    val webMillis = if (vkMillis != null) vkMillis else tcpConnectMillis("ya.ru", 443)
    lines += if (webMillis != null) {
        CheckLine("Интернет на телефоне", true, "есть")
    } else {
        CheckLine("Интернет на телефоне", false, "нет связи — проверьте мобильный интернет или Wi-Fi")
    }
    lines += when {
        vkMillis != null -> CheckLine("VK", true, "отвечает за $vkMillis мс")
        webMillis != null -> CheckLine("VK", false, "не отвечает, хотя интернет есть")
        else -> CheckLine("VK", null, "проверим, когда появится интернет")
    }

    if (hashesKnown) {
        lines += if (hashNotice == null) {
            CheckLine("Хэш VK", true, "в порядке")
        } else {
            CheckLine("Хэш VK", false, hashNotice)
        }
    }

    val running = TunnelManager.running.value
    val workers = TunnelManager.activeWorkers.value
    lines += when {
        !running -> CheckLine("Туннель", null, "выключен")
        workers > 0 -> CheckLine("Туннель", true, "работает, потоков: $workers")
        else -> CheckLine("Туннель", false, "включён, но потоков пока нет")
    }

    if (MayakConfig.ADMIN && settingsStore != null) {
        val profiles = runCatching { settingsStore.adminPanelProfiles(MayakConfig.ADMIN_SERVERS) }
            .getOrDefault(emptyList())
        for (profile in profiles) {
            val title = "Сервер ${profile.index + 1}"
            if (profile.host.isBlank()) {
                lines += CheckLine(title, null, "адрес не задан")
                continue
            }
            val millis = tcpConnectMillis(profile.host, profile.webPort)
            lines += if (millis != null) {
                CheckLine(title, true, "панель отвечает за $millis мс")
            } else {
                CheckLine(title, false, "напрямую не отвечает. В мобильной сети это бывает — проверьте через Wi-Fi")
            }
        }
    } else {
        lines += when {
            running && workers > 0 -> CheckLine("Сервер", true, "отвечает")
            running -> CheckLine("Сервер", false, "пока не ответил")
            else -> CheckLine("Сервер", null, "узнаем, когда маяк будет включён")
        }
    }
    return lines
}

/**
 * A small text button that opens the connection check.
 *
 * @param hashNotice what is wrong with the VK hash, or null when it is fine.
 * @param hashesKnown false while hash settings are loading or not relevant.
 */
@Composable
internal fun MayakConnectionCheckButton(
    settingsStore: SettingsStore?,
    hashNotice: String?,
    hashesKnown: Boolean = true,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { showDialog = true }) {
        Text("Проверка связи", fontWeight = FontWeight.SemiBold)
    }
    if (showDialog) {
        MayakCheckDialog(
            settingsStore = settingsStore,
            hashNotice = hashNotice,
            hashesKnown = hashesKnown,
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun MayakCheckDialog(
    settingsStore: SettingsStore?,
    hashNotice: String?,
    hashesKnown: Boolean,
    onDismiss: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var lines by remember { mutableStateOf<List<CheckLine>?>(null) }

    LaunchedEffect(attempt) {
        lines = null
        lines = runMayakCheck(settingsStore, hashNotice, hashesKnown)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Проверка связи", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val current = lines
                if (current == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Проверяем…", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    current.forEach { line ->
                        val mark = when (line.ok) {
                            true -> "✓"
                            false -> "✕"
                            null -> "•"
                        }
                        val markColor = when (line.ok) {
                            true -> MaterialTheme.colorScheme.primary
                            false -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Row {
                            Text(
                                text = mark,
                                color = markColor,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.width(24.dp),
                            )
                            Column {
                                Text(
                                    text = line.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = line.detail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { attempt += 1 }, enabled = lines != null) {
                Text("Проверить ещё раз", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
    )
}
