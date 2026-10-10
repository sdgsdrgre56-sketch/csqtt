// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: a simplified noncommercial build based on CSQTT (Copyright 2026 amurcanov).

package com.csqtt.client.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.csqtt.client.BuildConfig
import com.csqtt.client.MayakConfig
import com.csqtt.client.ui.components.CsqttScreen

private val RequiredNotices = listOf(
    "Required Notice: Copyright 2026 amurcanov.",
    "Required Notice: Commercial use of CSQTT requires a separate written license from the licensor.",
    "Required Notice: Redistributions and derivative works must retain attribution to the original CSQTT project and these notices.",
)

@Composable
internal fun AboutTab() {
    val context = LocalContext.current

    fun openUrl(url: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (error: Exception) {
            Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
        }
    }

    CsqttScreen {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .fillMaxWidth()
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppSectionCard(
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = MayakConfig.APP_NAME,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Версия ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AppSectionCard(
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Как пользоваться",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "1. Скопируйте ссылку подключения, которую вам прислали.\n" +
                        "2. Откройте вкладку «Ссылка» и нажмите «Вставить ссылку из буфера».\n" +
                        "3. Вернитесь на первую вкладку и нажмите большую кнопку.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Во вкладке «Исключ.» можно выбрать приложения, которые будут работать мимо туннеля.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AppSectionCard(
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Авторство и лицензия",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "«${MayakConfig.APP_NAME}» — изменённая некоммерческая сборка открытого проекта " +
                        "${MayakConfig.UPSTREAM_NAME}, автор оригинала — amurcanov. Приложение распространяется " +
                        "бесплатно по лицензии PolyForm Noncommercial 1.0.0. Продажа и другое коммерческое " +
                        "использование запрещены.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                RequiredNotices.forEach { notice ->
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { openUrl(MayakConfig.UPSTREAM_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Оригинальный проект ${MayakConfig.UPSTREAM_NAME}")
                }
                OutlinedButton(
                    onClick = { openUrl(MayakConfig.LICENSE_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Текст лицензии")
                }
            }
        }
    }
}
