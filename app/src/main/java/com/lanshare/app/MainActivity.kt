package com.lanshare.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.TypedValue
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences

    private lateinit var tvUrl: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvFolder: TextView
    private lateinit var etPort: EditText
    private lateinit var qrImage: ImageView

    private var currentUrl = ""
    private var currentTreeUri: String? = null
    private var askedManageAllFilesOnce = false
    private var showHotspotQr = false
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        currentTreeUri = prefs.getString(KEY_FOLDER_URI, null)

        tvUrl = findViewById(R.id.tvUrl)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        tvStatus = findViewById(R.id.tvStatus)
        tvFolder = findViewById(R.id.tvFolder)
        etPort = findViewById(R.id.etPort)
        qrImage = findViewById(R.id.ivQr)

        val btnOpenBrowser: Button = findViewById(R.id.btnOpenBrowser)
        val btnApplyPort: Button = findViewById(R.id.btnApplyPort)
        val btnChangeFolder: Button = findViewById(R.id.btnChangeFolder)
        val btnStartServer: Button = findViewById(R.id.btnStartServer)
        val btnStopServer: Button = findViewById(R.id.btnStopServer)
        val btnEnableHotspot: Button = findViewById(R.id.btnEnableHotspot)
        val btnShowHotspotQr: Button = findViewById(R.id.btnShowHotspotQr)

        val port = prefs.getInt(KEY_PORT, 1390)
        etPort.setText(port.toString())
        tvSubtitle.text = getString(R.string.subtitle)

        val storage = StorageAccess(this, currentTreeUri)
        storage.ensureRootExists()
        tvFolder.text = storage.displayPath()
        tvUrl.text = "http://0.0.0.0:$port"
        renderQr(tvUrl.text.toString())

        btnOpenBrowser.setOnClickListener {
            if (currentUrl.isBlank()) {
                toast("Server URL not ready")
                return@setOnClickListener
            }
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
        }

        btnApplyPort.setOnClickListener {
            val p = etPort.text.toString().trim().toIntOrNull()
            if (p == null || p !in 1024..65535) {
                etPort.error = "Port must be 1024-65535"
                return@setOnClickListener
            }
            prefs.edit().putInt(KEY_PORT, p).apply()
            showHotspotQr = false
            startOrRestartService(p)
        }

        btnStartServer.setOnClickListener {
            val p = etPort.text.toString().trim().toIntOrNull()
            if (p == null || p !in 1024..65535) {
                etPort.error = "Port must be 1024-65535"
                return@setOnClickListener
            }
            prefs.edit().putInt(KEY_PORT, p).apply()
            if (hasRequiredStoragePermission()) {
                showHotspotQr = false
                startOrRestartService(p)
            } else {
                ensurePermissionAndStart()
            }
        }

        btnStopServer.setOnClickListener {
            val stopIntent = Intent(this, ServerService::class.java).apply {
                action = ServerService.ACTION_STOP
            }
            runCatching { startService(stopIntent) }
            currentUrl = ""
            tvUrl.text = getString(R.string.server_not_running)
            tvStatus.text = "Status: stopped"
            tvStatus.setTextColor(resources.getColor(R.color.status_stopped, theme))
            showHotspotQr = false
            renderQr("")
        }

        btnEnableHotspot.setOnClickListener {
            val tetherIntent = Intent("android.settings.TETHER_SETTINGS")
            val fallbackIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
            runCatching { startActivity(tetherIntent) }
                .onFailure { runCatching { startActivity(fallbackIntent) } }
            toast("Open hotspot settings and turn it on")
        }

        btnShowHotspotQr.setOnClickListener {
            showHotspotQrDialog()
        }

        btnChangeFolder.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }
            startActivityForResult(intent, REQ_PICK_FOLDER)
        }
    }

    override fun onResume() {
        super.onResume()
        observeServerState()
        if (!hasRequiredStoragePermission()) {
            ensurePermissionAndStart()
        }
    }

    override fun onPause() {
        collectJob?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
            currentTreeUri = uri.toString()
            prefs.edit().putString(KEY_FOLDER_URI, currentTreeUri).apply()
            tvFolder.text = currentTreeUri
            startOrRestartService()
            return
        }
        if (requestCode == REQ_MANAGE_ALL_FILES) {
            askedManageAllFilesOnce = false
            if (hasRequiredStoragePermission()) {
                toast("Permission granted. Tap Start Server.")
            } else {
                toast("All files access is required on Android 11+")
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE_PERMS) {
            if (hasRequiredStoragePermission()) {
                toast("Permission granted. Tap Start Server.")
            } else {
                toast("Storage permission is required")
            }
        }
    }

    private fun observeServerState() {
        collectJob?.cancel()
        collectJob = uiScope.launch {
            ServerService.state.collect { state ->
                tvStatus.text = if (state.running) "Status: running" else "Status: stopped"
                tvStatus.setTextColor(resources.getColor(if (state.running) R.color.status_running else R.color.status_stopped, theme))
                currentUrl = state.url
                tvUrl.text = if (state.url.isBlank()) getString(R.string.server_not_running) else state.url
                if (state.folderDisplay.isNotBlank()) tvFolder.text = state.folderDisplay
                if (!showHotspotQr) {
                    renderQr(tvUrl.text.toString())
                }
            }
        }
    }

    private fun ensurePermissionAndStart() {
        if (hasRequiredStoragePermission()) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (askedManageAllFilesOnce) return
            askedManageAllFilesOnce = true
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivityForResult(intent, REQ_MANAGE_ALL_FILES)
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                REQ_STORAGE_PERMS
            )
        }
    }

    private fun hasRequiredStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startOrRestartService(forcedPort: Int? = null) {
        val port = forcedPort ?: prefs.getInt(KEY_PORT, 1390)
        val intent = Intent(this, ServerService::class.java).apply {
            action = ServerService.ACTION_RESTART
            putExtra(ServerService.EXTRA_PORT, port)
            putExtra(ServerService.EXTRA_TREE_URI, currentTreeUri)
        }
        runCatching { startForegroundService(intent) }
            .onFailure {
                Log.e("LanShare", "Failed to start foreground service", it)
                toast("Failed to start server service: ${it.message}")
            }
    }

    private fun renderQr(content: String) {
        if (content.isBlank()) {
            qrImage.setImageBitmap(null)
            return
        }
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 200f, resources.displayMetrics).toInt()
        runCatching { QrCodeGenerator.render(content, px) }
            .onSuccess { qrImage.setImageBitmap(it) }
            .onFailure { qrImage.setImageBitmap(null) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showHotspotQrDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(0))
        }

        val ssidInput = EditText(this).apply {
            hint = "Hotspot name (SSID)"
            setText(prefs.getString(KEY_HOTSPOT_SSID, ""))
        }
        val passInput = EditText(this).apply {
            hint = "Hotspot password"
            setText(prefs.getString(KEY_HOTSPOT_PASS, ""))
        }

        container.addView(ssidInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(passInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        AlertDialog.Builder(this)
            .setTitle("Hotspot QR")
            .setMessage("Enter your hotspot details")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Show QR") { _, _ ->
                val ssid = ssidInput.text.toString().trim()
                val pass = passInput.text.toString()
                if (ssid.isBlank()) {
                    toast("SSID is required")
                    return@setPositiveButton
                }
                prefs.edit().putString(KEY_HOTSPOT_SSID, ssid).putString(KEY_HOTSPOT_PASS, pass).apply()
                val payload = buildWifiQrPayload(ssid, pass)
                showHotspotQr = true
                renderQr(payload)
                toast("Hotspot QR generated")
            }
            .show()
    }

    private fun buildWifiQrPayload(ssid: String, password: String): String {
        val escSsid = escapeWifiQrField(ssid)
        val escPass = escapeWifiQrField(password)
        return if (password.isBlank()) {
            "WIFI:T:nopass;S:$escSsid;;"
        } else {
            "WIFI:T:WPA;S:$escSsid;P:$escPass;;"
        }
    }

    private fun escapeWifiQrField(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace(":", "\\:")
            .replace("\"", "\\\"")
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        private const val PREFS = "lan_share_prefs"
        private const val KEY_PORT = "port"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_HOTSPOT_SSID = "hotspot_ssid"
        private const val KEY_HOTSPOT_PASS = "hotspot_pass"
        private const val REQ_STORAGE_PERMS = 1200
        private const val REQ_PICK_FOLDER = 1201
        private const val REQ_MANAGE_ALL_FILES = 1202
    }
}
