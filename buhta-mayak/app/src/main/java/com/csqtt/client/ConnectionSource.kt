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

    if (false && store.csqttLinkMode.first()) {
        val link = parseCsqttLink(store.csqttLink.first()) ?: return null
        val linkHashes = activeHashes(link.hashes.joinToString(","))
        return ConnectionSource(
            peer = link.peerAddress(),
            password = link.password,
            hashes = linkHashes.ifEmpty { activeHashes(store.vkHashes.first()) },
            hashesFromLink = linkHashes.isNotEmpty(),
        )
    }

    // Бухта: сервер находится через комнату Saby, поэтому адрес и пароль не нужны.
    val room = store.vkHashes.first().trim()
    if (BuhtaRoom.parse(room) == null) return null
    return ConnectionSource(
        peer = store.peer.first().ifBlank { "buhta" },
        password = "buhta",
        hashes = room,
        hashesFromLink = false,
    )
}
