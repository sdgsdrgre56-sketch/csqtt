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

private const val MAYAK_REPO_WEB = "https://github.com/sdgsdrgre56-sketch/csqtt"

/** Direct GitHub link to this edition's APK of [release] (opens as a download in a browser). */
internal fun githubApkUrl(release: AppReleaseInfo): String {
    val n = buildNumberOf(release.versionTag) ?: 0
    val file = if (MayakConfig.ADMIN) "MayakAdmin-$n-arm64.apk" else "Mayak-$n-universal.apk"
    return "$MAYAK_REPO_WEB/releases/download/build-$n/$file"
}

/** GitHub page of [release] with its download links. */
internal fun githubReleasePage(release: AppReleaseInfo): String =
    "$MAYAK_REPO_WEB/releases/tag/build-${buildNumberOf(release.versionTag) ?: 0}"

/**
 * Opens [url] in a browser. A browser goes through Mayak's own tunnel (Mayak itself does not), so
 * on mobile whitelists the APK still comes, at the tunnel's speed. Returns false if nothing opened.
 */
internal fun openInBrowser(context: Context, url: String): Boolean = runCatching {
    context.startActivity(
        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    true
}.getOrDefault(false)

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
