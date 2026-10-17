// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak Admin: announcements and server lists for people's phones.

package com.csqtt.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.csqtt.client.MayakConfig
import com.csqtt.client.MayakSync
import com.csqtt.client.MayakSyncAdmin
import com.csqtt.client.SettingsStore
import com.csqtt.client.SyncException
import com.csqtt.client.ui.components.CsqttSegmentedControl
import kotlinx.coroutines.launch

@Composable
internal fun AdminChannelDialog(
    settingsStore: SettingsStore,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var hasToken by remember { mutableStateOf(MayakSyncAdmin.hasToken(context)) }
    var tokenText by remember { mutableStateOf("") }
    var hasYandex by remember { mutableStateOf(MayakSyncAdmin.hasYandex(context)) }
    var yandexTokenText by remember { mutableStateOf("") }
    val canPublish = hasToken || hasYandex
    var announcement by remember { mutableStateOf("") }
    var targetServer by remember { mutableStateOf(MayakConfig.ADMIN_SERVERS - 1) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultIsError by remember { mutableStateOf(false) }

    fun launchTask(block: suspend () -> String) {
        if (busy) return
        busy = true
        result = null
        scope.launch {
            try {
                result = block()
                resultIsError = false
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (failure: SyncException) {
                result = failure.message
                resultIsError = true
            } catch (failure: Exception) {
                result = "Не получилось: ${failure.message ?: "ошибка"}"
                resultIsError = true
            } finally {
                busy = false
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Связь с людьми", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    text = "Объявления и списки серверов доходят до Маяка у людей сами, без новых ссылок. " +
                        "Маяк проверяет их при каждом включении.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // --- token ---
                    Text("Доступ к GitHub", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (hasToken) {
                            "Токен сохранён."
                        } else {
                            "Нужен токен GitHub с доступом только к репозиторию ${MayakSync.REPO} " +
                                "(Contents: Read and write). Он хранится на телефоне в зашифрованном виде."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = tokenText,
                        onValueChange = { tokenText = it.trim().take(300) },
                        label = { Text(if (hasToken) "Новый токен (если нужно заменить)" else "Токен GitHub") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                MayakSyncAdmin.saveToken(context, tokenText)
                                hasToken = MayakSyncAdmin.hasToken(context)
                                tokenText = ""
                                result = if (hasToken) "Токен сохранён" else "Токен удалён"
                                resultIsError = false
                            },
                            enabled = !busy && tokenText.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) { Text("Сохранить") }
                        if (hasToken) {
                            OutlinedButton(
                                onClick = {
                                    MayakSyncAdmin.saveToken(context, "")
                                    hasToken = false
                                    result = "Токен удалён"
                                    resultIsError = false
                                },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) { Text("Удалить") }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // --- Yandex Disk ---
                    Text("Яндекс Диск (для белых списков)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (hasYandex) {
                            "Токен сохранён. Объявления и списки пишутся ещё и в папку mayak на Яндекс Диске, " +
                                "их Маяк найдёт даже на мобильном с белыми списками."
                        } else {
                            "Нужен токен Яндекс Диска (тот же, что в секрете YANDEX_TOKEN на GitHub). " +
                                "Хранится на телефоне в зашифрованном виде."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = yandexTokenText,
                        onValueChange = { yandexTokenText = it.trim().take(300) },
                        label = { Text(if (hasYandex) "Новый токен Яндекса (если нужно заменить)" else "Токен Яндекс Диска") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                MayakSyncAdmin.saveYandex(context, yandexTokenText)
                                hasYandex = MayakSyncAdmin.hasYandex(context)
                                yandexTokenText = ""
                                result = if (hasYandex) "Токен Яндекса сохранён" else "Токен Яндекса удалён"
                                resultIsError = false
                            },
                            enabled = !busy && yandexTokenText.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) { Text("Сохранить") }
                        if (hasYandex) {
                            OutlinedButton(
                                onClick = {
                                    MayakSyncAdmin.saveYandex(context, "")
                                    hasYandex = false
                                    result = "Токен Яндекса удалён"
                                    resultIsError = false
                                },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) { Text("Удалить") }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // --- announcement ---
                    Text("Объявление", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = announcement,
                        onValueChange = { announcement = it.take(500) },
                        label = { Text("Например: сервер 2 на ремонте до вечера") },
                        enabled = !busy && canPublish,
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                val text = announcement
                                launchTask { MayakSyncAdmin.publishAnnouncement(context, text) }
                            },
                            enabled = !busy && canPublish && announcement.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) { Text("Отправить") }
                        OutlinedButton(
                            onClick = {
                                launchTask { MayakSyncAdmin.publishAnnouncement(context, "") }
                            },
                            enabled = !busy && canPublish,
                            modifier = Modifier.weight(1f),
                        ) { Text("Убрать") }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // --- server lists ---
                    Text("Списки серверов", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "Собирает людей со всех серверов по имени и отправляет каждому его свежий список. " +
                            "Имена людей должны быть разными.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            launchTask {
                                val r = MayakSyncAdmin.publishPeople(context, settingsStore)
                                buildString {
                                    append("Людей: ${r.people}, обновлено списков: ${r.changedFiles}.")
                                    if (r.problems.isNotEmpty()) append(" Проблемы: ${r.problems.joinToString("; ")}")
                                }
                            }
                        },
                        enabled = !busy && canPublish,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Разослать списки серверов") }

                    Text(
                        text = "Новый сервер: настрой его в Админке, выбери номер и нажми кнопку. " +
                            "Все люди будут заведены на нём с тем же сроком, и он появится у них сам.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    com.csqtt.client.ui.components.ServerNumberPicker(
                        count = MayakConfig.ADMIN_SERVERS,
                        flags = com.csqtt.client.ui.components.rememberServerFlags(settingsStore, MayakConfig.ADMIN_SERVERS),
                        selected = targetServer,
                        onSelected = { targetServer = it },
                        enabled = !busy,
                    )
                    Button(
                        onClick = {
                            val target = targetServer
                            launchTask { MayakSyncAdmin.addServerForAll(context, settingsStore, target) }
                        },
                        enabled = !busy && canPublish,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Добавить сервер ${targetServer + 1} всем") }

                    // Note about the selected server, shown to people while they are connected to it.
                    var serverNote by remember { mutableStateOf("") }
                    androidx.compose.runtime.LaunchedEffect(targetServer) {
                        serverNote = runCatching { settingsStore.serverNote(targetServer) }.getOrDefault("")
                    }
                    OutlinedTextField(
                        value = serverNote,
                        onValueChange = { serverNote = it.take(60) },
                        label = { Text("Подпись сервера ${targetServer + 1} для людей") },
                        placeholder = { Text("Например: YouTube без рекламы") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = {
                            val target = targetServer
                            val note = serverNote.trim()
                            launchTask {
                                settingsStore.saveServerNote(target, note)
                                val r = MayakSyncAdmin.publishPeople(context, settingsStore)
                                buildString {
                                    append(if (note.isEmpty()) "Подпись сервера ${target + 1} убрана." else "Подпись сервера ${target + 1} сохранена.")
                                    append(" Людей: ${r.people}, обновлено списков: ${r.changedFiles}.")
                                    if (r.problems.isNotEmpty()) append(" Проблемы: ${r.problems.joinToString("; ")}.")
                                }
                            }
                        },
                        enabled = !busy && canPublish,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Сохранить подпись и разослать") }

                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Работаю…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    result?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (resultIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    CertificateChangedNotice(settingsStore, enabled = !busy)
                }

                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .height(48.dp),
                ) { Text("Закрыть") }
            }
        }
    }
}
