// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: quiet update notices — a blinking dot, a small note on the main screen, an update card.

package com.csqtt.client.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.csqtt.client.AppReleaseInfo
import com.csqtt.client.MayakUpdateState
import com.csqtt.client.buildNumberOf
import com.csqtt.client.githubApkUrl
import com.csqtt.client.githubReleasePage
import com.csqtt.client.isOnWifi
import com.csqtt.client.openInBrowser
import androidx.compose.material3.OutlinedButton

private fun versionName(release: AppReleaseInfo): String = "1.0.${buildNumberOf(release.versionTag) ?: "?"}"

/** A small blinking dot: there is a new version. */
@Composable
internal fun MayakUpdateDot(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "update-dot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "update-dot-alpha",
    )
    Box(
        modifier = modifier
            .size(9.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    )
}

/** Main screen: a small note about the new version, can be closed; tapping opens "Инфо". */
@Composable
internal fun MayakUpdateBanner(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val release by MayakUpdateState.available.collectAsState()
    var closedTick by remember { mutableStateOf(0) }
    val current = release ?: return
    if (closedTick >= 0 && MayakUpdateState.bannerClosed(context, current)) return
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpen() },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        ) {
            MayakUpdateDot()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Text(
                    text = "Есть обновление ${versionName(current)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = current.notes.firstOrNull()?.let { "$it · обновить во «Инфо» по Wi-Fi" }
                        ?: "Обновить можно во вкладке «Инфо», по Wi-Fi",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = {
                MayakUpdateState.closeBanner(context, current)
                closedTick++
            }) {
                Icon(Icons.Filled.Close, contentDescription = "Закрыть", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** "Инфо": what is new and the "Обновить" button, which works only on Wi-Fi. */
@Composable
internal fun MayakUpdateCard(onUpdate: (AppReleaseInfo) -> Unit) {
    val context = LocalContext.current
    val release by MayakUpdateState.available.collectAsState()
    val current = release ?: return
    var hint by remember { mutableStateOf<String?>(null) }
    var siteHint by remember { mutableStateOf<String?>(null) }
    var siteTried by remember { mutableStateOf(false) }
    AppSectionCard(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MayakUpdateDot()
            Text(
                text = "Доступно обновление ${versionName(current)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        if (current.notes.isNotEmpty()) {
            Text("Что нового:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            current.notes.forEach { line ->
                Text("• $line", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(
            onClick = {
                if (isOnWifi(context)) {
                    hint = null
                    onUpdate(current)
                } else {
                    hint = "Обновление скачивается только по Wi-Fi. Подключись к Wi-Fi и нажми ещё раз."
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Обновить", fontWeight = FontWeight.SemiBold)
        }
        Text(
            text = hint ?: "Скачивается только по Wi-Fi, когда тебе удобно.",
            style = MaterialTheme.typography.bodySmall,
            color = if (hint != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Any network: a browser goes through whatever VPN is on (Mayak's tunnel or another one).
        OutlinedButton(
            onClick = {
                // First press: the file itself (the browser starts the download by itself).
                // Next presses: the version page, where the file can be tapped by hand.
                val opened = if (!siteTried) {
                    openInBrowser(context, githubApkUrl(current))
                } else {
                    openInBrowser(context, githubReleasePage(current))
                }
                siteHint = when {
                    !opened -> "Браузер не открылся."
                    !siteTried -> "Браузер качает файл. Когда скачается, нажми на него, чтобы установить. " +
                        "Если загрузка не началась, нажми ещё раз — откроется страница версии."
                    else -> "На странице версии нажми на файл ${
                        if (com.csqtt.client.MayakConfig.ADMIN) "MayakAdmin" else "Mayak"
                    }-…apk."
                }
                siteTried = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Скачать через сайт", fontWeight = FontWeight.SemiBold)
        }
        Text(
            text = siteHint ?: "На любой сети: откроется браузер и сам скачает файл через включённый VPN.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
