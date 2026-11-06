package ru.buhta.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import ru.buhta.app.BuhtaState
import ru.buhta.app.Settings

/** Кнопка в шапке: профиль конфигурации (как верхняя панель Маяка) и оформление. */
@Composable
fun RowScope.ProfileMenu(
    profile: Int,
    onProfile: (Int) -> Unit,
    mode: String,
    onMode: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "Меню", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        Text(
            "Профиль конфигурации",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = androidx.compose.ui.Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        for (p in 0 until Settings.PROFILES) {
            DropdownMenuItem(
                text = { Text("Профиль $p") },
                trailingIcon = { if (p == profile) Icon(Icons.Filled.Check, null) },
                onClick = {
                    open = false
                    if (p != profile) {
                        if (BuhtaState.active) Toast.makeText(ctx, "Сначала отключись", Toast.LENGTH_SHORT).show()
                        else { Settings.profile = p; onProfile(p) }
                    }
                },
            )
        }
        HorizontalDivider()
        Text(
            "Оформление",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = androidx.compose.ui.Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        listOf("system" to "Система", "light" to "Светлая", "dark" to "Тёмная").forEach { (k, l) ->
            DropdownMenuItem(
                text = { Text(l) },
                trailingIcon = { if (k == mode) Icon(Icons.Filled.Check, null) },
                onClick = { open = false; onMode(k) },
            )
        }
    }
}
