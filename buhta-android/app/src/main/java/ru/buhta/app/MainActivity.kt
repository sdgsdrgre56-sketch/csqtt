package ru.buhta.app

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import ru.buhta.app.ui.*
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) launchVpn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            var mode by remember { mutableStateOf(Settings.mode) }
            var skin by remember { mutableStateOf(Settings.palette) }
            BuhtaTheme(mode, skin) {
                MainScreen(
                    mode = mode, skin = skin,
                    onMode = { mode = it; Settings.mode = it },
                    onSkin = { skin = it; Settings.palette = it },
                    onToggle = { if (BuhtaState.active) stopVpn() else startVpn() },
                )
            }
        }
    }

    private fun startVpn() {
        val room = Settings.currentServer().room
        if (!Settings.UUID_RE.matches(room)) {
            Toast.makeText(this, "У сервера неверный UUID комнаты", Toast.LENGTH_SHORT).show()
            return
        }
        val prep = VpnService.prepare(this)
        if (prep != null) vpnPermission.launch(prep) else launchVpn()
    }

    private fun launchVpn() {
        val s = Settings.currentServer()
        BuhtaState.active = true
        BuhtaState.update("Подключаюсь к комнате…")
        startService(
            Intent(this, BuhtaVpnService::class.java)
                .putExtra("room", s.room)
                .putExtra("key", s.key)
        )
    }

    private fun stopVpn() {
        startService(Intent(this, BuhtaVpnService::class.java).setAction("stop"))
    }
}

private val navItems = listOf(
    NavItem(0, "Бухта", Icons.Filled.PowerSettingsNew, Icons.Filled.PowerSettingsNew),
    NavItem(1, "Серверы", Icons.Filled.Dns, Icons.Outlined.Dns),
    NavItem(2, "Настройки", Icons.Filled.Tune, Icons.Outlined.Tune),
    NavItem(3, "Логи", Icons.Filled.Terminal, Icons.Outlined.Terminal),
    NavItem(4, "Инфо", Icons.Filled.Info, Icons.Outlined.Info),
)

@Composable
fun MainScreen(
    mode: String, skin: String,
    onMode: (String) -> Unit, onSkin: (String) -> Unit,
    onToggle: () -> Unit,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val dragTargetIndex = remember { mutableIntStateOf(-1) }
    val dragProgress = remember { mutableFloatStateOf(0f) }
    val tabStateHolder = rememberSaveableStateHolder()

    val safeBottomInset = with(density) { WindowInsets.safeDrawing.getBottom(density).toDp() }
    val navOverlayReserve = safeBottomInset + 10.dp + 66.dp + 5.dp
    val dragSlop = with(density) { 6.dp.toPx() }

    Box(Modifier.fillMaxSize()) {
        AppBackdrop(Modifier.matchParentSize())
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            containerColor = Color.Transparent,
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .pointerInput(selectedTab) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDrag = 0f; dragTargetIndex.intValue = -1; dragProgress.floatValue = 0f },
                            onDragCancel = { dragTargetIndex.intValue = -1; dragProgress.floatValue = 0f },
                            onDragEnd = {
                                if (dragTargetIndex.intValue in navItems.indices && shouldCommitNavigationSwipe(dragProgress.floatValue)) {
                                    selectedTab = navItems[dragTargetIndex.intValue].id
                                }
                                dragTargetIndex.intValue = -1; dragProgress.floatValue = 0f
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            totalDrag += dragAmount
                            if (abs(totalDrag) < dragSlop) {
                                dragTargetIndex.intValue = -1; dragProgress.floatValue = 0f
                                return@detectHorizontalDragGestures
                            }
                            val cur = navItems.indexOfFirst { it.id == selectedTab }
                            val cand = if (totalDrag < 0f) cur + 1 else cur - 1
                            if (cand !in navItems.indices) {
                                dragTargetIndex.intValue = -1; dragProgress.floatValue = 0f
                                return@detectHorizontalDragGestures
                            }
                            dragTargetIndex.intValue = cand
                            dragProgress.floatValue = navigationSwipeProgress(totalDrag, size.width)
                        }
                    },
            ) {
                Box(Modifier.fillMaxSize().padding(bottom = navOverlayReserve)) {
                    key(selectedTab) {
                        tabStateHolder.SaveableStateProvider(selectedTab) {
                            when (selectedTab) {
                                0 -> ConnectionTab(onToggle)
                                1 -> ServersTab()
                                2 -> SettingsTab(mode, skin, onMode, onSkin)
                                3 -> LogsTab()
                                else -> InfoTab()
                            }
                        }
                    }
                }
                ProxyNavigationBar(
                    navItems = navItems,
                    selectedTab = selectedTab,
                    dragTargetIndex = dragTargetIndex,
                    dragProgress = dragProgress,
                    onTabSelected = { index ->
                        if (selectedTab != index) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            selectedTab = index
                        }
                        dragTargetIndex.intValue = -1
                        dragProgress.floatValue = 0f
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
