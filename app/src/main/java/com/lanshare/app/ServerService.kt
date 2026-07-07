package com.lanshare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

data class ServerUiState(
    val running: Boolean = false,
    val url: String = "",
    val status: String = "stopped",
    val port: Int = 1390,
    val folderDisplay: String = ""
)

class ServerService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: HttpServer? = null
    private var serverJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val ACTION_START = "com.lanshare.app.START"
        const val ACTION_STOP = "com.lanshare.app.STOP"
        const val ACTION_RESTART = "com.lanshare.app.RESTART"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_TREE_URI = "extra_tree_uri"
        const val EXTRA_AUTH_USER = "extra_auth_user"
        const val EXTRA_AUTH_PASS = "extra_auth_pass"

        private val _state = MutableStateFlow(ServerUiState())
        val state: StateFlow<ServerUiState> = _state.asStateFlow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(1001, notification())

        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> stopAndShutdown()
            ACTION_START, ACTION_RESTART -> {
                val port = intent?.getIntExtra(EXTRA_PORT, 1390) ?: 1390
                val treeUri = intent?.getStringExtra(EXTRA_TREE_URI)
                val authUser = intent?.getStringExtra(EXTRA_AUTH_USER).orEmpty()
                val authPass = intent?.getStringExtra(EXTRA_AUTH_PASS).orEmpty()
                restartServer(port, treeUri, authUser, authPass)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServerOnly()
        super.onDestroy()
    }

    private fun restartServer(port: Int, treeUri: String?, authUser: String = "", authPass: String = "") {
        serverJob?.cancel()
        serverJob = serviceScope.launch {
            stopServerOnly()
            val storage = StorageAccess(this@ServerService, treeUri)
            if (!storage.ensureRootExists()) {
                _state.value = _state.value.copy(
                    running = false,
                    status = "stopped",
                    url = "",
                    port = port,
                    folderDisplay = storage.displayPath()
                )
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
                return@launch
            }
            val pm = PasswordManager(storage)
            server = HttpServer(storage, pm, port, authUser, authPass)
            runCatching { server?.start() }
                .onSuccess {
                    acquireLocks()
                    val ip = resolveBestIpAddress() ?: "127.0.0.1"
                    val url = "http://$ip:$port"
                    _state.value = ServerUiState(
                        running = true,
                        status = "running",
                        url = url,
                        port = port,
                        folderDisplay = storage.displayPath()
                    )
                    getSystemService(NotificationManager::class.java)?.notify(1001, notification())
                }
                .onFailure {
                    _state.value = ServerUiState(
                        running = false,
                        status = "stopped",
                        url = "",
                        port = port,
                        folderDisplay = storage.displayPath()
                    )
                }
        }
    }

    private fun stopAndShutdown() {
        stopServerOnly()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopServerOnly() {
        runCatching { server?.stop() }
        server = null
        releaseLocks()
        _state.value = _state.value.copy(running = false, status = "stopped", url = "")
    }

    // Keep the Wi-Fi radio out of power-save and the CPU awake while the server
    // is up. Without these, the device's radio sleeps between packets once the
    // screen is off, adding seconds of latency to every request round-trip.
    private fun acquireLocks() {
        runCatching {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(mode, "swap:wifi")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "swap:cpu")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wifiLock = null
        wakeLock = null
    }

    private fun notification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "lan_share_channel")
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("LAN Share running — tap to open app")
            .setContentText(_state.value.url.ifBlank { "Starting server..." })
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel("lan_share_channel", "LAN Share", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun resolveBestIpAddress(): String? {
        val wifi = wifiIpAddress()
        if (!wifi.isNullOrBlank()) return wifi
        return localIpAddress()
    }

    private fun wifiIpAddress(): String? {
        return runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wm?.connectionInfo?.ipAddress ?: 0
            if (ip == 0) return@runCatching null
            String.format(
                Locale.US,
                "%d.%d.%d.%d",
                ip and 0xff,
                ip shr 8 and 0xff,
                ip shr 16 and 0xff,
                ip shr 24 and 0xff
            )
        }.getOrNull()
    }

    private fun localIpAddress(): String? {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        interfaces.forEach { intf ->
            if (!intf.isUp || intf.isLoopback) return@forEach
            Collections.list(intf.inetAddresses).forEach { addr ->
                if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress
            }
        }
        return null
    }
}
