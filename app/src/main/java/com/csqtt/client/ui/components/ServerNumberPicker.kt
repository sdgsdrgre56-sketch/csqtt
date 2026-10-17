// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: server number buttons, split into rows of five so ten servers fit a phone width,
// with the small flag of the server's country in front of the number when it is known.

package com.csqtt.client.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.csqtt.client.ServerFlags
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
                modifier = Modifier.height(44.dp),
                enabled = enabled,
                // The colour already shows the chosen server; the check mark would not leave room for the flag.
                showCheck = flags.isEmpty(),
            )
        }
    }
}

/** Flags of the admin's servers by index, from the IP in each server's "Деплой" tab. */
@Composable
fun rememberServerFlags(settingsStore: SettingsStore, count: Int): Map<Int, String> {
    val context = LocalContext.current
    val flags by produceState(initialValue = emptyMap<Int, String>(), settingsStore, count) {
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

/** Flags of the servers in a person's link list, by position, from the host in each link. */
@Composable
fun rememberLinkFlags(hosts: List<String>): Map<Int, String> {
    val context = LocalContext.current
    val flags by produceState(initialValue = emptyMap<Int, String>(), hosts) {
        val known = hosts.withIndex().mapNotNull { (index, host) ->
            ServerFlags.cached(context, host)?.let { index to ServerFlags.flagOf(it) }
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
