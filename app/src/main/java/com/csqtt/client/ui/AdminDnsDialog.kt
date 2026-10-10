// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak Admin: change the server DNS through the web panel API.

package com.csqtt.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.CsqttConstants
import com.csqtt.client.DeploySettingsSnapshot
import com.csqtt.client.PanelApi
import com.csqtt.client.PanelDns
import com.csqtt.client.PanelException
import com.csqtt.client.PanelServer
import com.csqtt.client.SettingsStore
import kotlinx.coroutines.launch

private data class DnsPreset(
    val title: String,
    val primary: String,
    val secondary: String,
    /** Id of a provider built into the server, or null to send plain addresses. */
    val providerId: String? = null,
)

private val DnsPresets = listOf(
    DnsPreset("Yandex", "77.88.8.8", "77.88.8.1", "yandex"),
    DnsPreset("Cloudflare", "1.1.1.1", "1.0.0.1", "cloudflare"),
    DnsPreset("Google", "8.8.8.8", "8.8.4.4", "google"),
    DnsPreset("AdGuard (без рекламы)", "94.140.14.14", "94.140.15.14", "adguard"),
    DnsPreset("Xbox DNS", "111.88.96.50", "111.88.96.51", "xbox"),
    DnsPreset("Comms DNS", "83.220.169.155", "212.109.195.93", "comms"),
    DnsPreset("Geohide", "95.182.120.241", "37.230.192.51", "geohide"),
    DnsPreset("Quad9", "9.9.9.9", "149.112.112.112", "quad9"),
    DnsPreset("OpenDNS", "208.67.222.222", "208.67.220.220", "opendns"),
    DnsPreset("Ростелеком", "95.189.32.74", "195.208.5.1", "rostelecom"),
    DnsPreset("NextDNS", "45.90.28.181", "45.90.30.181", "nextdns"),
    DnsPreset("BI.ZONE", "195.208.6.1", "195.208.7.1", "bizone"),
    DnsPreset("НСДИ", "195.208.4.1", "195.208.5.1", "nsdi"),
    DnsPreset("Yandex Безопасный", "77.88.8.88", "77.88.8.2"),
    DnsPreset("Yandex Семейный", "77.88.8.7", "77.88.8.3"),
    DnsPreset("Cloudflare без вредоносных", "1.1.1.2", "1.0.0.2"),
    DnsPreset("Cloudflare Семейный", "1.1.1.3", "1.0.0.3"),
    DnsPreset("AdGuard Семейный", "94.140.14.15", "94.140.15.16"),
    DnsPreset("AdGuard без фильтров", "94.140.14.140", "94.140.14.141"),
    DnsPreset("Control D", "76.76.2.0", "76.76.10.0"),
    DnsPreset("CleanBrowsing", "185.228.168.9", "185.228.169.9"),
    DnsPreset("DNS.SB", "185.222.222.222", "45.11.45.11"),
    DnsPreset("Level3", "4.2.2.1", "4.2.2.2"),
    DnsPreset("Comodo Secure", "8.26.56.26", "8.20.247.20"),
    DnsPreset("Verisign", "64.6.64.6", "64.6.65.6"),
)

private fun isIpv4(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { part ->
        part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toInt() in 0..255
    }
}

private fun matchesPreset(preset: DnsPreset, dns: PanelDns?): Boolean =
    dns != null && preset.primary == dns.primary && preset.secondary == dns.secondary

/**
 * Lets the owner pick one of the DNS presets or enter custom addresses.
 * The server applies the change to connected clients without a restart.
 */
@Composable
internal fun AdminDnsDialog(
    settingsStore: SettingsStore,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val deploy by settingsStore.deploySettingsSnapshot.collectAsStateWithLifecycle(
        initialValue = DeploySettingsSnapshot(),
    )

    var current by remember { mutableStateOf<PanelDns?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var customPrimary by remember { mutableStateOf("") }
    var customSecondary by remember { mutableStateOf("") }

    fun api(): PanelApi = PanelApi(
        context,
        PanelServer(
            host = deploy.ip.trim(),
            port = if (deploy.manualPortsEnabled) {
                deploy.serverWebPort
            } else {
                CsqttConstants.Network.DEFAULT_SERVER_WEB_PORT
            },
            user = deploy.webLogin,
            password = deploy.webPassword,
        ),
    )

    fun perform(successNotice: String?, action: suspend (PanelApi) -> PanelDns) {
        if (busy) return
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                current = action(api())
                notice = successNotice
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (failure: PanelException) {
                error = failure.message
            } catch (failure: Exception) {
                error = "Не удалось выполнить запрос к серверу"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(deploy.isLoaded, deploy.profile) {
        if (deploy.isLoaded) {
            current = null
            perform(successNotice = null) { it.getDns() }
        }
    }

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
                    text = "DNS сервера ${deploy.profile.coerceAtLeast(0) + 1}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Меняется на сервере сразу для всех подключённых, без перезапуска.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        text = when {
                            busy -> "Связываюсь с сервером…"
                            current != null -> "Сейчас: ${current?.primary}, ${current?.secondary}"
                            else -> "Текущий DNS неизвестен"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    if (message == PanelApi.CERTIFICATE_CHANGED) {
                        Text(
                            text = "Так бывает после переустановки сервера. Если вы его переустанавливали, " +
                                "разрешите новый сертификат.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = {
                                api().forgetCertificate()
                                perform(successNotice = null) { it.getDns() }
                            },
                        ) {
                            Text("Доверять новому сертификату")
                        }
                    }
                }
                notice?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    DnsPresets.forEach { preset ->
                        val selected = matchesPreset(preset, current)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    enabled = !busy && !selected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        perform(successNotice = "Применено: ${preset.title}") { panel ->
                                            panel.setDns(preset.providerId, preset.primary, preset.secondary)
                                        }
                                    },
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = preset.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                )
                                Text(
                                    text = "${preset.primary} · ${preset.secondary}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    Text(
                        text = "Свои DNS",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = customPrimary,
                        onValueChange = { value -> customPrimary = value.filter { it.isDigit() || it == '.' }.take(15) },
                        label = { Text("Основной, например 1.1.1.1") },
                        singleLine = true,
                        isError = customPrimary.isNotEmpty() && !isIpv4(customPrimary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        value = customSecondary,
                        onValueChange = { value -> customSecondary = value.filter { it.isDigit() || it == '.' }.take(15) },
                        label = { Text("Запасной, можно оставить пустым") },
                        singleLine = true,
                        isError = customSecondary.isNotEmpty() && !isIpv4(customSecondary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    Button(
                        onClick = {
                            perform(successNotice = "Применены свои DNS") { panel ->
                                panel.setDns(null, customPrimary, customSecondary)
                            }
                        },
                        enabled = !busy && isIpv4(customPrimary) &&
                            (customSecondary.isEmpty() || isIpv4(customSecondary)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .height(48.dp),
                    ) {
                        Text("Применить свои DNS", fontWeight = FontWeight.SemiBold)
                    }
                }

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .height(48.dp),
                ) {
                    Text("Закрыть")
                }
            }
        }
    }
}
