package ru.buhta.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ru.buhta.app.BuhtaState
import ru.buhta.app.Settings
import ru.buhta.app.ui.components.CsqttEmptyState
import ru.buhta.app.ui.components.CsqttSegmentedControl
import ru.buhta.app.ui.design.CsqttShapes
import ru.buhta.app.ui.design.CsqttSizes
import ru.buhta.app.ui.design.CsqttSpacing
import ru.buhta.app.ui.design.CsqttTheme

/** Каркас вкладки: заголовок «Бухта» + версия, как шапка в Маяке. */
@Composable
fun BuhtaScreen(
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ctx = LocalContext.current
    val version = remember {
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = CsqttSizes.ScreenHorizontalPadding)
            .padding(top = CsqttSizes.ScreenTopPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = CsqttSizes.ContentMaxWidth)
                .then(if (scroll) Modifier.fillMaxHeight().verticalScroll(rememberScrollState()) else Modifier.fillMaxHeight()),
            verticalArrangement = Arrangement.spacedBy(CsqttSpacing.Md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Бухта",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Surface(
                    shape = CsqttShapes.Control,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        "v$version",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            content()
        }
    }
}

// ───────────────────────── Подключение ─────────────────────────

@Composable
fun ConnectionTab(onToggle: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val tick = BuhtaState.tick
    val active = BuhtaState.active
    val connected = BuhtaState.connected
    val server = Settings.currentServer()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connected) {
        while (connected) { now = System.currentTimeMillis(); delay(1000) }
    }
    val uptime = if (connected && BuhtaState.connectedAt > 0) ((now - BuhtaState.connectedAt) / 1000).coerceAtLeast(0) else 0L
    val colors = MaterialTheme.colorScheme
    val statusColor = when {
        connected -> BuhtaColors.connected
        active -> colors.primary
        else -> colors.onSurfaceVariant
    }
    val statusText = when {
        connected -> "Подключено"
        active -> "Подключение"
        else -> "Отключено"
    }
    val scale by animateFloatAsState(if (connected) 1f else if (active) 0.96f else 0.92f, label = "logo")

    BuhtaScreen {
        AppSectionCard(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val on = connected || active
                val brush = if (on) {
                    Brush.radialGradient(listOf(colors.primary.copy(alpha = 0.55f), colors.primary, colors.primary.copy(alpha = 0.78f)))
                } else {
                    Brush.radialGradient(listOf(colors.surfaceVariant, colors.surfaceVariant.copy(alpha = 0.7f)))
                }
                Box(
                    modifier = Modifier
                        .size(172.dp)
                        .scale(scale)
                        .clip(CircleShape)
                        .background(brush)
                        .clickable(onClick = onToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PowerSettingsNew,
                        contentDescription = if (on) "Отключить" else "Подключить",
                        tint = if (on) colors.onPrimary else colors.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(76.dp),
                    )
                }
                Text(statusText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = statusColor, textAlign = TextAlign.Center)
                Text(
                    "%02d:%02d:%02d".format(uptime / 3600, (uptime / 60) % 60, uptime % 60),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (connected) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.7f),
                )
                StatusPanel(
                    server.name,
                    if (server.key.isNotEmpty()) "E2E" else "Нет",
                    if (connected && BuhtaState.ip.isNotEmpty()) BuhtaState.ip else "—",
                )
            }
        }
        AppSectionCard {
            Text("Как это работает", style = MaterialTheme.typography.titleMedium)
            Text(
                "Трафик телефона идёт через звонок Saby Meet на твой сервер. " +
                    "Серверы и ключи добавляются на вкладке «Серверы».",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusPanel(server: String, enc: String, ip: String) {
    Surface(
        shape = CsqttShapes.Control,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusItem("Сервер", server, Modifier.weight(1f).padding(horizontal = 5.dp, vertical = 8.dp))
            Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)))
            StatusItem("Шифрование", enc, Modifier.weight(1f).padding(horizontal = 5.dp, vertical = 8.dp))
            Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)))
            StatusItem("IP в туннеле", ip, Modifier.weight(1f).padding(horizontal = 5.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun StatusItem(title: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f),
            textAlign = TextAlign.Center, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
    }
}

// ───────────────────────── Серверы ─────────────────────────

@Composable
private fun PillButton(text: String, filled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    if (filled) {
        Button(onClick = onClick, shape = CsqttShapes.Control, modifier = modifier.height(CsqttSizes.CompactControlHeight)) { Text(text) }
    } else {
        OutlinedButton(
            onClick = onClick, shape = CsqttShapes.Control,
            border = BorderStroke(1.dp, c.outline.copy(alpha = 0.4f)),
            modifier = modifier.height(CsqttSizes.CompactControlHeight),
        ) { Text(text) }
    }
}

@Composable
fun ServersTab() {
    val ctx = LocalContext.current
    var servers by remember { mutableStateOf(Settings.servers().toList()) }
    var sel by remember { mutableIntStateOf(Settings.selected.coerceIn(0, servers.size - 1)) }
    var name by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
    val colors = MaterialTheme.colorScheme

    BuhtaScreen {
        servers.forEachIndexed { i, s ->
            val chosen = i == sel
            Surface(
                shape = CsqttShapes.Card,
                color = if (chosen) colors.primaryContainer.copy(alpha = 0.55f) else colors.surface,
                border = BorderStroke(1.dp, if (chosen) colors.primary.copy(alpha = 0.7f) else colors.outlineVariant.copy(alpha = 0.32f)),
                modifier = Modifier.fillMaxWidth().clip(CsqttShapes.Card).clickable {
                    if (BuhtaState.active) toast("Сначала отключись")
                    else { Settings.selected = i; sel = i }
                },
            ) {
                Column(Modifier.padding(CsqttSpacing.Md), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = chosen, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.room, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = {
                            if (servers.size <= 1) toast("Нужен хотя бы один сервер")
                            else if (BuhtaState.active) toast("Сначала отключись")
                            else {
                                val l = servers.toMutableList().also { it.removeAt(i) }
                                Settings.saveServers(l); Settings.selected = 0
                                servers = l; sel = 0
                            }
                        }) { Icon(Icons.Outlined.Delete, contentDescription = "Удалить", tint = colors.onSurfaceVariant) }
                    }
                    Text(
                        if (s.key.isNotEmpty()) "● шифрование E2E (ключ buhta://)" else "○ без шифрования",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (s.key.isNotEmpty()) BuhtaColors.connected else colors.onSurfaceVariant,
                    )
                }
            }
        }

        AppSectionCard {
            Text("Добавить сервер", style = MaterialTheme.typography.titleMedium)
            Text(
                "Вставь ключ buhta:// из панели (с шифрованием) либо ссылку на гостевую комнату Saby Meet / UUID.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true, shape = CsqttShapes.Small,
                label = { Text("Название (необязательно)") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = link, onValueChange = { link = it }, maxLines = 3, shape = CsqttShapes.Small,
                label = { Text("Ссылка, ключ или UUID") }, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                PillButton("Из буфера", filled = false, modifier = Modifier.weight(1f)) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    link = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty().trim()
                }
                PillButton("Добавить", modifier = Modifier.weight(1f)) {
                    val raw = link.trim()
                    val parsed = Settings.parseKey(raw)
                    val m = if (parsed != null) null else Settings.UUID_RE.find(raw)
                    if (parsed == null && m == null) {
                        toast("Не нашёл ключ или UUID комнаты")
                    } else {
                        val fb = parsed?.first?.ifEmpty { null } ?: "Сервер ${servers.size + 1}"
                        val nm = name.trim().ifEmpty { fb }
                        val l = servers.toMutableList()
                        if (parsed != null) l.add(Settings.Server(nm, parsed.second, raw)) else l.add(Settings.Server(nm, m!!.value))
                        Settings.saveServers(l)
                        servers = l; name = ""; link = ""
                        toast("Сервер добавлен")
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ───────────────────────── Настройки ─────────────────────────

@Composable
fun SettingsTab(mode: String, skin: String, onMode: (String) -> Unit, onSkin: (String) -> Unit) {
    var dns by remember { mutableStateOf(Settings.dnsKey) }
    var custom by remember { mutableStateOf(Settings.customDns) }
    val colors = MaterialTheme.colorScheme

    BuhtaScreen {
        AppSectionCard {
            Text("Тема", style = MaterialTheme.typography.titleMedium)
            CsqttSegmentedControl(
                options = listOf("system" to "Система", "light" to "Светлая", "dark" to "Тёмная"),
                selected = mode, onSelected = onMode,
            )
            Text("Скин", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
            CsqttSegmentedControl(options = skins, selected = skin, onSelected = onSkin)
        }
        AppSectionCard(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("DNS", style = MaterialTheme.typography.titleMedium)
            Text("Применяется при следующем подключении.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Settings.dnsPresets.forEach { p ->
                Row(
                    modifier = Modifier.fillMaxWidth().selectable(
                        selected = dns == p.key, role = Role.RadioButton,
                        onClick = { dns = p.key; Settings.dnsKey = p.key },
                    ).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = dns == p.key, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(p.title, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (dns == "custom") {
                OutlinedTextField(
                    value = custom, onValueChange = { custom = it; Settings.customDns = it },
                    label = { Text("IP через пробел или запятую") }, singleLine = true,
                    shape = CsqttShapes.Small, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ───────────────────────── Логи ─────────────────────────

@Composable
fun LogsTab() {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val tick = BuhtaState.tick
    val lines = BuhtaState.lines()
    val ext = CsqttTheme.extendedColors
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }

    BuhtaScreen(scroll = false) {
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 4.dp),
            shape = CsqttShapes.Card,
            color = ext.terminalBackground,
            contentColor = ext.terminalText,
        ) {
            Box(Modifier.fillMaxSize()) {
                if (lines.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CsqttEmptyState(
                            title = "Логи пусты", icon = Icons.Outlined.Terminal,
                            contentColor = ext.terminalText, secondaryContentColor = ext.terminalMuted,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState, modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 64.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(lines.size) { i ->
                            val l = lines[i]
                            val c = when {
                                l.contains("Ошибка", true) || l.contains("error", true) || l.contains("fail", true) -> ext.terminalError
                                l.contains("Подключено") || l.contains("connected") -> ext.terminalSuccess
                                l.startsWith("▶") -> ext.terminalText
                                else -> ext.terminalMuted
                            }
                            Text(l, color = c, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                }
                Row(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IconButton(
                        enabled = lines.isNotEmpty(),
                        onClick = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Buhta logs", lines.joinToString("\n")))
                            Toast.makeText(ctx, "Логи скопированы", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Outlined.ContentCopy, "Копировать", tint = Color.White.copy(alpha = if (lines.isNotEmpty()) 1f else 0.4f), modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = { BuhtaState.clear() }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.Delete, "Очистить", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

// ───────────────────────── Инфо ─────────────────────────

@Composable
fun InfoTab() {
    val c = MaterialTheme.colorScheme
    BuhtaScreen {
        AppSectionCard {
            Text("О Бухте", style = MaterialTheme.typography.titleMedium)
            Text(
                "VPN, который ведёт IP-трафик телефона через звонок Saby Meet и выходит в интернет с твоего сервера. " +
                    "Со стороны сети это обычный видеозвонок.",
                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
            )
        }
        AppSectionCard {
            Text("Как подключиться", style = MaterialTheme.typography.titleMedium)
            listOf(
                "1. Открой веб-панель сервера и создай клиента.",
                "2. Скопируй ключ buhta:// (или ссылку на комнату).",
                "3. Вкладка «Серверы» → вставь ключ → «Добавить».",
                "4. Вернись на главную и нажми круглую кнопку.",
            ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) }
        }
        AppSectionCard {
            Text("Защита", style = MaterialTheme.typography.titleMedium)
            Text(
                "С ключом buhta:// трафик шифруется сквозным образом: обмен X25519 + ML-KEM-768, AES-256-GCM. " +
                    "Комната и звонок видят только шифртекст.",
                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
