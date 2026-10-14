// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: a simplified noncommercial build based on CSQTT (Copyright 2026 amurcanov).

package com.csqtt.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.csqtt.client.SettingsStore
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.Button
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.BuildConfig
import com.csqtt.client.MayakConfig
import com.csqtt.client.ui.components.CsqttScreen

private val RequiredNotices = listOf(
    "Required Notice: Copyright 2026 amurcanov.",
    "Required Notice: Commercial use of CSQTT requires a separate written license from the licensor.",
    "Required Notice: Redistributions and derivative works must retain attribution to the original CSQTT project and these notices.",
)

@Composable
internal fun AboutTab(settingsStore: SettingsStore? = null) {
    var showDnsDialog by remember { mutableStateOf(false) }
    if (showDnsDialog && settingsStore != null) {
        AdminDnsDialog(settingsStore = settingsStore, onDismiss = { showDnsDialog = false })
    }
    var showPeopleDialog by remember { mutableStateOf(false) }
    if (showPeopleDialog && settingsStore != null) {
        AdminPeopleDialog(settingsStore = settingsStore, onDismiss = { showPeopleDialog = false })
    }
    var showAddPersonDialog by remember { mutableStateOf(false) }
    if (showAddPersonDialog && settingsStore != null) {
        AdminAddPersonDialog(settingsStore = settingsStore, onDismiss = { showAddPersonDialog = false })
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

            if (MayakConfig.ADMIN && settingsStore != null) {
                AppSectionCard(
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Управление сервером",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Адрес и доступ к панели берутся из вкладки «Деплой» каждого сервера.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { showAddPersonDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Добавить человека", fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        text = "Заводит человека на всех серверах сразу и показывает один QR-код.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { showPeopleDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Text("Кто подключён", fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        text = "Показывает людей на всех серверах, кто сейчас подключён и сколько дней осталось.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "DNS меняется на сервере, выбранном на главном экране.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(
                        onClick = { showDnsDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("DNS сервера", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (!MayakConfig.ADMIN) {
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
            }

            // Attribution required by the PolyForm Noncommercial license of the
            // original project. Kept compact, but it must stay readable.
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                val footerStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp)
                val footerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                Text(
                    text = "На основе ${MayakConfig.UPSTREAM_NAME} · ${MayakConfig.UPSTREAM_URL} · " +
                        "PolyForm Noncommercial 1.0.0 · ${MayakConfig.LICENSE_URL}",
                    style = footerStyle,
                    color = footerColor,
                )
                RequiredNotices.forEach { notice ->
                    Text(text = notice, style = footerStyle, color = footerColor)
                }
            }
        }
    }
}
