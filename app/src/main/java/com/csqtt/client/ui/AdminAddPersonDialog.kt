// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak Admin: create a person on every configured server and hand over one QR code.

package com.csqtt.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.csqtt.client.MayakConfig
import com.csqtt.client.PanelApi
import com.csqtt.client.PanelException
import com.csqtt.client.PanelServer
import com.csqtt.client.SettingsStore
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.launch

private data class ServerOutcome(
    val serverNumber: Int,
    val link: String?,
    val message: String,
)

private fun encodeQr(text: String): BitMatrix? = runCatching {
    QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
        ),
    )
}.getOrNull()

@Composable
private fun QrImage(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { encodeQr(text) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        if (matrix == null) {
            Text("Не удалось построить QR-код", color = Color.Black)
        } else {
            Canvas(modifier = Modifier.size(280.dp)) {
                val cells = matrix.width
                val cell = size.minDimension / cells
                for (y in 0 until matrix.height) {
                    for (x in 0 until cells) {
                        if (matrix.get(x, y)) {
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(x * cell, y * cell),
                                // A hair wider than a cell, so neighbours leave no light seams.
                                size = Size(cell + 0.5f, cell + 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Creates one person on every configured server profile and shows the
 * resulting links as a single text and QR code for the person's phone.
 */
@Composable
internal fun AdminAddPersonDialog(
    settingsStore: SettingsStore,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var daysText by remember { mutableStateOf("0") }
    var busy by remember { mutableStateOf(false) }
    var outcomes by remember { mutableStateOf<List<ServerOutcome>>(emptyList()) }
    val links = outcomes.mapNotNull { it.link }
    val combined = links.joinToString("\n")
    val days = daysText.toIntOrNull()
    val canCreate = !busy && links.isEmpty() && name.isNotBlank() && days != null && days in 0..3650

    fun create() {
        val personName = name.trim()
        val personDays = days ?: return
        busy = true
        outcomes = emptyList()
        scope.launch {
            val results = mutableListOf<ServerOutcome>()
            try {
                val profiles = settingsStore.adminPanelProfiles(MayakConfig.ADMIN_SERVERS)
                for (profile in profiles) {
                    val number = profile.index + 1
                    if (!profile.isConfigured) {
                        results += ServerOutcome(number, null, "не настроен (нет адреса или доступа к панели)")
                        outcomes = results.toList()
                        continue
                    }
                    val outcome = try {
                        val api = PanelApi(
                            context,
                            PanelServer(profile.host, profile.webPort, profile.webLogin, profile.webPassword),
                        )
                        val client = api.createClient(
                            name = personName,
                            days = personDays,
                            peerPort = profile.peerPort,
                        )
                        ServerOutcome(
                            number,
                            PanelApi.buildLink(profile.host, client.peerPort, client.password, client.expiresAt),
                            "создан",
                        )
                    } catch (failure: kotlinx.coroutines.CancellationException) {
                        throw failure
                    } catch (failure: PanelException) {
                        ServerOutcome(number, null, failure.message ?: "ошибка")
                    } catch (failure: Exception) {
                        ServerOutcome(number, null, "не удалось выполнить запрос")
                    }
                    results += outcome
                    outcomes = results.toList()
                }
            } finally {
                busy = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
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
                Text(
                    text = "Добавить человека",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Человек заводится на всех настроенных серверах сразу, а ссылки собираются в один QR-код.",
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
                    OutlinedTextField(
                        value = name,
                        onValueChange = { value -> name = value.take(40) },
                        label = { Text("Имя, например Мама") },
                        singleLine = true,
                        enabled = links.isEmpty() && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = daysText,
                        onValueChange = { value -> daysText = value.filter(Char::isDigit).take(4) },
                        label = { Text("На сколько дней (0 — без срока)") },
                        singleLine = true,
                        enabled = links.isEmpty() && !busy,
                        isError = days == null || days !in 0..3650,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { create() },
                        enabled = canCreate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Создать на всех серверах", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    outcomes.forEach { outcome ->
                        Text(
                            text = "Сервер ${outcome.serverNumber}: ${outcome.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (outcome.link != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    if (links.isNotEmpty() && !busy) {
                        Text(
                            text = if (links.size > 1) {
                                "Готово. В этом коде серверов: ${links.size}. Отсканируйте его в «Маяке» на телефоне человека."
                            } else {
                                "Готово. Отсканируйте код в «Маяке» на телефоне человека."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        QrImage(
                            text = combined,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(vertical = 4.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("Маяк", combined))
                                    Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                            ) {
                                Text("Скопировать")
                            }
                            OutlinedButton(
                                onClick = {
                                    val send = Intent(Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, combined)
                                    runCatching {
                                        context.startActivity(
                                            Intent.createChooser(send, "Отправить ссылку")
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                            ) {
                                Text("Отправить")
                            }
                        }
                        Text(
                            text = "Ссылка даёт доступ к серверам. Показывайте и отправляйте её только этому человеку.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .height(48.dp),
                ) {
                    Text("Закрыть")
                }
            }
        }
    }
}
