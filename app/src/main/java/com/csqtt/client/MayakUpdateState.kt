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

    // Mayak: the person can turn update notices off; the newest version found is still remembered,
    // so turning them back on shows it at once with everything that is new since the installed one.
    private val lastFound = MutableStateFlow<AppReleaseInfo?>(null)
    private val mutedState = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = mutedState
    private const val MUTED = "notices_off"

    /** Reads the person's choice; called once when the app starts. */
    fun init(context: Context) {
        mutedState.value = !MayakConfig.ADMIN && context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(MUTED, false)
        if (mutedState.value) state.value = null
    }

    /** Turns update notices off or back on. Back on, the waiting update (if any) shows right away. */
    fun setMuted(context: Context, muted: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(MUTED, muted).apply()
        mutedState.value = muted
        state.value = if (muted) null else lastFound.value
    }

    /** True when a newer version is known, even while notices are off. */
    fun hasWaiting(): Boolean = lastFound.value != null

    /**
     * A version found by a check. Shown unless notices are off; [force] is a check the person asked
     * for themselves ("Проверить обновления"), which always shows its result.
     */
    fun offer(release: AppReleaseInfo?, force: Boolean = false) {
        if (release == null) return
        val newer = release.takeIf { isMayakUpdate(it) }
        lastFound.value = newer
        if (!mutedState.value || force) state.value = newer
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
