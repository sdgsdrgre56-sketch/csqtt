// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

import com.csqtt.client.ui.utils.parseCsqttLink
import com.csqtt.client.ui.utils.peerAddress
import kotlinx.coroutines.flow.first

data class ConnectionSource(
    val peer: String,
    val password: String,
    val hashes: String,
    val hashesFromLink: Boolean,
)

suspend fun resolveConnectionSource(store: SettingsStore): ConnectionSource? {
    val invalidHashes = VkHashValidationCodec.decode(store.vkHashCheckResults.first())
    fun activeHashes(raw: String): String = VkHashValidationCodec.active(
        raw.split(Regex("[,\\s\\n]+")),
        invalidHashes,
    ).joinToString(",")

    if (store.csqttLinkMode.first()) {
        val link = parseCsqttLink(store.csqttLink.first()) ?: return null
        val linkHashes = activeHashes(link.hashes.joinToString(","))
        return ConnectionSource(
            peer = link.peerAddress(),
            password = link.password,
            hashes = linkHashes.ifEmpty { activeHashes(store.vkHashes.first()) },
            hashesFromLink = linkHashes.isNotEmpty(),
        )
    }

    val basePeer = store.peer.first()
    val passwordState = store.connectionPasswordState.first()
    val password = passwordState.value
    if (passwordState.state == StoredSecretState.Unreadable) return null
    if (basePeer.isBlank() || password.isBlank()) return null
    val manualPorts = store.manualPortsEnabled.first()
    val serverPeerPort = if (manualPorts) {
        store.serverPeerPort.first()
    } else {
        CsqttConstants.Network.DEFAULT_SERVER_PEER_PORT
    }
    val mainPeer = PeerHost.withPort(basePeer, serverPeerPort)
    // Mayak Admin: "host:port+46001-46009" — the native client spreads workers across these ports.
    val extraPorts = if (manualPorts) ExtraPeerPorts.normalize(store.extraPeerPorts.first()) else ""
    val peer = if (extraPorts.isEmpty()) mainPeer else "$mainPeer+$extraPorts"
    return ConnectionSource(
        peer = peer,
        password = password,
        hashes = activeHashes(store.vkHashes.first()),
        hashesFromLink = false,
    )
}
