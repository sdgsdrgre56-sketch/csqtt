package com.csqtt.client.wb

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.CSQTTTheme
import com.csqtt.client.TunnelManager
import kotlinx.coroutines.launch

/** Separate screen for the WB Stream test (Mayak Admin only). */
class WbStreamActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CSQTTTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WbStreamScreen(onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun WbStreamScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val phase by WbState.phase.collectAsState()
    val detail by WbState.detail.collectAsState()
    val logs by WbState.logs.collectAsState()
    val mayakRunning by TunnelManager.running.collectAsState()
    val mayakStarting by TunnelManager.starting.collectAsState()

    var platform by remember { mutableStateOf(WbPrefs.platform(context)) }
    var link by remember { mutableStateOf(WbPrefs.link(context)) }
    var hasWbToken by remember { mutableStateOf(WbPrefs.token(context).isNotBlank()) }
    var showWbLogin by remember { mutableStateOf(false) }
    var dionEmail by remember { mutableStateOf(WbPrefs.dionEmail(context)) }
    var dionPassword by remember { mutableStateOf("") }
    var hasDionPassword by remember { mutableStateOf(WbPrefs.dionPassword(context).isNotBlank()) }
    var host by remember { mutableStateOf(WbPrefs.host(context)) }
    var port by remember { mutableStateOf(WbPrefs.port(context).toString()) }
    var user by remember { mutableStateOf(WbPrefs.user(context)) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var serverMessage by remember { mutableStateOf("") }
    var serverError by remember { mutableStateOf(false) }

    val roomId = parseRoomId(platform, link)
    val active = phase != WbPhase.OFF && phase != WbPhase.FAILED

    fun choose(next: CallPlatform) {
        if (next == platform) return
        platform = next
        WbPrefs.setPlatform(context, next)
        serverMessage = ""
        // A link from the other service is useless here.
        if (parseRoomId(next, link).isEmpty()) link = ""
    }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && VpnService.prepare(context) == null) {
            WbVpnService.start(context, platform, roomId)
        } else {
            toast(context, "Без разрешения на VPN подключиться не получится")
        }
    }

    fun connect() {
        if (mayakRunning || mayakStarting) {
            toast(context, "Сначала выключи Маяк — два VPN сразу Android не держит")
            return
        }
        WbPrefs.setLink(context, link)
        val prepare = VpnService.prepare(context)
        if (prepare != null) vpnLauncher.launch(prepare) else WbVpnService.start(context, platform, roomId)
    }

    fun ssh(): WbSsh? {
        val p = port.toIntOrNull()
        if (host.isBlank() || user.isBlank() || password.isEmpty() || p == null || p !in 1..65535) {
            serverMessage = "Заполни адрес, порт, логин и пароль SSH"
            serverError = true
            return null
        }
        WbPrefs.saveSsh(context, host, p, user)
        return WbSsh(host.trim(), p, user.trim(), password)
    }

    fun runServer(action: suspend (WbSsh) -> WbServerResult, okText: (WbServerResult) -> String) {
        val target = ssh() ?: return
        busy = true
        serverError = false
        serverMessage = "Выполняю… (до двух минут)"
        scope.launch {
            val result = action(target)
            busy = false
            serverError = !result.ok
            serverMessage = if (result.ok) okText(result) else "Ошибка: ${result.error}"
            if (result.link.isNotBlank()) {
                CallPlatform.detect(result.link)?.let { choose(it) }
                link = result.link
                WbPrefs.setLink(context, result.link)
            }
        }
    }

    fun install() {
        val login = when (platform) {
            CallPlatform.DION -> {
                if (dionEmail.isNotBlank() && dionPassword.isNotEmpty()) {
                    WbPrefs.saveDion(context, dionEmail, dionPassword)
                    hasDionPassword = true
                    dionPassword = ""
                }
                val savedPassword = WbPrefs.dionPassword(context)
                if (dionEmail.isBlank() || savedPassword.isBlank()) {
                    serverMessage = "Впиши почту и пароль от аккаунта DION"
                    serverError = true
                    return
                }
                WbServerInstaller.NodeLogin(roomId = roomId, dionEmail = dionEmail, dionPassword = savedPassword)
            }
            CallPlatform.WB -> {
                val token = WbPrefs.token(context)
                if (roomId.isEmpty() && token.isBlank()) {
                    serverMessage = "Для WB нужна готовая ссылка на комнату в «Подключение»: гостю WB создавать комнаты не даёт"
                    serverError = true
                    return
                }
                WbServerInstaller.NodeLogin(roomId = roomId, wbToken = if (roomId.isEmpty()) token else "")
            }
        }
        WbPrefs.setLink(context, link)
        val target = platform
        runServer({ WbServerInstaller.install(context, it, target, login) }) {
            "Узел в комнате, ссылка подставлена. Выключи Маяк и жми «Подключить»."
        }
    }

    if (showWbLogin) {
        WbLoginDialog(
            onToken = { token ->
                WbPrefs.setToken(context, token)
                hasWbToken = true
                showWbLogin = false
                toast(context, "Вход в WB Stream выполнен")
            },
            onDismiss = { showWbLogin = false },
        )
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Звонки (тест)", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onBack) { Text("Назад") }
        }
        Text(
            "Отдельный туннель через видеозвонок. Основной Маяк он не трогает, людям не уходит.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            CallPlatform.entries.forEach { option ->
                val selected = option == platform
                if (selected) {
                    Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(option.title) }
                } else {
                    OutlinedButton(
                        onClick = { choose(option) },
                        enabled = !active && !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(option.title) }
                }
            }
        }

        // --- connection ---
        Section("Подключение") {
            OutlinedTextField(
                value = link,
                onValueChange = { link = it.trim() },
                label = { Text(if (platform == CallPlatform.DION) "Ссылка dion://…" else "Ссылка на комнату WB") },
                singleLine = true,
                enabled = !active,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        val found = extractLink(clip)
                        val target = CallPlatform.detect(found) ?: platform
                        if (parseRoomId(target, found).isNotEmpty()) {
                            choose(target)
                            link = found
                        } else {
                            toast(context, "В буфере нет ссылки на комнату")
                        }
                    },
                    enabled = !active,
                ) { Text("Вставить") }
                OutlinedButton(
                    onClick = { copy(context, link) },
                    enabled = link.isNotBlank(),
                ) { Text("Скопировать") }
            }
            Text(
                text = phase.label + if (detail.isNotBlank()) ": $detail" else "",
                style = MaterialTheme.typography.titleSmall,
                color = when (phase) {
                    WbPhase.CONNECTED -> MaterialTheme.colorScheme.primary
                    WbPhase.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if ((mayakRunning || mayakStarting) && !active) {
                Text(
                    "Маяк сейчас включён. Выключи его, потом подключайся здесь.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (active) {
                Button(
                    onClick = { WbVpnService.stop(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Отключить", fontWeight = FontWeight.SemiBold) }
            } else {
                Button(
                    onClick = { connect() },
                    enabled = roomId.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Подключить", fontWeight = FontWeight.SemiBold) }
            }
        }

        // --- server node ---
        Section("Узел на сервере") {
            Text(
                "Ставится отдельной службой mayak-call. Портов не открывает, CSQTT и 3x-ui не трогает. " +
                    "Лучше ставить на тестовый сервер.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (platform == CallPlatform.DION) {
                Text(
                    "Аккаунт DION (регистрация по почте на dion.vc). Узел сам создаёт комнату и сам перезаходит, " +
                        "когда вход протухает. Телефоны заходят гостями.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = dionEmail, onValueChange = { dionEmail = it.trim() },
                    label = { Text("Почта DION") }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dionPassword, onValueChange = { dionPassword = it },
                    label = { Text(if (hasDionPassword) "Пароль DION (сохранён, можно не вводить)" else "Пароль DION") },
                    singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    if (roomId.isNotEmpty()) {
                        "Узел зайдёт гостем в комнату из поля «Подключение»."
                    } else {
                        "Создавать комнаты в WB могут только сотрудники WB. Гостю нужна готовая ссылка в «Подключение»."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (roomId.isEmpty()) {
                    OutlinedButton(onClick = { showWbLogin = true }, enabled = !busy) {
                        Text(if (hasWbToken) "Войти в WB заново (сотрудник)" else "Войти в WB (сотрудник)")
                    }
                }
            }
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
                value = password, onValueChange = { password = it },
                label = { Text("Пароль SSH (не сохраняется)") }, singleLine = true, enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { install() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Установить / обновить узел ${platform.title}") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { runServer(WbServerInstaller::status) { "Узел: ${it.status.ifBlank { "?" }}" } },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Проверить") }
                OutlinedButton(
                    onClick = { runServer(WbServerInstaller::newRoom) { "Новая комната создана, ссылка обновлена" } },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Новая комната") }
            }
            OutlinedButton(
                onClick = { runServer(WbServerInstaller::uninstall) { "Узел удалён с сервера" } },
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

        // --- log ---
        Section("Журнал") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy(context, logs.joinToString("\n")) }, enabled = logs.isNotEmpty()) {
                    Text("Скопировать")
                }
                OutlinedButton(onClick = { WbState.clearLogs() }, enabled = logs.isNotEmpty()) { Text("Очистить") }
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
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

private fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("WB Stream", text))
    toast(context, "Скопировано")
}
