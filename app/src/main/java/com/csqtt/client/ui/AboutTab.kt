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
internal fun AboutTab() {
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
