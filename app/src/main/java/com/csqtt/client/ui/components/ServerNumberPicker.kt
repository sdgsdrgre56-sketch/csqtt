// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: server number buttons, split into rows of five so ten servers fit a phone width,
// with the small flag of the server's country in front of the number when it is known.

package com.csqtt.client.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.csqtt.client.ServerFlags
import kotlinx.coroutines.launch
import com.csqtt.client.SettingsStore

@Composable
fun ServerNumberPicker(
    count: Int,
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    flags: Map<Int, String> = emptyMap(),
    perRow: Int = 5,
    // Mayak Admin: "45 мс" under each server's flag; empty map shows none.
    pings: Map<Int, String> = emptyMap(),
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        (0 until count).chunked(perRow).forEach { row ->
            CsqttSegmentedControl(
                options = row.map { index ->
                    val flag = flags[index].orEmpty()
                    index to if (flag.isEmpty()) "${index + 1}" else "$flag ${index + 1}"
                },
                selected = selected,
                onSelected = onSelected,
                modifier = Modifier.height(if (pings.isEmpty()) 44.dp else 54.dp),
                enabled = enabled,
                // The colour already shows the chosen server; the check mark would not leave room for the flag.
                showCheck = flags.isEmpty(),
                subLabels = if (pings.isEmpty()) emptyMap() else row.associateWith { pings[it].orEmpty() },
            )
        }
    }
}

/** Flags of the admin's servers by index, from the IP in each server's "Деплой" tab. */
@Composable
fun rememberServerFlags(settingsStore: SettingsStore, count: Int): Map<Int, String> {
    val context = LocalContext.current
    val manualVersion by ServerFlags.manualVersion.collectAsState()
    val flags by produceState(initialValue = emptyMap<Int, String>(), settingsStore, count, manualVersion) {
        val hosts = runCatching { settingsStore.adminPanelProfiles(count) }.getOrNull()
            ?.associate { it.index to it.host }
            ?: return@produceState
        // Remembered ones show at once; the rest are looked up one by one.
        val known = hosts.mapNotNull { (index, host) ->
            ServerFlags.cached(context, host)?.let { index to ServerFlags.flagOf(it) }
        }.toMap().toMutableMap()
        value = known.toMap()
        for ((index, host) in hosts) {
            if (index in known || host.isBlank()) continue
            val code = ServerFlags.countryCode(context, host) ?: continue
            known[index] = ServerFlags.flagOf(code)
            value = known.toMap()
        }
    }
    return flags
}

/**
 * Mayak Admin: ping from this phone to every configured server, shown under the flags. It is measured
 * only twice: once when the app opens and once about 10 seconds after each connection, nothing more.
 * The results live for the whole app run, so switching tabs neither loses nor repeats them.
 */
object ServerPings {
    val pings = androidx.compose.runtime.mutableStateMapOf<Int, String>()
    private var openedOnce = false
    private var pingedThisConnection = false

    /** Time to open a connection to the server's panel port, best of two tries; null if it never answered. */
    private suspend fun measure(host: String, port: Int): Long? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        var best: Long? = null
        repeat(2) {
            runCatching {
                java.net.Socket().use { socket ->
                    val started = System.nanoTime()
                    socket.connect(java.net.InetSocketAddress(host.trim(), port), 2_000)
                    val ms = (System.nanoTime() - started) / 1_000_000
                    best = best?.coerceAtMost(ms) ?: ms
                }
            }
        }
        best
    }

    private suspend fun pingAll(settingsStore: SettingsStore, count: Int) {
        val profiles = runCatching { settingsStore.adminPanelProfiles(count) }.getOrNull().orEmpty()
            .filter { it.host.isNotBlank() }
        kotlinx.coroutines.coroutineScope {
            profiles.forEach { profile ->
                // "…" while measuring, so a new round is visible even when the numbers come out the same.
                pings[profile.index] = "…"
                launch {
                    pings[profile.index] = measure(profile.host, profile.webPort)?.let { "$it мс" } ?: "нет"
                }
            }
        }
    }

    /** First time the server buttons are shown in this app run. */
    suspend fun onAppOpened(settingsStore: SettingsStore, count: Int) {
        if (openedOnce) return
        openedOnce = true
        pingAll(settingsStore, count)
    }

    /** Called when the tunnel state changes: ~10 s after a connection comes up, once per connection. */
    suspend fun onTunnelState(settingsStore: SettingsStore, count: Int, running: Boolean) {
        if (!running) {
            pingedThisConnection = false
            return
        }
        if (pingedThisConnection) return
        kotlinx.coroutines.delay(10_000)
        pingedThisConnection = true
        pingAll(settingsStore, count)
    }
}

/** Flags of the servers in a person's link list, by position, from the host in each link. */
@Composable
fun rememberLinkFlags(hosts: List<String>, countries: List<String> = emptyList()): Map<Int, String> {
    val context = LocalContext.current
    val flags by produceState(initialValue = emptyMap<Int, String>(), hosts, countries) {
        // A country the admin set by hand comes inside the link ("&cc=NL") and wins over the IP lookup.
        val known = hosts.withIndex().mapNotNull { (index, host) ->
            val code = countries.getOrNull(index)?.takeIf { ServerFlags.flagOf(it).isNotEmpty() }
                ?: ServerFlags.cached(context, host)
            code?.let { index to ServerFlags.flagOf(it) }
        }.toMap().toMutableMap()
        value = known.toMap()
        for ((index, host) in hosts.withIndex()) {
            if (index in known || host.isBlank()) continue
            val code = ServerFlags.countryCode(context, host) ?: continue
            known[index] = ServerFlags.flagOf(code)
            value = known.toMap()
        }
    }
    return flags
}

/** Countries offered when the admin sets a server's country by hand. */
private val ManualCountries = listOf(
    "NL", "DE", "FI", "SE", "PL", "FR", "GB", "LV", "LT", "EE", "CH", "AT", "CZ", "NO", "DK",
    "IT", "ES", "RO", "BG", "HU", "TR", "KZ", "AM", "GE", "RU", "US", "CA", "JP", "SG", "HK",
)

/**
 * "Стокгольм, Швеция · AEZA GROUP" for the admin server at [index]; nothing while unknown.
 * Tapping it lets the admin set the country by hand when the IP lookup is wrong.
 */
@Composable
fun ServerDetailsLine(settingsStore: SettingsStore, index: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manualVersion by ServerFlags.manualVersion.collectAsState()
    var host by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    val text by produceState(initialValue = "", settingsStore, index, manualVersion) {
        host = runCatching { settingsStore.adminPanelProfiles(index + 1) }.getOrNull()
            ?.firstOrNull { it.index == index }?.host.orEmpty()
        if (host.isBlank()) {
            value = ""
            return@produceState
        }
        val manual = ServerFlags.manual(context, host)
        var details = ServerFlags.cachedDetails(context, host)
        if (details == null) details = ServerFlags.details(context, host)
        value = if (manual != null) {
            // Hand-set country: the city from the IP lookup may be wrong too, so it is left out.
            listOf(ServerFlags.countryName(manual) + " (вручную)", details?.hoster.orEmpty())
                .filter { it.isNotBlank() }.joinToString(" · ")
        } else {
            // Even when the lookup failed, keep a line to tap so the country can still be set by hand.
            details?.text?.takeIf { it.isNotBlank() } ?: "Страна не определена"
        }
    }
    if (text.isNotEmpty()) {
        androidx.compose.material3.Text(
            text = "$text  ✎",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = modifier
                .fillMaxWidth()
                .clickable { choosing = true },
        )
    }
    if (choosing && host.isNotBlank()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { choosing = false },
            title = { androidx.compose.material3.Text("Страна сервера ${index + 1}") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    androidx.compose.material3.Text(
                        text = "Если флаг по IP неверный, выбери страну вручную. " +
                            "Чтобы флаг поменялся и у людей, после этого нажми «Разослать списки серверов».",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    androidx.compose.material3.TextButton(onClick = {
                        ServerFlags.setManual(context, host, null)
                        choosing = false
                    }) { androidx.compose.material3.Text("Авто (по IP)") }
                    ManualCountries.forEach { code ->
                        androidx.compose.material3.TextButton(onClick = {
                            ServerFlags.setManual(context, host, code)
                            choosing = false
                        }) {
                            androidx.compose.material3.Text("${ServerFlags.flagOf(code)}  ${ServerFlags.countryName(code)}")
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { choosing = false }) {
                    androidx.compose.material3.Text("Закрыть")
                }
            },
        )
    }
}
