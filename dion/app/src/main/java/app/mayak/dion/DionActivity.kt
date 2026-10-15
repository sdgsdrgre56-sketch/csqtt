package app.mayak.dion

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class DionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) {
                    darkColorScheme(primary = Color(0xFF90CAF9), secondary = Color(0xFFFFD54F))
                } else {
                    lightColorScheme(primary = Color(0xFF1565C0), secondary = Color(0xFFF9A825))
                },
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DionScreen()
                }
            }
        }
    }
}

@Composable
private fun DionScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val phase by DionState.phase.collectAsState()
    val detail by DionState.detail.collectAsState()
    val channels by DionState.channels.collectAsState()
    val logs by DionState.logs.collectAsState()

    var count by remember { mutableStateOf(DionPrefs.count(context)) }
    val links = remember { mutableStateListOf<String>().apply { addAll(DionPrefs.links(context)) } }
    var email by remember { mutableStateOf(DionPrefs.email(context)) }
    var password by remember { mutableStateOf("") }
    var hasPassword by remember { mutableStateOf(DionPrefs.password(context).isNotBlank()) }
    var ownAccounts by remember { mutableStateOf(DionPrefs.ownAccounts(context)) }
    val ownEmails = remember { mutableStateListOf<String>().apply { addAll((1..MAX_CHANNELS).map { DionPrefs.email(context, it) }) } }
    val ownPasswords = remember { mutableStateListOf<String>().apply { repeat(MAX_CHANNELS) { add("") } } }
    var host by remember { mutableStateOf(DionPrefs.host(context)) }
    var port by remember { mutableStateOf(DionPrefs.port(context).toString()) }
    var user by remember { mutableStateOf(DionPrefs.user(context)) }
    var sshPassword by remember { mutableStateOf("") }
    var hasSshPassword by remember { mutableStateOf(DionPrefs.sshPassword(context).isNotEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var serverMessage by remember { mutableStateOf("") }
    var serverError by remember { mutableStateOf(false) }
    var batteryFree by remember { mutableStateOf(isIgnoringBattery(context)) }

    val active = phase != Phase.OFF && phase != Phase.FAILED
    val slugs = (0 until count).map { parseSlug(links[it]) }.filter { it.isNotEmpty() }

    LaunchedEffect(phase) { batteryFree = isIgnoringBattery(context) }
    LaunchedEffect(Unit) { if (DionUpdate.stage.value == DionUpdate.Stage.IDLE) DionUpdate.check() }
    val updStage by DionUpdate.stage.collectAsState()
    val updRelease by DionUpdate.available.collectAsState()
    val updProgress by DionUpdate.progress.collectAsState()
    val updMessage by DionUpdate.message.collectAsState()

    fun saveLinks() = DionPrefs.setLinks(context, links.toList())

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && VpnService.prepare(context) == null) {
            DionVpnService.start(context, slugs)
        } else {
            toast(context, "Без разрешения на VPN подключиться не получится")
        }
    }

    fun connect() {
        saveLinks()
        val prepare = VpnService.prepare(context)
        if (prepare != null) vpnLauncher.launch(prepare) else DionVpnService.start(context, slugs)
    }

    fun ssh(): Ssh? {
        val p = port.toIntOrNull()
        if (sshPassword.isNotEmpty()) {
            DionPrefs.setSshPassword(context, sshPassword)
            hasSshPassword = true
            sshPassword = ""
        }
        val savedSsh = DionPrefs.sshPassword(context)
        if (host.isBlank() || user.isBlank() || savedSsh.isEmpty() || p == null || p !in 1..65535) {
            serverMessage = "Заполни IP, порт, логин и пароль SSH"
            serverError = true
            return null
        }
        DionPrefs.saveSsh(context, host, p, user)
        return Ssh(host.trim(), p, user.trim(), savedSsh)
    }

    fun applyResult(result: ServerResult, okText: (ServerResult) -> String) {
        busy = false
        result.links.forEach { (n, link) -> if (n in 1..MAX_CHANNELS) links[n - 1] = link }
        saveLinks()
        serverError = !result.ok
        serverMessage = buildString {
            append(if (result.ok) okText(result) else "Ошибка: ${result.error}")
            result.fails.toSortedMap().forEach { (n, why) -> append("\nКанал $n: $why") }
        }
        if (result.links.isNotEmpty() && active) {
            serverMessage += "\nСсылки поменялись: отключись и подключись заново."
        }
    }

    fun runServer(action: suspend (Ssh) -> ServerResult, okText: (ServerResult) -> String) {
        val target = ssh() ?: return
        busy = true
        serverError = false
        serverMessage = "Выполняю… (до 3 минут)"
        scope.launch { applyResult(action(target), okText) }
    }

    fun install() {
        if (email.isNotBlank() && password.isNotEmpty()) {
            DionPrefs.saveAccount(context, 0, email, password)
            hasPassword = true
            password = ""
        } else if (email.isNotBlank()) {
            DionPrefs.saveAccount(context, 0, email, "")
        }
        val sharedPass = DionPrefs.password(context)
        val shared = if (email.isNotBlank() && sharedPass.isNotBlank()) Account(email, sharedPass) else null
        val own = mutableMapOf<Int, Account>()
        if (ownAccounts) {
            for (n in 1..count) {
                DionPrefs.saveAccount(context, n, ownEmails[n - 1], ownPasswords[n - 1])
                ownPasswords[n - 1] = ""
                val e = DionPrefs.email(context, n)
                val p = DionPrefs.password(context, n)
                if (e.isNotBlank() && p.isNotBlank()) own[n] = Account(e, p)
            }
        }
        DionPrefs.setOwnAccounts(context, ownAccounts)
        if (shared == null && (1..count).any { it !in own }) {
            serverMessage = "Впиши почту и пароль DION (общий аккаунт или свой для каждого канала)"
            serverError = true
            return
        }
        DionPrefs.setCount(context, count)
        saveLinks()
        val rooms = (1..count).associateWith { parseSlug(links[it - 1]) }.filterValues { it.isNotEmpty() }
        val n = count
        runServer({ DionInstaller.install(context, it, n, shared, own, rooms) }) { r ->
            "Каналов в комнатах: ${r.links.size} из $n. Ссылки подставлены, жми «Подключить»."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("DION Маяк", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        updRelease?.let { rel ->
            if (updStage == DionUpdate.Stage.AVAILABLE) {
                Text(
                    "Есть обновление: сборка ${rel.build}. Кнопка внизу, в «Обновления».",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(
            "VPN через несколько звонков DION сразу. Каждая комната — отдельный канал, " +
                "соединения раскладываются по всем работающим каналам.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // --- connection ---
        Section("Подключение") {
            Text(
                text = phase.label + if (detail.isNotBlank()) ": $detail" else "",
                style = MaterialTheme.typography.titleSmall,
                color = when (phase) {
                    Phase.CONNECTED -> MaterialTheme.colorScheme.primary
                    Phase.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (channels.isNotEmpty() && active) {
                channels.forEach { ch -> ChannelRow(ch) }
                val down = channels.sumOf { it.down }
                val up = channels.sumOf { it.up }
                Text(
                    "Всего: ↓ ${formatSpeed(down)}   ↑ ${formatSpeed(up)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (active) {
                Button(
                    onClick = { DionVpnService.stop(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Отключить", fontWeight = FontWeight.SemiBold) }
            } else {
                Button(
                    onClick = { connect() },
                    enabled = slugs.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(
                        if (slugs.isEmpty()) "Нет ссылок на комнаты" else "Подключить (${slugs.size} каналов)",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (!batteryFree) {
                OutlinedButton(onClick = { askBattery(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Не усыплять приложение (важно для Samsung)")
                }
            }
        }

        // --- rooms ---
        Section("Комнаты") {
            Text(
                "Ссылка на каждый канал. Подставляются сами после установки узла.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (i in 0 until count) {
                OutlinedTextField(
                    value = links[i],
                    onValueChange = { links[i] = it.trim() },
                    label = { Text("Канал ${i + 1}: dion://…") },
                    singleLine = true,
                    enabled = !active && !busy,
                    isError = links[i].isNotBlank() && parseSlug(links[i]).isEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        val found = parseAllLinks(clip)
                        if (found.isEmpty()) {
                            toast(context, "В буфере нет ссылок dion://")
                        } else {
                            found.take(MAX_CHANNELS).forEachIndexed { i, l -> links[i] = l }
                            if (found.size > count) {
                                count = found.size.coerceAtMost(MAX_CHANNELS)
                                DionPrefs.setCount(context, count)
                            }
                            saveLinks()
                            toast(context, "Вставлено ссылок: ${found.size.coerceAtMost(MAX_CHANNELS)}")
                        }
                    },
                    enabled = !active && !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Вставить все") }
                OutlinedButton(
                    onClick = { copy(context, (0 until count).map { links[it] }.filter { it.isNotBlank() }.joinToString("\n")) },
                    enabled = slugs.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Скопировать все") }
            }
        }

        // --- server node ---
        Section("Узел на сервере") {
            Text(
                "Каждый канал — своя служба mayak-dion@N и своя комната. Портов не открывает, " +
                    "CSQTT, 3x-ui и тестовый узел Маяк Админ не трогает. Ставь на тестовый сервер.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Каналов:", style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = { if (count > 1) count-- }, enabled = !busy && !active && count > 1) { Text("−") }
                Text("$count", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = { if (count < MAX_CHANNELS) count++ }, enabled = !busy && !active && count < MAX_CHANNELS) { Text("+") }
            }
            Text(
                "Каждый канал на сервере берёт около 60 МБ памяти: 12 каналов ≈ 750 МБ.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = email, onValueChange = { email = it.trim() },
                label = { Text("Почта DION") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text(if (hasPassword) "Пароль DION (сохранён, можно не вводить)" else "Пароль DION") },
                singleLine = true, enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = ownAccounts, onCheckedChange = { ownAccounts = it }, enabled = !busy)
                Text("Свой аккаунт для каждого канала", style = MaterialTheme.typography.bodyMedium)
            }
            if (ownAccounts) {
                Text(
                    "Пустые строки берут общий аккаунт выше. Пригодится, если DION не даст одному аккаунту сидеть в $count комнатах.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (i in 0 until count) {
                    val saved = DionPrefs.password(context, i + 1).isNotBlank()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = ownEmails[i], onValueChange = { ownEmails[i] = it.trim() },
                            label = { Text("Почта ${i + 1}") }, singleLine = true, enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.weight(1.3f),
                        )
                        OutlinedTextField(
                            value = ownPasswords[i], onValueChange = { ownPasswords[i] = it },
                            label = { Text(if (saved) "сохранён" else "Пароль ${i + 1}") },
                            singleLine = true, enabled = !busy,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            HorizontalDivider()
            OutlinedTextField(
                value = host, onValueChange = { host = it.trim() },
                label = { Text("IP сервера") }, singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Порт SSH") }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = user, onValueChange = { user = it.trim() },
                    label = { Text("Логин") }, singleLine = true, enabled = !busy,
                    modifier = Modifier.weight(1.4f),
                )
            }
            OutlinedTextField(
                value = sshPassword, onValueChange = { sshPassword = it },
                label = { Text(if (hasSshPassword) "Пароль SSH (сохранён, можно не вводить)" else "Пароль SSH") }, singleLine = true, enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { install() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Установить / обновить узел ($count каналов)")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { runServer(DionInstaller::status) { "Узел: ${it.status.ifBlank { "?" }}" } },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Проверить") }
                OutlinedButton(
                    onClick = { runServer(DionInstaller::newRooms) { "Новые комнаты: ${it.links.size}, ссылки обновлены" } },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Новые комнаты") }
            }
            OutlinedButton(
                onClick = { runServer(DionInstaller::uninstall) { "Узел удалён с сервера" } },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Удалить узел с сервера") }
            if (serverMessage.isNotBlank()) {
                Text(
                    serverMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (serverError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }

        // --- updates ---
        Section("Обновления") {
            Text(
                "Сейчас стоит сборка ${DionUpdate.localBuild()}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (updMessage.isNotBlank()) {
                Text(
                    updMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (updStage == DionUpdate.Stage.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (updStage == DionUpdate.Stage.DOWNLOADING) {
                LinearProgressIndicator(progress = { updProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            val rel = updRelease
            if (rel != null && updStage != DionUpdate.Stage.DOWNLOADING && updStage != DionUpdate.Stage.CHECKING) {
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                            toast(context, "Разреши DION Маяку устанавливать приложения и нажми ещё раз")
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                                )
                            }
                        } else {
                            scope.launch { DionUpdate.downloadAndInstall(context) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Обновить до сборки ${rel.build}") }
            }
            OutlinedButton(
                onClick = { scope.launch { DionUpdate.check() } },
                enabled = updStage != DionUpdate.Stage.CHECKING && updStage != DionUpdate.Stage.DOWNLOADING,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (updStage == DionUpdate.Stage.CHECKING) "Проверяю…" else "Проверить обновления") }
        }

        // --- log ---
        Section("Журнал") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy(context, logs.joinToString("\n")) }, enabled = logs.isNotEmpty()) { Text("Скопировать") }
                OutlinedButton(onClick = { DionState.clearLogs() }, enabled = logs.isNotEmpty()) { Text("Очистить") }
            }
            HorizontalDivider()
            SelectionContainer {
                Text(
                    text = if (logs.isEmpty()) "Пока пусто" else logs.takeLast(150).joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(ch: ChannelInfo) {
    val color = when (ch.state) {
        ChannelState.UP -> Color(0xFF43A047)
        ChannelState.CONNECTING, ChannelState.RECONNECTING -> Color(0xFFFFB300)
        ChannelState.FAILED -> Color(0xFFE53935)
        ChannelState.OFF -> Color(0xFF9E9E9E)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text("Канал ${ch.n}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text(
            if (ch.state == ChannelState.UP) "↓ ${formatSpeed(ch.down)} · соед. ${ch.active}" else ch.state.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (ch.note.isNotBlank() && ch.state != ChannelState.UP) {
        Text(
            ch.note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 18.dp),
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

private fun isIgnoringBattery(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

@SuppressLint("BatteryLife")
private fun askBattery(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        )
    }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

private fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("DION", text))
    toast(context, "Скопировано")
}
