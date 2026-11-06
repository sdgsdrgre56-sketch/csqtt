package ru.buhta.app

/** Общее состояние для окна приложения и VPN-сервиса (один процесс). */
object BuhtaState {
    @Volatile var active = false
    @Volatile var connected = false
    @Volatile var status = "Отключено"
    @Volatile var onChange: (() -> Unit)? = null

    private val lines = ArrayDeque<String>()

    fun update(s: String) {
        status = s
        log("▶ $s")
    }

    fun log(line: String) {
        synchronized(lines) {
            lines.addLast(line.take(220))
            while (lines.size > 200) lines.removeFirst()
        }
        onChange?.invoke()
    }

    fun clear() {
        synchronized(lines) { lines.clear() }
        onChange?.invoke()
    }

    fun text(): String = synchronized(lines) { lines.takeLast(80).joinToString("\n") }
}
