// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

/**
 * Mayak Admin: extra tunnel ports of a server, like in Квазар. On the server they are redirected
 * to the main tunnel port (iptables REDIRECT), and the phone spreads its workers across them, so a
 * hoster limit on UDP flows per source address and port does not cut workers off.
 */
object ExtraPeerPorts {
    const val MAX_PORTS = 31
    private const val RANDOM_MIN = 20000
    private const val RANDOM_MAX = 60000

    /** Parses "46001-46009, 51234" into a sorted list without duplicates; null when invalid. */
    fun parse(text: String): List<Int>? {
        val ports = sortedSetOf<Int>()
        for (part in text.split(',', ';', ' ', '\n', '\t')) {
            val item = part.trim()
            if (item.isEmpty()) continue
            val dash = item.indexOf('-')
            if (dash > 0) {
                val from = item.substring(0, dash).trim().toIntOrNull() ?: return null
                val to = item.substring(dash + 1).trim().toIntOrNull() ?: return null
                if (from !in 1..65535 || to !in from..65535 || to - from > 64) return null
                ports.addAll(from..to)
            } else {
                val port = item.toIntOrNull() ?: return null
                if (port !in 1..65535) return null
                ports.add(port)
            }
        }
        if (ports.size > MAX_PORTS) return null
        return ports.toList()
    }

    fun isValid(text: String): Boolean = text.isBlank() || parse(text) != null

    /** Text for the native client and deploy.sh ("46001,46002,…"), "" when blank or invalid. */
    fun normalize(text: String): String = parse(text)?.joinToString(",").orEmpty()

    /** [count] random high ports, none equal to the ports in [taken]. */
    fun random(count: Int = 9, taken: Set<Int> = emptySet()): String {
        val ports = sortedSetOf<Int>()
        while (ports.size < count) {
            val port = (RANDOM_MIN..RANDOM_MAX).random()
            if (port !in taken) ports.add(port)
        }
        return ports.joinToString(",")
    }
}
