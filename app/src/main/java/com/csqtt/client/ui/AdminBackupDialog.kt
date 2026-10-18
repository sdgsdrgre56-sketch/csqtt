// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak Admin: save all settings to a password-locked file and restore them from it.

package com.csqtt.client.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.csqtt.client.AdminBackup
import com.csqtt.client.BackupException
import com.csqtt.client.SettingsStore
import com.csqtt.client.TunnelManager
import kotlinx.coroutines.launch

@Composable
internal fun AdminBackupDialog(settingsStore: SettingsStore, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tunnelRunning by TunnelManager.running.collectAsState()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }

    fun run(block: suspend () -> String) {
        busy = true
        result = null
        scope.launch {
            try {
                result = block()
                isError = false
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (failure: BackupException) {
                result = failure.message
                isError = true
            } catch (failure: Exception) {
                result = "Не получилось: ${failure.message ?: "ошибка"}"
                isError = true
            } finally {
                busy = false
            }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            run {
                val servers = AdminBackup.save(context, settingsStore, uri, password)
                "Сохранено. Серверов в копии: $servers. Храни файл и пароль отдельно от этого телефона."
            }
        }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore = uri
    }

    val passwordOk = password.length >= 6

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Резервная копия", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Все настройки Админки в одном файле: серверы и доступ к панелям, подписи, страны, " +
                        "«Мои DNS», токены рассылки. Файл закрыт паролем, без пароля его не открыть.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(64) },
                    label = { Text("Пароль копии (от 6 знаков)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Пароль нигде не сохраняется. Забудешь его — копию не открыть.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                        saveLauncher.launch("mayak-admin-$date.mayak")
                    },
                    enabled = !busy && passwordOk,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("Сохранить копию", fontWeight = FontWeight.SemiBold) }
                OutlinedButton(
                    onClick = { openLauncher.launch(arrayOf("*/*")) },
                    enabled = !busy && passwordOk && !tunnelRunning,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("Восстановить из копии") }
                if (tunnelRunning) {
                    Text(
                        text = "Чтобы восстановить, сначала выключи маяк.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                result?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Закрыть") }
        },
    )

    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Заменить настройки?") },
            text = {
                Text(
                    "Все текущие настройки Админки на этом телефоне заменятся настройками из копии. " +
                        "Отменить это нельзя.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = null
                    run {
                        val servers = AdminBackup.restore(context, settingsStore, uri, password)
                        "Восстановлено. Серверов: $servers. Закрой Админку и открой заново, чтобы всё подхватилось."
                    }
                }) { Text("Заменить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = null }) { Text("Отмена") }
            },
        )
    }
}
