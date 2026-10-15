package app.mayak.dion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidbind.Androidbind
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * VPN over several DION rooms at once. Every channel is a joiner process with its own SOCKS port;
 * the balancer spreads connections over the channels that are up; tun2socks feeds it the phone's traffic.
 * A channel that drops is restarted on its own, the others keep working.
 */
class DionVpnService : VpnService() {

    companion object {
        private const val ACTION_START = "app.mayak.dion.START"
        private const val ACTION_STOP = "app.mayak.dion.STOP"
        private const val EXTRA_SLUGS = "slugs"
        private const val CHANNEL_ID = "dion_vpn"
        private const val NOTIFICATION_ID = 5150
        private const val MUX_PORT = 10818
        private const val FIRST_CHANNEL_PORT = 10830
        private const val MTU = 1500
        /** Without a single working channel by then, the start counts as failed. */
        private const val START_TIMEOUT_SEC = 120L

        fun start(context: Context, slugs: List<String>) {
            val intent = Intent(context, DionVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SLUGS, slugs.toTypedArray())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DionVpnService::class.java).setAction(ACTION_STOP))
        }
    }

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dion-service").apply { isDaemon = true } }
    private val relays = mutableMapOf<Int, ChannelRelay>()
    private val slugs = mutableMapOf<Int, String>()
    private val retries = mutableMapOf<Int, Int>()
    private val restartTasks = mutableMapOf<Int, ScheduledFuture<*>>()
    private var startTimeout: ScheduledFuture<*>? = null
    private var mux: Mux? = null
    private var lastStat: Pair<Long, List<Mux.MuxStat>>? = null
    @Volatile private var tunUp = false
    @Volatile private var stopping = false
    @Volatile private var everUp = false

    private val muxUser = "m" + UUID.randomUUID().toString().take(8)
    private val muxPass = UUID.randomUUID().toString().replace("-", "")
    private val chUser = "c" + UUID.randomUUID().toString().take(8)
    private val chPass = UUID.randomUUID().toString().replace("-", "")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> worker.execute { shutdown("Выключено") }
            ACTION_START -> {
                startForegroundCompat("Запуск…")
                val list = intent.getStringArrayExtra(EXTRA_SLUGS)?.toList().orEmpty().filter { it.isNotBlank() }
                worker.execute { begin(list) }
            }
        }
        return START_NOT_STICKY
    }

    private fun begin(list: List<String>) {
        stopAll()
        stopping = false
        everUp = false
        if (list.isEmpty()) {
            fail("Нет ни одной ссылки на комнату")
            return
        }
        DionState.phase.value = Phase.STARTING
        DionState.detail.value = ""
        DionState.channels.value = list.indices.map { ChannelInfo(it + 1, ChannelState.CONNECTING) }
        val nativeDir = applicationInfo.nativeLibraryDir
        val ports = list.indices.map { FIRST_CHANNEL_PORT + it }

        val m = Mux(nativeDir, MUX_PORT, muxUser, muxPass, ports, chUser, chPass, ::onStats) {
            worker.execute { if (!stopping) fail("Балансир остановился") }
        }
        if (!m.start()) {
            fail("Нет балансира в сборке")
            return
        }
        mux = m

        list.forEachIndexed { i, slug ->
            val n = i + 1
            slugs[n] = slug
            retries[n] = 0
            val relay = ChannelRelay(n, nativeDir, ports[i], chUser, chPass) { ch, status ->
                worker.execute { onChannelStatus(ch, status) }
            }
            relays[n] = relay
            // A short pause between channels: joining all rooms in the same second looks like a flood.
            worker.schedule(Runnable { if (!stopping) relay.start(slug) }, (i * 700).toLong(), TimeUnit.MILLISECONDS)
        }
        DionState.log("Запускаю ${list.size} каналов DION")
        setPhase(Phase.CONNECTING)
        startTimeout = worker.schedule(Runnable {
            if (!stopping && !everUp) fail("Ни один канал не подключился за ${START_TIMEOUT_SEC / 60} мин")
        }, START_TIMEOUT_SEC, TimeUnit.SECONDS)
    }

    private fun onChannelStatus(n: Int, status: String) {
        if (stopping || relays[n] == null) return
        when {
            status == "TUNNEL_CONNECTED" -> {
                retries[n] = 0
                mux?.setUp(n, true)
                setChannel(n, ChannelState.UP, "")
                DionState.log("[$n] канал работает")
                if (!tunUp && !establishTun()) return
                everUp = true
            }
            status == "READY" || status == "CONNECTING" -> setChannel(n, ChannelState.CONNECTING, "")
            status == "RECONNECTING" || status == "TUNNEL_LOST" -> {
                mux?.setUp(n, false)
                setChannel(n, ChannelState.RECONNECTING, "")
            }
            status.startsWith("ERROR:") -> {
                val reason = status.removePrefix("ERROR:")
                mux?.setUp(n, false)
                relays[n]?.stop()
                val attempt = (retries[n] ?: 0) + 1
                retries[n] = attempt
                val delay = minOf(5L * attempt, 60L)
                setChannel(n, ChannelState.FAILED, reason)
                DionState.log("[$n] ошибка: $reason. Перезапуск через $delay с")
                restartTasks[n]?.cancel(false)
                restartTasks[n] = worker.schedule(Runnable {
                    if (!stopping) {
                        setChannel(n, ChannelState.CONNECTING, "")
                        slugs[n]?.let { relays[n]?.start(it) }
                    }
                }, delay, TimeUnit.SECONDS)
            }
            else -> DionState.log("[$n] $status")
        }
        refreshPhase()
    }

    private fun setChannel(n: Int, state: ChannelState, note: String) =
        DionState.updateChannel(n) { it.copy(state = state, note = note, active = if (state == ChannelState.UP) it.active else 0) }

    private fun refreshPhase() {
        val list = DionState.channels.value
        val up = list.count { it.state == ChannelState.UP }
        if (up > 0) {
            DionState.detail.value = "каналов: $up из ${list.size}"
            setPhase(Phase.CONNECTED)
        } else {
            DionState.detail.value = if (everUp) "все каналы переподключаются" else ""
            setPhase(Phase.CONNECTING)
        }
    }

    private fun onStats(stats: List<Mux.MuxStat>) {
        val now = System.nanoTime()
        val prev = lastStat
        lastStat = now to stats
        if (prev == null) return
        val secs = (now - prev.first) / 1e9
        if (secs <= 0.2) return
        stats.forEach { s ->
            val p = prev.second.firstOrNull { it.n == s.n } ?: return@forEach
            val down = ((s.rx - p.rx).coerceAtLeast(0) / secs).toLong()
            val upl = ((s.tx - p.tx).coerceAtLeast(0) / secs).toLong()
            DionState.updateChannel(s.n) { it.copy(active = s.active, down = down, up = upl) }
        }
        if (DionState.phase.value == Phase.CONNECTED) {
            val total = DionState.channels.value.sumOf { it.down }
            updateNotification("${DionState.detail.value} · ↓ ${formatSpeed(total)}")
        }
    }

    private fun establishTun(): Boolean {
        val builder = Builder()
            .setSession("DION Маяк")
            .addAddress("10.19.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .setMtu(MTU)
        // IPv6 is captured too and rejected inside tun2socks, so apps fall back to IPv4 through the calls.
        runCatching {
            builder.addAddress("fd00:19::2", 128)
            builder.addRoute("::", 0)
        }
        // The call processes belong to this app: keep them outside the tunnel, or the calls would loop.
        runCatching { builder.addDisallowedApplication(packageName) }
        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            DionState.log("Не удалось поднять VPN: ${e.message}")
            null
        }
        if (pfd == null) {
            fail("Android не дал включить VPN")
            return false
        }
        val fd = pfd.detachFd()
        return try {
            Androidbind.startTun2Socks(fd.toLong(), MTU.toLong(), MUX_PORT.toLong(), muxUser, muxPass)
            tunUp = true
            DionState.log("VPN поднят, трафик идёт через звонки DION")
            true
        } catch (e: Exception) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            fail("tun2socks: ${e.message}")
            false
        }
    }

    private fun setPhase(phase: Phase) {
        DionState.phase.value = phase
        updateNotification(phase.label + if (DionState.detail.value.isNotBlank()) " · ${DionState.detail.value}" else "")
    }

    private fun fail(reason: String) {
        DionState.log("Ошибка: $reason")
        shutdown(null)
        DionState.phase.value = Phase.FAILED
        DionState.detail.value = reason
    }

    private fun stopAll() {
        startTimeout?.cancel(false)
        startTimeout = null
        restartTasks.values.forEach { it.cancel(false) }
        restartTasks.clear()
        relays.values.forEach { it.stop() }
        relays.clear()
        slugs.clear()
        mux?.stop()
        mux = null
        lastStat = null
        if (tunUp) {
            tunUp = false
            runCatching { Androidbind.stopTun2Socks() }
        }
    }

    private fun shutdown(logLine: String?) {
        stopping = true
        stopAll()
        DionState.channels.value = DionState.channels.value.map { it.copy(state = ChannelState.OFF, active = 0, down = 0, up = 0) }
        if (logLine != null) {
            DionState.phase.value = Phase.OFF
            DionState.detail.value = ""
            DionState.log(logLine)
        }
        runCatching {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onRevoke() {
        DionState.log("Android отключил VPN (включён другой VPN?)")
        worker.execute { shutdown("Выключено") }
    }

    override fun onDestroy() {
        if (!stopping) runCatching { worker.submit(Runnable { shutdown("Выключено") }).get(3, TimeUnit.SECONDS) }
        worker.shutdown()
        super.onDestroy()
    }

    private fun startForegroundCompat(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "DION Маяк", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        if (stopping) return
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, DionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, DionVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        @Suppress("DEPRECATION")
        return builder
            .setContentTitle("DION Маяк")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Отключить", stop).build())
            .setOngoing(true)
            .build()
    }
}
