package ru.buhta.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.buhta.app.BuhtaState
import ru.buhta.app.Deploy
import ru.buhta.app.Settings
import ru.buhta.app.ui.components.CsqttSettingRow
import ru.buhta.app.ui.design.CsqttShapes
import ru.buhta.app.ui.design.CsqttSizes
import ru.buhta.app.ui.design.CsqttSpacing
import ru.buhta.app.ui.design.CsqttTheme

// ───────────────────────── Установка сервера на VPS ─────────────────────────

private fun stageOf(line: String): String? = when {
    line.contains("Подключаюсь к") -> "Соединение с сервером…"
    line.contains("SSH: вход выполнен") -> "Соединение с сервером установлено"
    line.contains("Проверяю систему") -> "Проверка системы"
    line.contains("Скачиваю сервер") -> "Загрузка сервера"
    line.contains("Создаю службу") -> "Установка службы"
    line.contains("Служба buhta запущена") -> "Запуск панели"
    line.contains("Пароль панели получен") -> "Получение пароля панели"
    else -> null
}

private object DeployUi {
    val lines: SnapshotStateList<String> = mutableStateListOf()
    var busy by mutableStateOf(false)
    var stage by mutableStateOf("")
    var failed by mutableStateOf(false)
    var panelUrl by mutableStateOf("")
    var panelUser by mutableStateOf("")
    var panelPass by mutableStateOf("")
}

@Composable
fun DeployTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var host by remember { mutableStateOf(Settings.deployHost) }
    var port by remember { mutableStateOf(Settings.deployPort) }
    var user by remember { mutableStateOf(Settings.deployUser) }
    var pass by remember { mutableStateOf("") }
    var room by remember { mutableStateOf(Settings.deployRoom.ifEmpty { Settings.currentServer().room }) }
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    fun run(action: String) {
        if (DeployUi.busy) return
        val h = host.trim()
        if (h.isEmpty() || pass.isEmpty()) { toast("Укажи адрес VPS и пароль"); return }
        val roomId = Settings.UUID_RE.find(room)?.value ?: ""
        if ((action == "install") && roomId.isEmpty()) { toast("Вставь ссылку на комнату Saby Meet или UUID"); return }
        Settings.deployHost = h; Settings.deployPort = port; Settings.deployUser = user; Settings.deployRoom = room
        DeployUi.busy = true
        DeployUi.failed = false
        DeployUi.stage = "Соединение с сервером…"
        DeployUi.lines.clear()
        scope.launch(Dispatchers.IO) {
            val r = Deploy.run(h, port.toIntOrNull() ?: 22, user.trim().ifEmpty { "root" }, pass, action, roomId) { l ->
                DeployUi.lines.add(l)
                stageOf(l)?.let { DeployUi.stage = it }
            }
            var autoLink: String? = null
            if (r.ok && r.panelPass.isNotEmpty() && roomId.isNotEmpty()) {
                DeployUi.stage = "Создание ключа для телефона"
                DeployUi.lines.add("[LOG] Создаю ключ для этого телефона в панели")
                autoLink = Deploy.autoKey(h, r.panelPass, "Телефон") { DeployUi.lines.add(it) }
                if (autoLink != null) {
                    val parsed = Settings.parseKey(autoLink)
                    Settings.saveServer(parsed?.first?.ifEmpty { null } ?: h, roomId, autoLink)
                    DeployUi.lines.add("[OK] Ключ сохранён в профиле ${Settings.profile}, можно подключаться")
                }
            }
            withContext(Dispatchers.Main) {
                DeployUi.busy = false
                if (r.ok) {
                    DeployUi.lines.add("[OK] Готово")
                    DeployUi.stage = if (autoLink != null) "Готово: сервер установлен, ключ сохранён" else "Готово: операция завершена"
                    if (r.panelPass.isNotEmpty()) {
                        DeployUi.panelUrl = "https://$h:8787"
                        DeployUi.panelUser = r.panelUser
                        DeployUi.panelPass = r.panelPass
                    }
                } else {
                    DeployUi.lines.add("[ERR] ${r.message}")
                    DeployUi.failed = true
                    DeployUi.stage = "Ошибка: ${r.message}"
                }
            }
        }
    }

    BuhtaScreen {
        AppSectionCard {
            Text("Сервер (VPS)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Приложение зайдёт на VPS по SSH, скачает сервер Бухты, создаст службу и выдаст пароль веб-панели. " +
                    "Пароль SSH нигде не сохраняется.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            )
            OutlinedTextField(value = host, onValueChange = { host = it }, singleLine = true, shape = CsqttShapes.Small,
                label = { Text("IP или домен VPS") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit) }, singleLine = true, shape = CsqttShapes.Small,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("Порт") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = user, onValueChange = { user = it }, singleLine = true, shape = CsqttShapes.Small,
                    label = { Text("Логин") }, modifier = Modifier.weight(2f))
            }
            OutlinedTextField(value = pass, onValueChange = { pass = it }, singleLine = true, shape = CsqttShapes.Small,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                label = { Text("Пароль SSH") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = room, onValueChange = { room = it }, maxLines = 2, shape = CsqttShapes.Small,
                label = { Text("Ссылка на комнату Saby Meet или UUID") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { run("install") }, enabled = !DeployUi.busy, shape = CsqttShapes.Control,
                modifier = Modifier.fillMaxWidth().height(CsqttSizes.ControlHeight),
            ) { Text(if (DeployUi.busy) "Выполняется…" else "Установить сервер") }
            if (DeployUi.stage.isNotEmpty()) {
                Text(
                    DeployUi.stage,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = if (DeployUi.failed) colors.error else if (DeployUi.busy) colors.primary else BuhtaColors.connected,
                )
                if (DeployUi.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                } else {
                    LinearProgressIndicator(
                        progress = { 1f }, modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = if (DeployUi.failed) colors.error else BuhtaColors.connected,
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SmallBtn("Обновить", Modifier.weight(1f)) { run("update") }
                SmallBtn("Перезапуск", Modifier.weight(1f)) { run("restart") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SmallBtn("Логи сервера", Modifier.weight(1f)) { run("logs") }
                SmallBtn("Новый пароль панели", Modifier.weight(1f)) { run("resetpass") }
            }
            SmallBtn("Удалить сервер с VPS", Modifier.fillMaxWidth()) { run("uninstall") }
        }

        if (DeployUi.panelPass.isNotEmpty()) {
            AppSectionCard {
                Text("Веб-панель", style = MaterialTheme.typography.titleMedium)
                Text("Адрес: ${DeployUi.panelUrl}", style = MaterialTheme.typography.bodyMedium)
                Text("Логин: ${DeployUi.panelUser}", style = MaterialTheme.typography.bodyMedium)
                Text("Пароль: ${DeployUi.panelPass}", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                Text(
                    "Сертификат самоподписанный — браузер предупредит, это нормально. Пароль показывается один раз: сохрани его.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SmallBtn("Копировать пароль", Modifier.weight(1f)) {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("panel", DeployUi.panelPass)); toast("Пароль скопирован")
                    }
                    SmallBtn("Открыть панель", Modifier.weight(1f)) {
                        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DeployUi.panelUrl))) } catch (_: Exception) { toast("Не удалось открыть") }
                    }
                }
            }
        }

        val ext = CsqttTheme.extendedColors
        if (DeployUi.lines.isNotEmpty()) {
            Surface(shape = CsqttShapes.Card, color = ext.terminalBackground, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DeployUi.lines.toList().forEach { l ->
                        val c = when {
                            l.startsWith("[ERR]") -> ext.terminalError
                            l.startsWith("[OK]") -> ext.terminalSuccess
                            else -> ext.terminalText
                        }
                        Text(l, color = c, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SmallBtn(text: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = !DeployUi.busy, shape = CsqttShapes.Control,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = modifier.height(CsqttSizes.CompactControlHeight),
    ) { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) }
}

// ───────────────────────── Исключения ─────────────────────────

private class AppRow(val pkg: String, val label: String)

@Composable
fun ExceptionsTab() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppRow>>(emptyList()) }
    var chosen by remember { mutableStateOf(Settings.excluded) }
    var whitelist by remember { mutableStateOf(Settings.whitelist) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(i, 0)
                .map { AppRow(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .filter { it.pkg != ctx.packageName }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }
    }
    val colors = MaterialTheme.colorScheme
    val shown = apps.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }

    BuhtaScreen(scroll = false) {
        AppSectionCard(
            contentPadding = PaddingValues(horizontal = CsqttSpacing.Md, vertical = CsqttSpacing.Xs),
            verticalArrangement = Arrangement.spacedBy(CsqttSpacing.None),
        ) {
            CsqttSettingRow(
                title = if (whitelist) "Только выбранные идут через VPN" else "Выбранные идут мимо VPN",
                description = "Применяется при следующем подключении",
                checked = whitelist,
                onCheckedChange = { whitelist = it; Settings.whitelist = it },
            )
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, shape = CsqttShapes.Small,
            label = { Text("Поиск приложения") }, modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            items(shown, key = { it.pkg }) { a ->
                val on = a.pkg in chosen
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        chosen = if (on) chosen - a.pkg else chosen + a.pkg
                        Settings.excluded = chosen
                    }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = on, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(a.pkg, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
