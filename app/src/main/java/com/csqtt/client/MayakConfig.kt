// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: a simplified noncommercial build based on CSQTT (Copyright 2026 amurcanov).

package com.csqtt.client

/**
 * Switches for the simplified "Mayak" build. The app always works in the
 * link ("participant") mode: the user pastes a csqtt:// link and presses one button.
 */
internal object MayakConfig {
    /** Hides owner/server features and advanced tunnel settings. */
    val SIMPLE_UI: Boolean = true

    /** The upstream updater would offer the original CSQTT APK, so it stays off. */
    val UPSTREAM_UPDATE_CHECK: Boolean = false

    /** Speed slider is limited to the points 9, 18 and 27. */
    val MAX_WORKERS: Int = 27

    const val APP_NAME = "Маяк"
    const val UPSTREAM_NAME = "CSQTT"
    const val UPSTREAM_URL = "https://github.com/amurcanov/csqtt"
    const val LICENSE_URL = "https://polyformproject.org/licenses/noncommercial/1.0.0"
}
