// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: a simplified noncommercial build based on CSQTT (Copyright 2026 amurcanov).

package com.csqtt.client.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import android.widget.Toast
import android.net.Uri
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.MayakSpeedPhase
import com.csqtt.client.TunnelManager
import androidx.compose.material3.LinearProgressIndicator
import java.util.Locale

internal enum class MayakBeaconState { Off, Connecting, Connected, Waiting }

private object MayakPalette {
    val Yellow = Color(0xFFFFD54A)
    val YellowSoft = Color(0xFFFFE28A)
    val Red = Color(0xFFE5484D)
    val White = Color(0xFFF5F7FA)
    val Steel = Color(0xFFC9D3E0)
    val Disc = Color(0xFF12294B)
    val DiscLit = Color(0xFF16345F)
    val DiscBorder = Color(0xFF2A416A)
    val DiscBorderWarm = Color(0xFF8C7A3A)
    val OffTower = Color(0xFFC5CEDB)
    val OffStripe = Color(0xFF6F7F99)
    val OffBase = Color(0xFF8FA0BA)
    val OffLantern = Color(0xFF46597A)
}

private fun polygon(unit: Float, vararg points: Float): Path = Path().apply {
    moveTo(points[0] * unit, points[1] * unit)
    var index = 2
    while (index < points.size) {
        lineTo(points[index] * unit, points[index + 1] * unit)
        index += 2
    }
    close()
}

/** Draws the lighthouse on a 108x108 grid scaled to the canvas. */
private fun DrawScope.drawLighthouse(
    lit: Boolean,
    beamScale: Float,
    beamAlpha: Float,
    lanternAlpha: Float,
) {
    val u = size.minDimension / 108f
    val lantern = Offset(54f * u, 36f * u)

    if (beamAlpha > 0f) {
        scale(scaleX = beamScale, scaleY = 1f, pivot = lantern) {
            drawPath(polygon(u, 48f, 36f, 8f, 23f, 8f, 49f), MayakPalette.Yellow.copy(alpha = 0.6f * beamAlpha))
            drawPath(polygon(u, 60f, 36f, 100f, 23f, 100f, 49f), MayakPalette.Yellow.copy(alpha = 0.6f * beamAlpha))
        }
        drawCircle(
            color = MayakPalette.Yellow.copy(alpha = 0.28f * beamAlpha),
            radius = 13f * u,
            center = lantern,
        )
    }

    val base = if (lit) MayakPalette.Steel else MayakPalette.OffBase
    val tower = if (lit) MayakPalette.White else MayakPalette.OffTower
    val stripe = if (lit) MayakPalette.Red else MayakPalette.OffStripe

    drawPath(polygon(u, 37f, 80f, 71f, 80f, 71f, 84f, 37f, 84f), base)
    drawPath(polygon(u, 46f, 44f, 62f, 44f, 67f, 80f, 41f, 80f), tower)
    drawPath(polygon(u, 44.89f, 52f, 63.11f, 52f, 64.08f, 59f, 43.92f, 59f), stripe)
    drawPath(polygon(u, 42.94f, 66f, 65.06f, 66f, 66.03f, 73f, 41.97f, 73f), stripe)
    drawPath(polygon(u, 43f, 41f, 65f, 41f, 65f, 44f, 43f, 44f), base)
    drawPath(polygon(u, 48f, 31f, 60f, 31f, 60f, 41f, 48f, 41f), MayakPalette.OffLantern)
    if (lanternAlpha > 0f) {
        drawPath(
            polygon(u, 48f, 31f, 60f, 31f, 60f, 41f, 48f, 41f),
            MayakPalette.Yellow.copy(alpha = lanternAlpha.coerceIn(0f, 1f)),
        )
    }
    drawPath(polygon(u, 46f, 31f, 62f, 31f, 54f, 23f), stripe)
}

@Composable
internal fun MayakBeaconButton(
    state: MayakBeaconState,
    enabled: Boolean,
    actionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "mayak_beacon")
    val sweep by transition.animateFloat(
        initialValue = 1f,
        targetValue = -1f,
        animationSpec = infiniteRepeatable(tween(2500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "mayak_sweep",
    )
    val blink by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "mayak_blink",
    )
    val ring by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "mayak_ring",
    )

    val connected = state == MayakBeaconState.Connected
    val connecting = state == MayakBeaconState.Connecting
    val lit = connected || connecting
    val borderColor = when (state) {
        MayakBeaconState.Connected -> MayakPalette.Yellow
        MayakBeaconState.Connecting -> MayakPalette.DiscBorderWarm
        else -> MayakPalette.DiscBorder
    }

    Box(modifier = modifier.size(248.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val discRadius = size.minDimension * (108f / 248f)
            if (connected) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(MayakPalette.Yellow.copy(alpha = 0.30f), Color.Transparent),
                        center = center,
                        radius = size.minDimension / 2f,
                    ),
                    radius = size.minDimension / 2f,
                    center = center,
                )
            }
            if (connecting) {
                drawCircle(
                    color = MayakPalette.Yellow.copy(alpha = 0.55f * (1f - ring)),
                    radius = discRadius * (0.96f + 0.16f * ring),
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(216.dp)
                .clip(CircleShape)
                .background(if (connected) MayakPalette.DiscLit else MayakPalette.Disc)
                .border(2.dp, borderColor, CircleShape)
                .clickable(
                    enabled = enabled,
                    onClickLabel = actionLabel,
                    role = Role.Button,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(172.dp)) {
                drawLighthouse(
                    lit = lit,
                    beamScale = if (connected) sweep else 1f,
                    beamAlpha = when {
                        connected -> 1f
                        connecting -> 0.75f * blink
                        else -> 0f
                    },
                    lanternAlpha = when {
                        connected -> 1f
                        connecting -> blink
                        else -> 0f
                    },
                )
            }
        }
    }
}

private fun formatMayakUptime(seconds: Long?): String {
    val total = seconds?.coerceAtLeast(0L) ?: 0L
    return String.format(Locale.US, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
}

@Composable
private fun MayakUptime() {
    val uptimeSeconds by TunnelManager.uptimeSeconds.collectAsStateWithLifecycle()
    Text(
        text = formatMayakUptime(uptimeSeconds),
        style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

private fun formatMayakRate(bitsPerSecond: Long): String = when {
    bitsPerSecond >= 1_000_000L -> String.format(Locale.US, "%.1f Мбит/с", bitsPerSecond / 1_000_000.0)
    bitsPerSecond >= 1_000L -> String.format(Locale.US, "%d Кбит/с", bitsPerSecond / 1_000L)
    else -> "0 Кбит/с"
}

private fun formatMayakVolume(bytes: Long): String {
    val megabytes = bytes / (1024.0 * 1024.0)
    return if (megabytes >= 1024.0) {
        String.format(Locale.US, "%.2f ГБ", megabytes / 1024.0)
    } else {
        String.format(Locale.US, "%.0f МБ", megabytes)
    }
}

/**
 * A quiet "check speed" button. The app itself is excluded from its own tunnel,
 * so the measurement is done by the native client, which downloads a test file
 * through the tunnel by itself (rust-client/speedtest.rs) and reports the speed.
 */
@Composable
internal fun MayakSpeedCheckButton() {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { showDialog = true }) {
        Text("Замер скорости", fontWeight = FontWeight.SemiBold)
    }
    if (showDialog) {
        MayakSpeedDialog(onDismiss = { showDialog = false })
    }
}

// Fallback when the built-in test cannot run: a speed test page in the browser.
private const val MayakSpeedUrl = "https://speed.cloudflare.com/"

private fun openMayakSpeedPage(context: android.content.Context, url: String) {
    TunnelManager.resetSpeedPeaks()
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (error: Exception) {
        Toast.makeText(context, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun MayakSpeedDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val test by TunnelManager.speedTest.collectAsStateWithLifecycle()
    val busy = test.phase == MayakSpeedPhase.Starting || test.phase == MayakSpeedPhase.Running

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Замер скорости", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (test.phase) {
                    MayakSpeedPhase.Idle -> Text(
                        text = "Маяк скачает проверочный файл через туннель и покажет скорость. " +
                            "Замер идёт около 10 секунд и тратит до 100 МБ трафика.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    MayakSpeedPhase.Starting -> {
                        Text(
                            text = "Соединяемся с сервером замера…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    MayakSpeedPhase.Running -> {
                        Text(
                            text = "Идёт замер",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatMayakMbps(test.mbps),
                            style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        LinearProgressIndicator(
                            progress = { test.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    MayakSpeedPhase.Done -> {
                        Text(
                            text = "Скорость загрузки",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatMayakMbps(test.mbps),
                            style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "Максимум: ${formatMayakMbps(test.peakMbps)}\n" +
                                "Отклик: ${test.pingMs} мс\n" +
                                "Скачано: ${formatMayakVolume(test.bytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MayakSpeedPhase.Error -> {
                        Text(
                            text = "Замер не получился",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = test.message,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { openMayakSpeedPage(context, MayakSpeedUrl) }) {
                            Text("Открыть замер в браузере")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { TunnelManager.startSpeedTest() },
                enabled = !busy,
            ) {
                Text(
                    text = when (test.phase) {
                        MayakSpeedPhase.Idle -> "Начать замер"
                        MayakSpeedPhase.Starting, MayakSpeedPhase.Running -> "Идёт замер…"
                        MayakSpeedPhase.Done, MayakSpeedPhase.Error -> "Ещё раз"
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
    )
}

private fun formatMayakMbps(mbps: Double): String =
    if (mbps >= 100.0) {
        String.format(Locale.US, "%.0f Мбит/с", mbps)
    } else {
        String.format(Locale.US, "%.1f Мбит/с", mbps)
    }

private val MayakSpeedSteps = listOf(9 to "Эконом", 18 to "Обычно", 27 to "Быстро", 45 to "Макс")

@Composable
private fun MayakSpeedSelector(
    workers: Int,
    maxWorkers: Int,
    enabled: Boolean,
    onWorkersChange: (Int) -> Unit,
) {
    val effectiveWorkers = workers.coerceAtMost(maxWorkers)
    val selectedStep = when {
        effectiveWorkers <= 9 -> 9
        effectiveWorkers <= 18 -> 18
        effectiveWorkers <= 27 -> 27
        else -> 45
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (enabled) "Скорость" else "Скорость · меняется при выключенном маяке",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MayakSpeedSteps.forEach { (value, label) ->
                val isSelected = value == selectedStep
                val stepAvailable = value <= maxWorkers
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .semantics { selected = isSelected }
                        .clickable(
                            enabled = enabled && stepAvailable && !isSelected,
                            role = Role.RadioButton,
                            onClick = { onWorkersChange(value) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (enabled && stepAvailable) 0.86f else 0.4f,
                            )
                        },
                    )
                }
            }
        }
        if (maxWorkers < 45) {
            Text(
                text = "«Макс» доступен, когда добавлено два хэша",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MayakWelcome(
    onPasteLink: () -> Unit,
    onScanQr: () -> Unit,
    onEnterManually: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(MayakPalette.Disc)
                    .border(2.dp, MayakPalette.DiscBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(126.dp)) {
                    drawLighthouse(lit = false, beamScale = 1f, beamAlpha = 0f, lanternAlpha = 0f)
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Вставьте ссылку подключения",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Скопируйте ссылку, которую вам прислали, и нажмите кнопку ниже.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 300.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onPasteLink,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Вставить из буфера", fontWeight = FontWeight.ExtraBold)
            }
            OutlinedButton(
                onClick = onScanQr,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Сканировать QR-код", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onEnterManually) {
                Text("Ввести вручную", fontWeight = FontWeight.SemiBold)
            }
            Text(
                text = "Ссылка начинается с csqtt://",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Main screen of the simplified build: a welcome prompt until a link is saved,
 * then the lighthouse button with status, uptime and three speed steps.
 *
 * @param linkReady null while settings are still loading.
 */
@Composable
internal fun MayakHome(
    linkReady: Boolean?,
    tunnelRunning: Boolean,
    tunnelStarting: Boolean,
    tunnelStopping: Boolean,
    autoPausedForWifi: Boolean,
    cooldownActive: Boolean,
    configurationValid: Boolean,
    workers: Int,
    maxWorkers: Int,
    serverPosition: Int,
    serverCount: Int,
    hashNotice: String?,
    hashesKnown: Boolean,
    onToggleTunnel: () -> Unit,
    onWorkersChange: (Int) -> Unit,
    onPasteLink: () -> Unit,
    onScanQr: () -> Unit,
    onEnterManually: () -> Unit,
) {
    if (linkReady == null) {
        Box(modifier = Modifier.fillMaxSize())
        return
    }
    val isStarting = tunnelStarting && !tunnelRunning && !tunnelStopping
    val active = tunnelRunning || isStarting || autoPausedForWifi || tunnelStopping
    if (!linkReady && !active) {
        MayakWelcome(onPasteLink = onPasteLink, onScanQr = onScanQr, onEnterManually = onEnterManually)
        return
    }

    val canToggle = !tunnelStopping &&
        (tunnelRunning || isStarting || autoPausedForWifi || !cooldownActive || !configurationValid)
    val state = when {
        autoPausedForWifi || tunnelStopping -> MayakBeaconState.Waiting
        tunnelRunning -> MayakBeaconState.Connected
        isStarting -> MayakBeaconState.Connecting
        else -> MayakBeaconState.Off
    }
    val title = when {
        autoPausedForWifi -> "Пауза: вы в Wi-Fi"
        tunnelStopping -> "Отключение…"
        tunnelRunning -> "Подключено"
        isStarting -> "Подключение…"
        cooldownActive -> "Подождите…"
        else -> "Отключено"
    }
    val hint = when {
        autoPausedForWifi -> "Туннель включится сам, когда Wi-Fi пропадёт"
        tunnelStopping -> "Ещё пара секунд"
        tunnelRunning -> "Нажмите на маяк, чтобы выключить"
        isStarting -> "Обычно это занимает несколько секунд"
        cooldownActive -> "Маяк можно будет включить через пару секунд"
        else -> "Нажмите на маяк, чтобы включить"
    }
    val titleColor = when (state) {
        MayakBeaconState.Connected -> MaterialTheme.colorScheme.primary
        MayakBeaconState.Connecting -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
        else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            MayakBeaconButton(
                state = state,
                enabled = canToggle,
                actionLabel = if (tunnelRunning || isStarting || autoPausedForWifi) "Выключить" else "Включить",
                onClick = onToggleTunnel,
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = titleColor,
                    textAlign = TextAlign.Center,
                )
                if (tunnelRunning || autoPausedForWifi) {
                    MayakUptime()
                }
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.Center) {
                    if (tunnelRunning) {
                        MayakSpeedCheckButton()
                    }
                    MayakConnectionCheckButton(
                        settingsStore = null,
                        hashNotice = hashNotice,
                        hashesKnown = hashesKnown,
                    )
                }
                if (serverCount > 1 && serverPosition > 0) {
                    Text(
                        text = "Сервер $serverPosition из $serverCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        if (hashNotice != null && !active) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = hashNotice,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                TextButton(onClick = onEnterManually) {
                    Text("Открыть настройки", fontWeight = FontWeight.Bold)
                }
            }
        }
        Box(modifier = Modifier.padding(bottom = 12.dp)) {
            MayakSpeedSelector(
                workers = workers,
                maxWorkers = maxWorkers,
                enabled = !active,
                onWorkersChange = onWorkersChange,
            )
        }
    }
}
