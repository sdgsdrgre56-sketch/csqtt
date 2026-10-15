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

    var link by remember { mutableStateOf(WbPrefs.link(context)) }
    var hasToken by remember { mutableStateOf(WbPrefs.token(context).isNotBlank()) }
    var showLogin by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf(WbPrefs.host(context)) }
    var port by remember { mutableStateOf(WbPrefs.port(context).toString()) }
    var user by remember { mutableStateOf(WbPrefs.user(context)) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var serverMessage by remember { mutableStateOf("") }
    var serverError by remember { mutableStateOf(false) }

    val roomId = parseWbRoomId(link)
    val active = phase != WbPhase.OFF && phase != WbPhase.FAILED

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && VpnService.prepare(context) == null) {
            WbVpnService.start(context, roomId)
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
        if (prepare != null) vpnLauncher.launch(prepare) else WbVpnService.start(context, roomId)
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
                link = result.link
                WbPrefs.setLink(context, result.link)
            }
        }
    }

    if (showLogin) {
        WbLoginDialog(
            onToken = { token ->
                WbPrefs.setToken(context, token)
                hasToken = true
                showLogin = false
                toast(context, "Вход в WB Stream выполнен")
            },
            onDismiss = { showLogin = false },
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
            Text("WB Stream (тест)", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onBack) { Text("Назад") }
        }
        Text(
            "Отдельный туннель через звонок WB Stream. Основной Маяк он не трогает, людям не уходит.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // --- connection ---
        Section("Подключение") {
            OutlinedTextField(
                value = link,
                onValueChange = { link = it.trim() },
                label = { Text("Ссылка на комнату WB") },
                singleLine = true,
                enabled = !active,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        val found = Regex("(?:wbstream://|https?://)\\S+").find(clip)?.value ?: clip.trim()
                        if (parseWbRoomId(found).isNotEmpty()) link = found else toast(context, "В буфере нет ссылки WB")
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
                "Ставится отдельной службой mayak-wb. Портов не открывает, CSQTT и 3x-ui не трогает. " +
                    "Лучше ставить на тестовый сервер.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (hasToken) "Вход в WB: выполнен" else "Вход в WB: нужен для установки",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(top = 10.dp),
                )
                OutlinedButton(onClick = { showLogin = true }, enabled = !busy) {
                    Text(if (hasToken) "Войти заново" else "Войти в WB")
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
            Text(
                if (roomId.isNotEmpty()) {
                    "Узел зайдёт гостем в комнату из поля «Подключение». Вход в WB для этого не нужен."
                } else {
                    "Поле «Подключение» пустое: узел попробует создать свою комнату. Это может только сотрудник WB, " +
                        "гостю нужна готовая ссылка с сайта stream.wb.ru."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    val token = WbPrefs.token(context)
                    if (roomId.isEmpty() && token.isBlank()) {
                        serverMessage = "Вставь ссылку на комнату WB в «Подключение» (или войди в WB как сотрудник)"
                        serverError = true
                    } else {
                        WbPrefs.setLink(context, link)
                        val room = roomId
                        runServer({ WbServerInstaller.install(context, it, if (room.isEmpty()) token else "", room) }) {
                            "Узел в комнате. Теперь выключи Маяк и жми «Подключить»."
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (roomId.isNotEmpty()) "Установить узел в эту комнату" else "Установить / обновить узел") }
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
