// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

/** Mayak Admin: the server field takes an IPv4 address, a domain or an IPv6 address. */
object PeerHost {
    private val IPV6 = Regex("^\\[?[0-9a-fA-F:.]+]?$")
    private val IPV6_WITH_PORT = Regex("^\\[[0-9a-fA-F:.]+]:[0-9]{1,5}$")

    /** "2605:e440:29::9b" or "[2605:e440:29::9b]" — an IPv6 address without a port. */
    fun isIpv6(text: String): Boolean {
        val value = text.trim()
        return IPV6.matches(value) && value.count { it == ':' } >= 2 && !value.contains("]:")
    }

    /** What the server field accepts: a host without a port, or an IPv6 address. */
    fun isValidInput(text: String): Boolean {
        val value = text.trim()
        return value.isNotBlank() &&
            (!value.contains(':') || isIpv6(value) || IPV6_WITH_PORT.matches(value))
    }

    /** "host:port" for the native client; IPv6 goes in brackets, a full "[v6]:port" stays as it is. */
    fun withPort(text: String, port: Int): String {
        val value = text.trim()
        return when {
            isIpv6(value) -> "[${value.trim('[', ']')}]:$port"
            value.contains(':') -> value
            else -> "$value:$port"
        }
    }
}
