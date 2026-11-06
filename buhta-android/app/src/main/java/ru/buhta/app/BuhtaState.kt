package ru.buhta.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Общее состояние для окна приложения и VPN-сервиса (один процесс). */
object BuhtaState {
    @Volatile var active = false
    @Volatile var connected = false
    @Volatile var status = "Отключено"
    @Volatile var ip = ""
    @Volatile var connectedAt = 0L
    val rxBytes = java.util.concurrent.atomic.AtomicLong()
    val txBytes = java.util.concurrent.atomic.AtomicLong()
    @Volatile var onChange: (() -> Unit)? = null

    /** Счётчик для Compose: растёт при любом изменении состояния или логов. */
    var tick by mutableIntStateOf(0)
        private set

    private val lines = ArrayDeque<String>()

    private fun bump() { tick = tick + 1; onChange?.invoke() }

    fun update(s: String) {
        status = s
        log("▶ $s")
    }

    fun setConnected(c: Boolean) {
        connected = c
        connectedAt = if (c) System.currentTimeMillis() else 0L
        bump()
    }

    fun log(line: String) {
        synchronized(lines) {
            lines.addLast(line.take(220))
            while (lines.size > 300) lines.removeFirst()
        }
        bump()
    }

    fun clear() {
        synchronized(lines) { lines.clear() }
        bump()
    }

    fun lines(): List<String> = synchronized(lines) { lines.toList() }
    fun text(): String = synchronized(lines) { lines.takeLast(200).joinToString("\n") }
}
