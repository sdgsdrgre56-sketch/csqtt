package ru.buhta.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var skin: Skin
    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private var tab = 0

    // живые элементы, которые обновляются при смене состояния
    private var statusView: TextView? = null
    private var connectBtn: Button? = null
    private var serverView: TextView? = null
    private var logView: TextView? = null
    private var logScroll: ScrollView? = null

    private val tabNames = listOf("Подключение", "Серверы", "Настройки", "Логи", "Инфо")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun isDark(): Boolean = when (Settings.mode) {
        "dark" -> true
        "light" -> false
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        skin = Themes.get(Settings.palette, isDark())
        window.statusBarColor = skin.bg
        window.navigationBarColor = skin.surface

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(skin.bg)
        }
        val title = TextView(this).apply {
            text = "Бухта"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(skin.text)
            setPadding(dp(20), dp(36), dp(20), dp(8))
        }
        content = FrameLayout(this)
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(skin.surface)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        root.addView(title)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(tabBar, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        tab = savedInstanceState?.getInt("tab") ?: 0
        buildTabBar()
        showTab(tab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    override fun onResume() {
        super.onResume()
        BuhtaState.onChange = { ui.post { renderLive() } }
        renderLive()
    }

    override fun onPause() {
        BuhtaState.onChange = null
        super.onPause()
    }

    // ---------- каркас ----------

    private fun buildTabBar() {
        tabBar.removeAllViews()
        tabNames.forEachIndexed { i, n ->
            tabBar.addView(TextView(this).apply {
                text = n
                textSize = 11.5f
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(2), dp(10), dp(2), dp(10))
                setTextColor(if (i == tab) skin.onPrimary else skin.sub)
                background = round(if (i == tab) skin.primary else 0x00000000, 18)
                setOnClickListener { showTab(i) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
    }

    private fun showTab(i: Int) {
        tab = i
        buildTabBar()
        statusView = null; connectBtn = null; serverView = null; logView = null; logScroll = null
        content.removeAllViews()
        val v = when (i) {
            0 -> connectTab()
            1 -> serversTab()
            2 -> settingsTab()
            3 -> logsTab()
            else -> infoTab()
        }
        content.addView(v, FrameLayout.LayoutParams(-1, -1))
        renderLive()
    }

    private fun renderLive() {
        val st = (if (BuhtaState.connected) "🟢 " else if (BuhtaState.active) "🟡 " else "⚪ ") + BuhtaState.status
        statusView?.text = st
        connectBtn?.text = if (BuhtaState.active) "Отключить" else "Подключить"
        serverView?.text = Settings.currentServer().name
        logView?.let {
            it.text = BuhtaState.text()
            logScroll?.post { logScroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ---------- помощники оформления ----------

    private fun round(color: Int, r: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun page(): Pair<ScrollView, LinearLayout> {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        val sv = ScrollView(this).apply { addView(col) }
        return Pair(sv, col)
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(skin.surface, 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        parent.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        return c
    }

    private fun label(parent: LinearLayout, s: String, size: Float = 14f, bold: Boolean = false, sub: Boolean = false): TextView {
        val t = TextView(this).apply {
            text = s
            textSize = size
            setTextColor(if (sub) skin.sub else skin.text)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        parent.addView(t, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        return t
    }

    private fun button(parent: LinearLayout, s: String, filled: Boolean = true, onClick: () -> Unit): Button {
        val b = Button(this).apply {
            text = s
            isAllCaps = false
            setTextColor(if (filled) skin.onPrimary else skin.primary)
            background = if (filled) round(skin.primary, 14) else round(skin.bg, 14)
            setOnClickListener { onClick() }
        }
        parent.addView(b, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
        return b
    }

    private fun input(parent: LinearLayout, hint: String, value: String = ""): EditText {
        val e = EditText(this).apply {
            setSingleLine()
            this.hint = hint
            setText(value)
            setTextColor(skin.text)
            setHintTextColor(skin.sub)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
        }
        parent.addView(e, LinearLayout.LayoutParams(-1, -2))
        return e
    }

    // ---------- вкладка «Подключение» ----------

    private fun connectTab(): View {
        val (sv, col) = page()
        val c = card(col)
        label(c, "Сервер", 12f, sub = true)
        serverView = label(c, Settings.currentServer().name, 20f, bold = true)
        statusView = label(c, BuhtaState.status, 16f)
        connectBtn = button(c, "Подключить") {
            if (BuhtaState.active) stopVpn() else startVpn()
        }
        val d = card(col)
        label(d, "Как это работает", 14f, bold = true)
        label(d, "Трафик телефона идёт через звонок Saby Meet на твой сервер. " +
            "Серверы и их комнаты добавляются на вкладке «Серверы».", 13f, sub = true)
        return sv
    }

    private fun startVpn() {
        val room = Settings.currentServer().room
        if (!Settings.UUID_RE.matches(room)) {
            Toast.makeText(this, "У сервера неверный UUID комнаты", Toast.LENGTH_SHORT).show()
            return
        }
        val prep = VpnService.prepare(this)
        if (prep != null) startActivityForResult(prep, 1) else launch(room)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) launch(Settings.currentServer().room)
    }

    private fun launch(room: String) {
        startService(Intent(this, BuhtaVpnService::class.java)
            .putExtra("room", room)
            .putExtra("key", Settings.currentServer().key))
        renderLive()
    }

    private fun stopVpn() {
        startService(Intent(this, BuhtaVpnService::class.java).setAction("stop"))
    }

    // ---------- вкладка «Серверы» ----------

    private fun serversTab(): View {
        val (sv, col) = page()
        val list = Settings.servers()
        val sel = Settings.selected.coerceIn(0, list.size - 1)

        list.forEachIndexed { i, s ->
            val c = card(col)
            label(c, (if (i == sel) "● " else "○ ") + s.name, 16f, bold = true)
            label(c, s.room, 11f, sub = true)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            c.addView(row, LinearLayout.LayoutParams(-1, -2))
            if (i != sel) {
                button(row, "Выбрать") {
                    if (BuhtaState.active) {
                        Toast.makeText(this, "Сначала отключись", Toast.LENGTH_SHORT).show()
                    } else {
                        Settings.selected = i
                        showTab(1)
                    }
                }
            }
            button(row, "Удалить", filled = false) {
                if (list.size <= 1) {
                    Toast.makeText(this, "Нужен хотя бы один сервер", Toast.LENGTH_SHORT).show()
                } else {
                    list.removeAt(i)
                    Settings.saveServers(list)
                    Settings.selected = 0
                    showTab(1)
                }
            }
            for (k in 0 until row.childCount) {
                row.getChildAt(k).layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) }
            }
        }

        val add = card(col)
        label(add, "Добавить сервер", 15f, bold = true)
        label(add, "Вставь ключ buhta:// из панели (с шифрованием) либо ссылку на гостевую комнату Saby Meet / UUID.", 12f, sub = true)
        val nameE = input(add, "Название (необязательно)")
        val linkE = input(add, "Ссылка или UUID комнаты")
        button(add, "Вставить из буфера", filled = false) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            linkE.setText(t.trim())
        }
        button(add, "Добавить") {
            val raw = linkE.text.toString().trim()
            val parsed = Settings.parseKey(raw)
            val m = if (parsed != null) null else Settings.UUID_RE.find(raw)
            if (parsed == null && m == null) {
                Toast.makeText(this, "Не нашёл ключ или UUID комнаты", Toast.LENGTH_SHORT).show()
            } else {
                val fallback = parsed?.first?.ifEmpty { null } ?: "Сервер ${list.size + 1}"
                val name = nameE.text.toString().trim().ifEmpty { fallback }
                if (parsed != null) list.add(Settings.Server(name, parsed.second, raw))
                else list.add(Settings.Server(name, m!!.value))
                Settings.saveServers(list)
                showTab(1)
            }
        }
        return sv
    }

    // ---------- вкладка «Настройки» ----------

    private fun settingsTab(): View {
        val (sv, col) = page()

        val dnsCard = card(col)
        label(dnsCard, "DNS", 15f, bold = true)
        label(dnsCard, "Какой DNS-провайдер использовать внутри туннеля. Применяется при следующем подключении.", 12f, sub = true)
        val customE = input(dnsCard, "Свой DNS, например 9.9.9.9 1.1.1.1", Settings.customDns)
        customE.visibility = if (Settings.dnsKey == "custom") View.VISIBLE else View.GONE
        Settings.dnsPresets.forEach { p ->
            val on = p.key == Settings.dnsKey
            button(dnsCard, (if (on) "● " else "○ ") + p.title, filled = on) {
                Settings.dnsKey = p.key
                Settings.customDns = customE.text.toString()
                showTab(2)
            }
        }
        customE.setOnFocusChangeListener { _, has -> if (!has) Settings.customDns = customE.text.toString() }

        val skinCard = card(col)
        label(skinCard, "Скин", 15f, bold = true)
        Themes.names.forEach { (k, n) ->
            val on = k == Settings.palette
            button(skinCard, (if (on) "● " else "○ ") + n, filled = on) {
                Settings.palette = k
                recreate()
            }
        }
        label(skinCard, "Тема", 13f, sub = true)
        listOf("system" to "Как в системе", "light" to "Светлая", "dark" to "Тёмная").forEach { (k, n) ->
            val on = k == Settings.mode
            button(skinCard, (if (on) "● " else "○ ") + n, filled = on) {
                Settings.mode = k
                recreate()
            }
        }
        return sv
    }

    // ---------- вкладка «Логи» ----------

    private fun logsTab(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(row, LinearLayout.LayoutParams(-1, -2))
        button(row, "Копировать", filled = false) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("buhta-log", BuhtaState.text()))
            Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
        }
        button(row, "Очистить", filled = false) {
            BuhtaState.clear()
            renderLive()
        }
        for (k in 0 until row.childCount) {
            row.getChildAt(k).layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) }
        }
        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(skin.text)
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    // ---------- вкладка «Инфо» ----------

    private fun infoTab(): View {
        val (sv, col) = page()
        val c = card(col)
        label(c, "Бухта", 18f, bold = true)
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        label(c, "Версия $ver", 13f, sub = true)
        label(c, "VPN, который гонит IP-трафик через звонок Saby Meet на твой сервер.", 13f)
        val d = card(col)
        label(d, "Состояние", 14f, bold = true)
        label(d, "DNS: " + Settings.dnsList().joinToString(", "), 13f, sub = true)
        label(d, "Сервер: " + Settings.currentServer().name, 13f, sub = true)
        return sv
    }
}
