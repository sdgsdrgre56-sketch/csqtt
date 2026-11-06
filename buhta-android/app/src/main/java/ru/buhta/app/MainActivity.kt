package ru.buhta.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var roomEdit: EditText
    private lateinit var button: Button
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    private val defaultRoom = "01a1a7f6-2bd2-7d7f-b921-15125aebf1c1"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        val title = TextView(this).apply {
            text = "Бухта"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
        }
        roomEdit = EditText(this).apply {
            setSingleLine()
            hint = "Ссылка или UUID комнаты Saby Meet"
            setText(getSharedPreferences("buhta", MODE_PRIVATE).getString("room", defaultRoom))
        }
        button = Button(this)
        statusView = TextView(this).apply {
            textSize = 18f
            setPadding(0, pad, 0, pad)
        }
        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        scroll = ScrollView(this).apply { addView(logView) }

        root.addView(title)
        root.addView(roomEdit)
        root.addView(button)
        root.addView(statusView)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        button.setOnClickListener {
            if (BuhtaState.active) stopVpn() else startVpn()
        }
    }

    override fun onResume() {
        super.onResume()
        BuhtaState.onChange = { ui.post { render() } }
        render()
    }

    override fun onPause() {
        BuhtaState.onChange = null
        super.onPause()
    }

    private fun render() {
        statusView.text = (if (BuhtaState.connected) "🟢 " else if (BuhtaState.active) "🟡 " else "⚪ ") + BuhtaState.status
        button.text = if (BuhtaState.active) "Отключить" else "Подключить"
        logView.text = BuhtaState.text()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun startVpn() {
        // можно вставить целую ссылку на гостевую комнату — UUID вытащим сами
        val raw = roomEdit.text.toString().trim()
        val found = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").find(raw)
        val room = found?.value ?: raw
        if (found != null && raw != room) roomEdit.setText(room)
        if (!Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(room)) {
            Toast.makeText(this, "Нужен UUID комнаты", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences("buhta", MODE_PRIVATE).edit().putString("room", room).apply()
        val prep = VpnService.prepare(this)
        if (prep != null) {
            startActivityForResult(prep, 1)
        } else {
            launch(room)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) {
            launch(roomEdit.text.toString().trim())
        }
    }

    private fun launch(room: String) {
        startService(Intent(this, BuhtaVpnService::class.java).putExtra("room", room))
        render()
    }

    private fun stopVpn() {
        startService(Intent(this, BuhtaVpnService::class.java).setAction("stop"))
    }
}
