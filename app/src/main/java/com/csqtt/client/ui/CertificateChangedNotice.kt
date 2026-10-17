// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak Admin: when a server panel shows a different certificate than before, say which server it is
// and let the admin trust the new one right there (it changes after a server is reinstalled).

package com.csqtt.client.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.csqtt.client.MayakConfig
import com.csqtt.client.PanelApi
import com.csqtt.client.SettingsStore

/**
 * Shows nothing while every panel certificate is the remembered one. Otherwise names the servers
 * whose certificate changed and offers one button to trust the new certificates; [onTrusted] then
 * lets the dialog repeat what failed.
 */
@Composable
internal fun CertificateChangedNotice(
    settingsStore: SettingsStore,
    enabled: Boolean = true,
    onTrusted: () -> Unit = {},
) {
    val context = LocalContext.current
    val changed by PanelApi.changedCertificates.collectAsState()
    if (changed.isEmpty()) return
    // "host:port" -> server number, from the panel settings of each server.
    val numbers by produceState(initialValue = emptyMap<String, Int>(), changed) {
        value = runCatching { settingsStore.adminPanelProfiles(MayakConfig.ADMIN_SERVERS) }.getOrDefault(emptyList())
            .filter { it.host.isNotBlank() }
            .associate { "${it.host}:${it.webPort}" to it.index + 1 }
    }
    val servers = changed.mapNotNull { numbers[it] }.sorted()
    val title = when {
        servers.isEmpty() -> "У панели сервера сменился сертификат"
        servers.size == 1 -> "У сервера ${servers[0]} сменился сертификат панели"
        else -> "У серверов ${servers.joinToString(", ")} сменился сертификат панели"
    }
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = "Так бывает после переустановки сервера или CSQTT. Если ты его переустанавливал, " +
                "разреши новый сертификат. Если нет — сначала проверь сервер.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
        OutlinedButton(
            onClick = {
                PanelApi.forgetCertificates(context, changed)
                onTrusted()
            },
            enabled = enabled,
        ) {
            Text("Доверять новому сертификату", fontWeight = FontWeight.SemiBold)
        }
    }
}
