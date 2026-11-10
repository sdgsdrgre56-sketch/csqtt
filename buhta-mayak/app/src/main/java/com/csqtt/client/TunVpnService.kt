// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TunVpnService : VpnService() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val vpnMutex = Mutex()
    private val interfaceLock = Any()
    private var vpnInterface: ParcelFileDescriptor? = null
    private var sendJob: Job? = null
    private var activeClientIp: String? = null
    private var activeDns: String? = null
    private var recoveryAttempts = 0
    private var stopRequested = false
    @Volatile
    private var serviceDestroyed = false

    companion object {
        private const val TAG = "TunVPN"

        @Volatile
        var instance: TunVpnService? = null
            private set

        @Volatile
        var tunFd: Int = -1
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "TunVpnService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            "START" -> {
                stopRequested = false
                val clientIp = intent.getStringExtra("client_ip") ?: "10.66.66.2"
                val dns = intent.getStringExtra("dns") ?: "1.1.1.1"
                val forceRebuild = intent.getBooleanExtra("force_rebuild", false)
                if (!forceRebuild) recoveryAttempts = 0
                serviceScope.launch {
                    startVpn(clientIp, dns, forceRebuild)
                }
            }
            "STOP" -> {
                stopRequested = true
                serviceScope.launch {
                    vpnMutex.withLock { stopVpn() }
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    private suspend fun startVpn(clientIp: String, dns: String, forceRebuild: Boolean) {
        vpnMutex.withLock {
        try {
            val existing = synchronized(interfaceLock) {
                vpnInterface?.takeIf {
                    TunRecoveryPolicy.shouldReuse(
                        hasInterface = true,
                        descriptorValid = it.fileDescriptor.valid(),
                        serviceDestroyed = serviceDestroyed,
                        forceRebuild = forceRebuild,
                        activeClientIp = activeClientIp,
                        activeDns = activeDns,
                        requestedClientIp = clientIp,
                        requestedDns = dns,
                    )
                }
            }
            if (existing != null) {
                sendTunFd(existing)
                return
            }

            if (VpnService.prepare(this) != null) {
                failVpn("разрешение Android VPN не выдано")
                return
            }

            val settingsStore = SettingsStore(applicationContext)
            val savedExcluded = settingsStore.excludedApps.first()
            val isWhitelist = settingsStore.isWhitelist.first()
            val userSelected = savedExcluded.split(",").filter { it.isNotEmpty() }.toSet()
            val transportPackages = setOf(
                applicationContext.packageName,
                CsqttConstants.General.PACKAGE_VK,
                CsqttConstants.General.PACKAGE_VK_CALLS
            )

            val builder = Builder()
                .setSession("Бухта")
                .setMtu(1200)
                .addAddress(clientIp, 32)
                .addRoute("0.0.0.0", 0)

            val noDns = dns.isBlank() || dns.trim().equals("none", ignoreCase = true)
            var validDnsServers = 0
            if (!noDns) {
                dns.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { dnsServer ->
                    try {
                        builder.addDnsServer(dnsServer)
                        validDnsServers++
                    } catch (e: Exception) {
                        Log.w(TAG, "Invalid DNS server: $dnsServer")
                    }
                }
                if (validDnsServers == 0) {
                    failVpn("сервер передал некорректный DNS: $dns")
                    return
                }
            } else {
                Log.d(TAG, "DNS not set by app (системный DNS)")
            }

            if (isWhitelist) {
                val installedIncluded = userSelected
                    .filter { pkg -> 
                        pkg !in transportPackages && isPackageInstalled(pkg) 
                    }
                    .toSet()
                if (installedIncluded.isEmpty()) {
                    failVpn("Whitelist пуст: выберите хотя бы одно установленное приложение")
                    return
                } else {
                    var includedCount = 0
                    installedIncluded.forEach { pkg ->
                        try {
                            builder.addAllowedApplication(pkg)
                            includedCount++
                        } catch (error: Exception) {
                            Log.w(TAG, "Unable to include $pkg in VPN", error)
                        }
                    }
                    if (includedCount == 0) {
                        failVpn("Android не разрешил добавить выбранные приложения в whitelist")
                        return
                    }
                }
            } else {
                try {
                    builder.addDisallowedApplication(applicationContext.packageName)
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to exclude CSQTT UID from its VPN", e)
                    failVpn("Android не разрешил исключить CSQTT из собственного VPN")
                    return
                }
                val excluded = transportPackages.toMutableSet()
                excluded.addAll(userSelected)
                excluded
                    .filter { it != applicationContext.packageName && isPackageInstalled(it) }
                    .forEach { pkg ->
                    try { builder.addDisallowedApplication(pkg) } catch (_: Exception) {}
                }
            }

            val pfd = builder.establish()
            if (pfd == null) {
                Log.e(TAG, "VPN permission not granted")
                failVpn("Android не создал VPN-интерфейс; проверьте разрешение VPN и Always-on VPN")
                return
            }

            var previousInterface: ParcelFileDescriptor? = null
            val accepted = synchronized(interfaceLock) {
                if (serviceDestroyed) {
                    false
                } else {
                    previousInterface = vpnInterface
                    vpnInterface = pfd
                    tunFd = pfd.fd
                    activeClientIp = clientIp
                    activeDns = dns
                    true
                }
            }
            if (!accepted) {
                pfd.close()
                return
            }
            sendJob?.cancel()
            if (previousInterface !== pfd) {
                runCatching { previousInterface?.close() }
            }
            Log.d(TAG, "VPN interface established: IP=$clientIp DNS=$dns fd=$tunFd")

            sendTunFd(pfd, dns)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN: ${e.message}", e)
            failVpn(e.message ?: e.javaClass.simpleName)
        }
        }
    }

    @Volatile private var pumpSocket: java.net.DatagramSocket? = null
    private val upBytes = java.util.concurrent.atomic.AtomicLong()
    private val downBytes = java.util.concurrent.atomic.AtomicLong()

    /** Бухта: гоняем IP-пакеты TUN <-> локальные UDP-порты движка. При нескольких
     *  комнатах (потоках) раскладываем исходящие пакеты по всем готовым портам по кругу. */
    private fun sendTunFd(pfd: ParcelFileDescriptor, dns: String? = null) {
        sendJob?.cancel()
        runCatching { pumpSocket?.close() }
        val sock = java.net.DatagramSocket()
        pumpSocket = sock
        upBytes.set(0); downBytes.set(0)
        val local = java.net.InetAddress.getByName("127.0.0.1")
        val mainTarget = java.net.InetSocketAddress(local, BuhtaLinks.BASE_PORT)
        val input = java.io.FileInputStream(pfd.fileDescriptor)
        val output = java.io.FileOutputStream(pfd.fileDescriptor)
        // keepalive: будим все готовые линки, чтобы они знали обратный адрес насоса
        Thread {
            try {
                while (!sock.isClosed) {
                    for (port in BuhtaLinks.readyPorts()) {
                        try { sock.send(java.net.DatagramPacket(BuhtaLinks.KEEP_MAGIC, BuhtaLinks.KEEP_MAGIC.size, local, port)) } catch (_: Exception) {}
                    }
                    Thread.sleep(3000)
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
        Thread {
            val buf = ByteArray(32768)
            try {
                while (!sock.isClosed) {
                    val n = input.read(buf)
                    if (n <= 0) continue
                    val k = BuhtaLinks.pickLink(buf, n)
                    val dst = if (k == 0) mainTarget else java.net.InetSocketAddress(local, BuhtaLinks.BASE_PORT + k)
                    try {
                        sock.send(java.net.DatagramPacket(buf, 0, n, dst))
                        upBytes.addAndGet(n.toLong())
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }.start()
        Thread {
            val buf = ByteArray(32768)
            try {
                while (!sock.isClosed) {
                    val p = java.net.DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    output.write(buf, 0, p.length)
                    downBytes.addAndGet(p.length.toLong())
                }
            } catch (_: Exception) {
            }
        }.start()
        sendJob = serviceScope.launch {
            TunnelManager.onVpnInterfaceReady(dns)
            while (true) {
                delay(1000)
                TunnelManager.reportBuhtaTraffic(upBytes.get(), downBytes.get())
            }
        }
    }

    private suspend fun recoverAfterFdFailure(failedInterface: ParcelFileDescriptor) {
        val recoveryConfig = synchronized(interfaceLock) {
            if (vpnInterface !== failedInterface || serviceDestroyed) {
                null
            } else {
                runCatching { vpnInterface?.close() }
                vpnInterface = null
                tunFd = -1
                val config = activeClientIp?.let { ip -> activeDns?.let { dns -> ip to dns } }
                activeClientIp = null
                activeDns = null
                config
            }
        } ?: return
        TunnelManager.onVpnInterfaceStopped()
        if (recoveryAttempts >= 2) {
            failVpn("не удалось передать TUN движку после повторных попыток")
            return
        }
        recoveryAttempts++
        delay(500L * recoveryAttempts)
        startVpn(recoveryConfig.first, recoveryConfig.second, forceRebuild = true)
    }

    private fun failVpn(message: String) {
        stopRequested = true
        stopVpn()
        TunnelManager.onVpnTerminalFailure(message)
    }

    private fun stopVpn() {
        runCatching { pumpSocket?.close() }
        pumpSocket = null
        sendJob?.cancel()
        sendJob = null
        synchronized(interfaceLock) {
            try {
                vpnInterface?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing VPN interface: ${e.message}")
            }
            vpnInterface = null
            tunFd = -1
            activeClientIp = null
            activeDns = null
        }
        TunnelManager.onVpnInterfaceStopped()
        Log.d(TAG, "VPN interface closed")
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun onDestroy() {
        val unexpected = !stopRequested && TunnelManager.running.value
        serviceDestroyed = true
        serviceJob.cancel()
        stopVpn()
        instance = null
        if (unexpected) {
            TunnelManager.onVpnTerminalFailure("VPN-сервис был остановлен Android")
        }
        super.onDestroy()
    }

    override fun onRevoke() {
        stopRequested = true
        serviceDestroyed = true
        serviceJob.cancel()
        stopVpn()
        instance = null
        TunnelManager.onVpnTerminalFailure("разрешение Android VPN было отозвано")
        stopSelf()
    }
}
