package com.lanshare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
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

    companion object {
        const val ACTION_START = "com.lanshare.app.START"
        const val ACTION_STOP = "com.lanshare.app.STOP"
        const val ACTION_RESTART = "com.lanshare.app.RESTART"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_TREE_URI = "extra_tree_uri"

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
                restartServer(port, treeUri)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServerOnly()
        super.onDestroy()
    }

    private fun restartServer(port: Int, treeUri: String?) {
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
            server = HttpServer(storage, pm, port)
            runCatching { server?.start() }
                .onSuccess {
                    val ip = localIpAddress() ?: "127.0.0.1"
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
        _state.value = _state.value.copy(running = false, status = "stopped", url = "")
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
