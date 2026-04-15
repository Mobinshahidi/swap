package com.lanshare.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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

        val port = prefs.getInt(KEY_PORT, 1390)
        etPort.setText(port.toString())
        tvSubtitle.text = "Open this address in any browser on the same Wi-Fi"

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
            startOrRestartService(p)
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
        ensurePermissionAndStart()
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
            if (hasRequiredStoragePermission()) {
                startOrRestartService()
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
                startOrRestartService()
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
                tvUrl.text = if (state.url.isBlank()) "http://0.0.0.0:${etPort.text}" else state.url
                if (state.folderDisplay.isNotBlank()) tvFolder.text = state.folderDisplay
                renderQr(tvUrl.text.toString())
            }
        }
    }

    private fun ensurePermissionAndStart() {
        if (hasRequiredStoragePermission()) {
            startOrRestartService()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
        startForegroundService(intent)
    }

    private fun renderQr(content: String) {
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 200f, resources.displayMetrics).toInt()
        runCatching { QrCodeGenerator.render(content, px) }
            .onSuccess { qrImage.setImageBitmap(it) }
            .onFailure { qrImage.setImageBitmap(null) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val PREFS = "lan_share_prefs"
        private const val KEY_PORT = "port"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val REQ_STORAGE_PERMS = 1200
        private const val REQ_PICK_FOLDER = 1201
        private const val REQ_MANAGE_ALL_FILES = 1202
    }
}
