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
import android.content.Context
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.CsqttConstants
import com.csqtt.client.DeploySettingsSnapshot
import com.csqtt.client.MayakConfig
import com.csqtt.client.PanelApi
import com.csqtt.client.PanelDns
import com.csqtt.client.PanelException
import com.csqtt.client.PanelServer
import com.csqtt.client.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

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
    // From the owner's DNS Jumper list (only the ones that answered; ISP-only and private ones left out).
    DnsPreset("DNS 4565", "46.8.158.6", "45.155.204.190"),
    DnsPreset("US - NextDNS", "45.90.28.230", "45.90.30.230"),
    DnsPreset("DNS 4568", "37.230.192.51", "193.233.112.88"),
    DnsPreset("DNS 194.67.2.114", "194.67.1.154", "194.67.2.114"),
    DnsPreset("DNS 195.208.4.1", "195.208.4.1", "194.147.49.16"),
    DnsPreset("US - UltraDNS", "204.69.234.1", "204.74.101.1"),
    DnsPreset("US - Neustar 1", "156.154.71.1", "156.154.70.1"),
    DnsPreset("US - Comodo", "156.154.71.22", "156.154.70.22"),
    DnsPreset("DNS 4564", "193.233.112.88", "193.233.112.68"),
    DnsPreset("US - Neustar 2", "156.154.71.5", "156.154.70.5"),
    DnsPreset("US - OpenDNS 2", "208.67.220.222", "208.67.222.220"),
    DnsPreset("DNS 2", "195.46.39.40", "195.46.39.39"),
    DnsPreset("DNS 119", "130.61.69.123", ""),
    DnsPreset("DNS 120", "130.61.64.122", ""),
    DnsPreset("US - Quad9 No Security", "9.9.9.10", "149.112.112.10"),
    DnsPreset("DE - DNS WATCH", "84.200.70.40", "84.200.69.80"),
    DnsPreset("DNS 103", "94.247.43.254", ""),
    DnsPreset("DNS 112", "195.10.195.195", ""),
    DnsPreset("DNS 102", "134.195.4.2", ""),
    DnsPreset("DNSPod (DNS 95)", "119.29.29.29", "182.254.116.116"),
    DnsPreset("DNS 124", "116.203.104.203", ""),
    DnsPreset("DNS 104", "88.198.92.222", ""),
    DnsPreset("RU - AdGuard", "94.140.14.14", "94.140.15.15"),
    DnsPreset("DNS 113", "194.36.144.87", ""),
    DnsPreset("DNS 7", "216.146.35.35", "216.146.36.36"),
    DnsPreset("DNS 126", "178.63.116.152", ""),
    DnsPreset("DNS 90", "51.77.149.139", ""),
    DnsPreset("DNS 105", "81.169.136.222", ""),
    DnsPreset("DNS 88", "95.216.99.249", ""),
    DnsPreset("DNS 110", "217.160.70.42", ""),
    DnsPreset("DNS 114", "193.31.24.55", ""),
    DnsPreset("GB - Orange DNS", "195.92.195.94", "195.92.195.95"),
    DnsPreset("DNS 111", "213.202.211.221", ""),
    DnsPreset("DNS 89", "65.21.1.106", ""),
    DnsPreset("US - Level 3 - D", "4.2.2.5", "4.2.2.6"),
    DnsPreset("DNS 91", "51.158.108.203", ""),
    DnsPreset("US - Level 3 - C", "4.2.2.4", "4.2.2.3"),
    DnsPreset("DNS 109", "217.91.179.72", ""),
    DnsPreset("GB - Zen Internet", "212.23.8.1", "212.23.3.1"),
    DnsPreset("DNS 116", "185.189.58.124", ""),
    DnsPreset("DNS 106", "80.153.146.105", ""),
    DnsPreset("DNS 107", "80.152.203.134", ""),
    DnsPreset("NL - Freenom World", "80.80.81.81", "80.80.80.80"),
    DnsPreset("DNS 93", "192.71.166.92", ""),
    DnsPreset("US - Qwest", "205.171.3.65", "205.171.2.65"),
    DnsPreset("DNS 127", "45.79.189.158", ""),
    DnsPreset("DNS 98", "185.116.236.101", ""),
    DnsPreset("DNS 100", "168.235.111.72", ""),
    DnsPreset("DNS 97", "204.12.237.197", ""),
    DnsPreset("DNS 101", "147.93.130.20", ""),
    DnsPreset("DNS 99", "173.208.212.205", ""),
    DnsPreset("DNS 125", "161.97.219.84", ""),
    DnsPreset("DNS 122", "112.213.35.91", ""),
    DnsPreset("DNS 121", "168.138.12.137", ""),
    DnsPreset("DNS 8", "74.82.42.42", ""),
)

private fun isIpv4(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { part ->
        part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toInt() in 0..255
    }
}

/** The admin's own DNS entries, kept on this phone so they don't have to be typed again. */
private object SavedDns {
    private const val PREFS = "mayak_admin_dns"
    private const val KEY = "saved"

    fun load(context: Context): List<DnsPreset> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty()
            .lines()
            .mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size != 3 || !isIpv4(parts[1])) return@mapNotNull null
                DnsPreset(parts[0], parts[1], parts[2])
            }

    fun save(context: Context, list: List<DnsPreset>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, list.joinToString("\n") { "${it.title}|${it.primary}|${it.secondary}" })
            .apply()
    }
}

/**
 * Asks [ip] for the address of example.com over plain DNS (UDP 53) and returns the best answer
 * time of three tries in milliseconds, or null if it never answered. Measured from the phone.
 */
private suspend fun dnsPingMs(ip: String): Long? = withContext(Dispatchers.IO) {
    val query = byteArrayOf(
        0x4d, 0x59, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
        'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
        3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0,
        0x00, 0x01, 0x00, 0x01,
    )
    var best: Long? = null
    runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout = 1_500
            val address = InetAddress.getByName(ip)
            repeat(3) {
                runCatching {
                    val started = System.nanoTime()
                    socket.send(DatagramPacket(query, query.size, address, 53))
                    val buffer = ByteArray(512)
                    val answer = DatagramPacket(buffer, buffer.size)
                    socket.receive(answer)
                    if (answer.length >= 2 && buffer[0] == query[0] && buffer[1] == query[1]) {
                        val ms = (System.nanoTime() - started) / 1_000_000
                        best = best?.coerceAtMost(ms) ?: ms
                    }
                }
            }
        }
    }
    best
}

private fun pingLabel(pings: Map<String, Long?>, ip: String): String? =
    if (!pings.containsKey(ip)) null else pings[ip]?.let { "$it мс" } ?: "нет ответа"

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
    var customName by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(SavedDns.load(context)) }
    var pings by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    var pinging by remember { mutableStateOf(false) }

    fun checkPings() {
        if (pinging) return
        pinging = true
        pings = emptyMap()
        scope.launch {
            try {
                val ips = (saved + DnsPresets).map { it.primary }.distinct()
                pings = ips.map { ip -> async { ip to dnsPingMs(ip) } }.awaitAll().toMap()
            } finally {
                pinging = false
            }
        }
    }

    /** Adds the typed DNS to «Мои DNS» (same addresses replace the old entry). */
    fun saveCustom(): DnsPreset {
        val title = customName.trim().replace("|", " ").ifEmpty { customPrimary }.take(40)
        val entry = DnsPreset(title, customPrimary, customSecondary)
        saved = saved.filterNot { it.primary == entry.primary && it.secondary == entry.secondary } + entry
        SavedDns.save(context, saved)
        return entry
    }
    // Mayak: apply to every configured server at once (on by default).
    var allServers by remember { mutableStateOf(true) }

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

    /** Applies DNS on the selected server, or on every configured server when [allServers] is on. */
    fun applyDns(title: String, providerId: String?, primary: String, secondary: String) {
        if (!allServers) {
            perform(successNotice = "Применено: $title") { panel -> panel.setDns(providerId, primary, secondary) }
            return
        }
        if (busy) return
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                val done = mutableListOf<Int>()
                val failed = mutableListOf<String>()
                val profiles = settingsStore.adminPanelProfiles(MayakConfig.ADMIN_SERVERS)
                for (profile in profiles) {
                    if (!profile.isConfigured) continue
                    val number = profile.index + 1
                    try {
                        val result = PanelApi(
                            context,
                            PanelServer(profile.host, profile.webPort, profile.webLogin, profile.webPassword),
                        ).setDns(providerId, primary, secondary)
                        done += number
                        if (profile.index == deploy.profile) current = result
                    } catch (failure: kotlinx.coroutines.CancellationException) {
                        throw failure
                    } catch (failure: PanelException) {
                        failed += "сервер $number: ${failure.message}"
                    } catch (failure: Exception) {
                        failed += "сервер $number: не удалось выполнить запрос"
                    }
                }
                notice = when {
                    done.isEmpty() && failed.isEmpty() -> null
                    done.isEmpty() -> null
                    else -> "Применено «$title» на серверах: ${done.joinToString(", ")}"
                }
                error = when {
                    done.isEmpty() && failed.isEmpty() -> "Нет настроенных серверов"
                    failed.isEmpty() -> null
                    else -> "Не получилось: " + failed.joinToString("; ")
                }
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
                    text = if (allServers) "DNS серверов" else "DNS сервера ${deploy.profile.coerceAtLeast(0) + 1}",
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy, role = Role.Checkbox) { allServers = !allServers }
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.Checkbox(checked = allServers, onCheckedChange = null)
                    Text(
                        text = "Менять на всех серверах сразу",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }

                OutlinedButton(
                    onClick = { checkPings() },
                    enabled = !pinging,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                        .height(44.dp),
                ) {
                    if (pinging) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("  Проверяю пинг…")
                    } else {
                        Text("Проверить пинг DNS")
                    }
                }
                if (pings.isNotEmpty()) {
                    Text(
                        text = "Пинг измерен с телефона, мимо VPN. С серверов он может быть другим.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (saved.isNotEmpty()) {
                        Text(
                            text = "Мои DNS",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        saved.forEach { preset ->
                            val selected = matchesPreset(preset, current)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        enabled = !busy && (allServers || !selected),
                                        role = Role.RadioButton,
                                        onClick = { applyDns(preset.title, null, preset.primary, preset.secondary) },
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
                                        text = (listOf(preset.primary, preset.secondary).filter { it.isNotEmpty() } +
                                            listOfNotNull(pingLabel(pings, preset.primary))).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    enabled = !busy,
                                    onClick = {
                                        saved = saved - preset
                                        SavedDns.save(context, saved)
                                    },
                                ) { Text("Удалить") }
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }

                    DnsPresets.forEach { preset ->
                        val selected = matchesPreset(preset, current)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    enabled = !busy && (allServers || !selected),
                                    role = Role.RadioButton,
                                    onClick = {
                                        applyDns(preset.title, preset.providerId, preset.primary, preset.secondary)
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
                                    text = listOfNotNull(preset.primary, preset.secondary.ifEmpty { null }, pingLabel(pings, preset.primary))
                                        .joinToString(" · "),
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
                        value = customName,
                        onValueChange = { value -> customName = value.replace("|", "").replace("\n", "").take(40) },
                        label = { Text("Название, можно оставить пустым") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
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
                    val customValid = isIpv4(customPrimary) &&
                        (customSecondary.isEmpty() || isIpv4(customSecondary))
                    Button(
                        onClick = {
                            val entry = saveCustom()
                            applyDns(entry.title, null, entry.primary, entry.secondary)
                        },
                        enabled = !busy && customValid,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .height(48.dp),
                    ) {
                        Text("Применить и сохранить в «Мои DNS»", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = {
                            val entry = saveCustom()
                            notice = "Сохранено в «Мои DNS»: ${entry.title}"
                            customName = ""
                            customPrimary = ""
                            customSecondary = ""
                        },
                        enabled = !busy && customValid,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .height(48.dp),
                    ) {
                        Text("Только сохранить")
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
