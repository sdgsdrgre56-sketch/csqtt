// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: a simplified noncommercial build based on CSQTT (Copyright 2026 amurcanov).

package com.csqtt.client

/**
 * Switches for the two "Mayak" editions built from this source tree.
 *
 * - client: always works in the link ("participant") mode with a simplified UI.
 * - admin: the owner's edition with server profiles, deploy and all tunnel settings.
 */
internal object MayakConfig {
    /** True in the owner's "admin" edition. */
    val ADMIN: Boolean = BuildConfig.MAYAK_ADMIN

    /** Hides owner/server features and advanced tunnel settings (client edition). */
    val SIMPLE_UI: Boolean = !ADMIN

    /** Mayak name, header and colours instead of the upstream branding (both editions). */
    val BRANDED: Boolean = true

    /**
     * Mayak checks its own fork for updates (see MayakUpdate.kt) and installs the signed Mayak APK,
     * not the upstream CSQTT one. Turned on so every phone can update itself after a new build.
     */
    val UPSTREAM_UPDATE_CHECK: Boolean = true

    /** Client edition: speed steps are 9, 18, 27 and, with two or more hashes, 45. */
    val MAX_WORKERS: Int = 45

    /** Number of server profiles: "Сервер 1..N" in the admin edition (15), five in the client. */
    val ADMIN_SERVERS: Int = if (ADMIN) 15 else 5

    val APP_NAME: String = if (ADMIN) "Маяк Админ" else "Маяк"
    const val UPSTREAM_NAME = "CSQTT"
    const val UPSTREAM_URL = "https://github.com/amurcanov/csqtt"
    const val LICENSE_URL = "https://polyformproject.org/licenses/noncommercial/1.0.0"
}
