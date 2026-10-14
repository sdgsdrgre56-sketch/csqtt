// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: "Обновляюсь…" window with a progress bar, and the "updated to version N" note after it.

package com.csqtt.client.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.csqtt.client.AppReleaseInfo
import com.csqtt.client.BuildConfig
import com.csqtt.client.MayakConfig
import com.csqtt.client.buildNumberOf
import com.csqtt.client.downloadAndInstall

/**
 * Downloads and installs [release] while showing what is going on. Closes itself once the Android
 * installer is open; on a problem it says what happened and offers to try again.
 */
@Composable
internal fun MayakUpdateProgressDialog(
    release: AppReleaseInfo,
    onClose: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    var attempt by remember { mutableIntStateOf(0) }
    var downloaded by remember { mutableLongStateOf(0L) }
    var total by remember { mutableLongStateOf(0L) }
    var problem by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(true) }
    val version = buildNumberOf(release.versionTag)?.let { "1.0.$it" } ?: release.versionTag

    LaunchedEffect(attempt) {
        running = true
        problem = null
        downloaded = 0L
        total = 0L
        val result = downloadAndInstall(context, release) { done, size ->
            downloaded = done
            total = size
        }
        running = false
        if (result == null) onClose() else problem = result
    }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(if (problem == null) "Обновляюсь… 🤣" else "Не получилось обновиться", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Антоха опять что-то придумал или сломал. Ставлю версию $version.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (problem == null) {
                    val fraction = if (total > 0L) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
                    if (fraction == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    } else {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                    Text(
                        text = when {
                            downloaded == 0L -> "Подготовка…"
                            total > 0L -> "Скачано ${mb(downloaded)} из ${mb(total)} МБ · ${(fraction!! * 100).toInt()}%"
                            else -> "Скачано ${mb(downloaded)} МБ"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(problem.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (!running) {
                TextButton(onClick = { attempt += 1 }) { Text("Повторить", fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = {
            if (!running) {
                TextButton(onClick = onClose) { Text("Закрыть") }
            }
        },
    )
}

private fun mb(bytes: Long): String = String.format(java.util.Locale.US, "%.1f", bytes / 1_048_576.0)

/**
 * After the app was updated, shows once which version is installed now.
 */
@Composable
internal fun MayakUpdatedNotice() {
    val context = LocalContext.current.applicationContext
    var shownVersion by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("mayak_update", Context.MODE_PRIVATE)
        val previous = prefs.getInt("last_version_code", 0)
        val current = BuildConfig.VERSION_CODE
        if (previous != current) prefs.edit().putInt("last_version_code", current).apply()
        if (previous in 1 until current) shownVersion = BuildConfig.VERSION_NAME
    }
    shownVersion?.let { version ->
        AlertDialog(
            onDismissRequest = { shownVersion = null },
            title = { Text("Готово! 🎉", fontWeight = FontWeight.Bold) },
            text = { Text("${MayakConfig.APP_NAME} обновлён. Сейчас стоит версия $version.") },
            confirmButton = { TextButton(onClick = { shownVersion = null }) { Text("Отлично") } },
        )
    }
}
