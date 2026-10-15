package com.csqtt.client.wb

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

/**
 * Separate VPN service for the WB Stream test. It never touches Mayak's TunVpnService:
 * the screen refuses to start it while Mayak is on, and Mayak's tunnel code knows nothing about it.
 */
class WbVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.csqtt.client.wb.START"
        const val ACTION_STOP = "com.csqtt.client.wb.STOP"
        const val EXTRA_ROOM = "room"

        private const val CHANNEL_ID = "wbstream_test"
        private const val NOTIFICATION_ID = 4711
        private const val SOCKS_PORT = 10818
        private const val MTU = 1500

        fun start(context: Context, roomId: String) {
            val intent = Intent(context, WbVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ROOM, roomId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WbVpnService::class.java).setAction(ACTION_STOP))
        }
    }

    private var relay: WbRelay? = null
    @Volatile private var tunUp = false
    @Volatile private var stopping = false
    private val socksUser = "wb" + UUID.randomUUID().toString().take(8)
    private val socksPass = UUID.randomUUID().toString().replace("-", "")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown("Выключено")
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val room = intent.getStringExtra(EXTRA_ROOM).orEmpty()
                startForegroundCompat("Запуск WB Stream…")
                if (room.isBlank()) {
                    WbState.log("Нет ссылки на комнату")
                    fail("Нет ссылки на комнату")
                    return START_NOT_STICKY
                }
                begin(room)
            }
        }
        return START_NOT_STICKY
    }

    private fun begin(room: String) {
        stopping = false
        releaseTun()
        relay?.stop()
        WbState.phase.value = WbPhase.STARTING
        WbState.detail.value = ""
        relay = WbRelay(applicationInfo.nativeLibraryDir, SOCKS_PORT, socksUser, socksPass, ::onRelayStatus)
            .also { it.start(room) }
    }

    private fun onRelayStatus(status: String) {
        if (stopping) return
        when {
            status == "READY" -> WbState.phase.value = WbPhase.STARTING
            status == "CONNECTING" -> setPhase(WbPhase.CONNECTING)
            status == "RECONNECTING" || status == "TUNNEL_LOST" -> setPhase(WbPhase.RECONNECTING)
            status == "TUNNEL_CONNECTED" -> {
                if (!tunUp && !establishTun()) return
                setPhase(WbPhase.CONNECTED)
            }
            status.startsWith("ERROR:") -> {
                val reason = status.removePrefix("ERROR:")
                WbState.log("Ошибка: $reason")
                fail(reason)
            }
            else -> WbState.log("Статус: $status")
        }
    }

    private fun establishTun(): Boolean {
        val builder = Builder()
            .setSession("Маяк · WB Stream (тест)")
            .addAddress("10.18.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .setMtu(MTU)
        // IPv6 is captured too and rejected inside tun2socks, so apps fall back to IPv4 through the call.
        runCatching {
            builder.addAddress("fd00:18::2", 128)
            builder.addRoute("::", 0)
        }
        // The relay process belongs to this app; keep it outside the tunnel so the call itself is not looped.
        runCatching { builder.addDisallowedApplication(packageName) }
        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            WbState.log("Не удалось поднять VPN: ${e.message}")
            null
        }
        if (pfd == null) {
            fail("Android не дал включить VPN")
            return false
        }
        // tun2socks takes ownership of the descriptor and closes it on stop.
        val fd = pfd.detachFd()
        return try {
            Androidbind.startTun2Socks(fd.toLong(), MTU.toLong(), SOCKS_PORT.toLong(), socksUser, socksPass)
            tunUp = true
            WbState.log("VPN поднят, трафик идёт через WB Stream")
            true
        } catch (e: Exception) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            WbState.log("tun2socks: ${e.message}")
            fail("tun2socks: ${e.message}")
            false
        }
    }

    private fun setPhase(phase: WbPhase) {
        WbState.phase.value = phase
        updateNotification(phase.label)
    }

    private fun fail(reason: String) {
        shutdown(null)
        WbState.phase.value = WbPhase.FAILED
        WbState.detail.value = reason
    }

    private fun releaseTun() {
        if (tunUp) {
            tunUp = false
            runCatching { Androidbind.stopTun2Socks() }
        }
    }

    private fun shutdown(logLine: String?) {
        stopping = true
        relay?.stop()
        relay = null
        releaseTun()
        if (logLine != null) {
            WbState.phase.value = WbPhase.OFF
            WbState.detail.value = ""
            WbState.log(logLine)
        }
        runCatching {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onRevoke() {
        WbState.log("Android отключил VPN (включён другой VPN?)")
        shutdown("Выключено")
    }

    override fun onDestroy() {
        if (!stopping) shutdown("Выключено")
        super.onDestroy()
    }

    private fun startForegroundCompat(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "WB Stream (тест)", NotificationManager.IMPORTANCE_LOW),
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
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, WbStreamActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("WB Stream (тест)")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
