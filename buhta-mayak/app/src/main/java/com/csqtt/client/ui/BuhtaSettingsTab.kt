package com.csqtt.client.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.csqtt.client.BuhtaRoom
import com.csqtt.client.SettingsStore
import com.csqtt.client.ui.components.CsqttScreen
import kotlinx.coroutines.delay

/** Бухта: вместо хэшей ВК — ссылка на комнату Saby Meet или ключ buhta:// из панели сервера. */
@Composable
internal fun BuhtaSettingsTab(settingsStore: SettingsStore) {
    val saved by settingsStore.vkHashes.collectAsStateWithLifecycle(initialValue = "")
    var text by remember { mutableStateOf("") }
    var edited by remember { mutableStateOf(false) }
    LaunchedEffect(saved) { if (!edited) text = saved }
    LaunchedEffect(text, edited) {
        if (!edited) return@LaunchedEffect
        delay(450)
        settingsStore.saveParticipantTunnelSettings(text.trim(), 1)
    }
    val parsed = BuhtaRoom.parse(text)
    CsqttScreen(title = "Туннель", subtitle = "Комната вместо хэшей") {
        Column2(text, parsed != null, { text = it; edited = true })
    }
}

@Composable
private fun Column2(text: String, ok: Boolean, onChange: (String) -> Unit) {
    androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(
            value = text,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Ссылка на комнату Saby или ключ buhta://") },
            isError = text.isNotBlank() && !ok,
            minLines = 2,
            supportingText = {
                Text(
                    when {
                        text.isBlank() -> "Вставьте гостевую ссылку на комнату Saby Meet. После установки сервера ключ подставится сам."
                        ok -> if (text.trim().startsWith("buhta://")) "Ключ распознан ✓" else "Комната распознана ✓ (без ключа)"
                        else -> "Не похоже на ссылку комнаты или ключ"
                    }
                )
            },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Сервер и ключ создаются во вкладке «Сервер»: введите SSH-данные, придумайте логин и пароль панели, затем вставьте ссылку на комнату.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
