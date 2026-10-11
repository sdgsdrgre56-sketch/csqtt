// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: several connection links (one per server) kept as one setting.

package com.csqtt.client

import com.csqtt.client.ui.utils.parseCsqttLink

/**
 * A person can be given one link per server. They are passed around as one
 * text (lines, a QR code) and stored newline-separated in the link setting.
 */
internal object MayakLinks {
    const val MAX_LINKS = 6

    private val LinkRegex = Regex("csqtt://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)

    /** Valid csqtt:// links found anywhere in [text], in order, without duplicates. */
    fun extract(text: String): List<String> = LinkRegex.findAll(text)
        .map { it.value }
        .filter { parseCsqttLink(it) != null }
        .distinct()
        .take(MAX_LINKS)
        .toList()

    /** Links from pasted or scanned text; also accepts a single link typed with stray spaces. */
    fun fromUserText(text: String): List<String> {
        val found = extract(text)
        if (found.isNotEmpty()) return found
        val compact = text.filterNot(Char::isWhitespace)
        return if (parseCsqttLink(compact) != null) listOf(compact) else emptyList()
    }

    fun split(stored: String): List<String> = stored
        .split('\n')
        .map(String::trim)
        .filter(String::isNotEmpty)

    fun join(links: List<String>): String = links.joinToString("\n")
}
