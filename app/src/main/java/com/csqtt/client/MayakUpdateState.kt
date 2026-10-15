// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: a quiet "update available" state instead of a full-screen window.

package com.csqtt.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The newest version found by the update checks, when it is newer than the installed one.
 * Shown as a blinking dot on "Инфо", a small note on the main screen and an "Обновить" button;
 * the person updates when it suits them, on Wi-Fi.
 */
internal object MayakUpdateState {
    private val state = MutableStateFlow<AppReleaseInfo?>(null)
    val available: StateFlow<AppReleaseInfo?> = state

    fun offer(release: AppReleaseInfo?) {
        if (release == null) return
        state.value = release.takeIf { isMayakUpdate(it) }
    }

    private const val PREFS = "mayak_update"
    private const val BANNER_CLOSED = "banner_closed_version"

    fun bannerClosed(context: Context, release: AppReleaseInfo): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(BANNER_CLOSED, "") == release.versionTag

    fun closeBanner(context: Context, release: AppReleaseInfo) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(BANNER_CLOSED, release.versionTag).apply()
    }
}

/** True when the phone has Wi-Fi (or a cable): updates are downloaded only then. */
@Suppress("DEPRECATION")
internal fun isOnWifi(context: Context): Boolean {
    val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
    return runCatching {
        cm.allNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }.getOrDefault(false)
}
