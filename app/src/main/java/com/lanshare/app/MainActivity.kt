package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.TypedValue
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
	private lateinit var prefs: SharedPreferences

	private lateinit var tvUrl: TextView
	private lateinit var tvSubtitle: TextView
	private lateinit var tvStatus: TextView
	private lateinit var tvFolder: TextView
	private lateinit var tvQrMode: TextView
	private lateinit var etPort: EditText
	private lateinit var etAuthUser: EditText
	private lateinit var etAuthPass: EditText
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
		tvQrMode = findViewById(R.id.tvQrMode)
		etPort = findViewById(R.id.etPort)
		etAuthUser = findViewById(R.id.etAuthUser)
		etAuthPass = findViewById(R.id.etAuthPass)
		qrImage = findViewById(R.id.ivQr)

		val btnOpenBrowser: Button = findViewById(R.id.btnOpenBrowser)
		val btnStartServer: Button = findViewById(R.id.btnStartServer)
		val btnChangeFolder: Button = findViewById(R.id.btnChangeFolder)
		val btnStopServer: Button = findViewById(R.id.btnStopServer)
		val btnEnableHotspot: Button = findViewById(R.id.btnEnableHotspot)
		val btnGithub: ImageButton = findViewById(R.id.btnGithub)

		val port = prefs.getInt(KEY_PORT, 1390)
		etPort.setText(port.toString())
		etAuthUser.setText(prefs.getString(KEY_AUTH_USER, "") ?: "")
		etAuthPass.setText(prefs.getString(KEY_AUTH_PASS, "") ?: "")
		tvSubtitle.text = getString(R.string.subtitle)

		val storage = StorageAccess(this, currentTreeUri)
		storage.ensureRootExists()
		tvFolder.text = formatSafDisplayPath(currentTreeUri)
		tvUrl.text = getString(R.string.server_not_running)
		tvQrMode.text = getString(R.string.qr_mode_none)
		renderQr("")
		handleShareIntent(intent)

		btnOpenBrowser.setOnClickListener {
			if (currentUrl.isBlank()) {
				toast("Server URL not ready")
				return@setOnClickListener
			}
			startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
		}

		btnStartServer.setOnClickListener {
			val p = etPort.text.toString().trim().toIntOrNull()
			if (p == null || p !in 1024..65535) {
				etPort.error = "Port must be 1024-65535"
				return@setOnClickListener
			}
			prefs.edit()
				.putInt(KEY_PORT, p)
				.putString(KEY_AUTH_USER, etAuthUser.text.toString().trim())
				.putString(KEY_AUTH_PASS, etAuthPass.text.toString())
				.apply()
			if (!ensureFolderSelected()) return@setOnClickListener
			tvQrMode.text = getString(R.string.qr_mode_server)
			startOrRestartService(p)
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
			tvQrMode.text = getString(R.string.qr_mode_none)
			renderQr("")
		}

		btnEnableHotspot.setOnClickListener {
			val tetherIntent = Intent("android.settings.TETHER_SETTINGS")
			val fallbackIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
			runCatching { startActivity(tetherIntent) }
				.onFailure { runCatching { startActivity(fallbackIntent) } }
			toast("Open hotspot settings and turn it on")
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

		btnGithub.setOnClickListener {
			val url = "https://github.com/Mobinshahidi/swap"
			startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
		}

		val btnCheckUpdate: Button = findViewById(R.id.btnCheckUpdate)
		btnCheckUpdate.setOnClickListener { checkForUpdates() }
	}

	// Checking for a newer release inherently needs one network request to GitHub;
	// there is no offline way to know about releases published after this build.
	private fun checkForUpdates() {
		toast("Checking for updates…")
		uiScope.launch {
			val result = withContext(Dispatchers.IO) { fetchLatestRelease() }
			if (result == null) {
				toast("Update check failed (no internet?)")
				return@launch
			}
			val (tag, htmlUrl) = result
			val current = currentVersionName()
			if (isNewer(tag, current)) showUpdateDialog(tag, htmlUrl)
			else toast("You're up to date (v$current)")
		}
	}

	private fun fetchLatestRelease(): Pair<String, String>? {
		return runCatching {
			val conn = (URL("https://api.github.com/repos/Mobinshahidi/swap/releases/latest").openConnection() as HttpURLConnection).apply {
				requestMethod = "GET"
				setRequestProperty("Accept", "application/vnd.github+json")
				setRequestProperty("User-Agent", "Swap-App")
				connectTimeout = 8000
				readTimeout = 8000
			}
			conn.inputStream.use { stream ->
				val obj = JSONObject(stream.readBytes().toString(Charsets.UTF_8))
				val tag = obj.optString("tag_name")
				val html = obj.optString("html_url")
				if (tag.isBlank()) null else Pair(tag, html)
			}
		}.getOrNull()
	}

	private fun currentVersionName(): String {
		return runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "" }.getOrDefault("")
	}

	private fun isNewer(remote: String, local: String): Boolean {
		val r = remote.trimStart('v', 'V').split('.').mapNotNull { it.toIntOrNull() }
		val l = local.trimStart('v', 'V').split('.').mapNotNull { it.toIntOrNull() }
		for (i in 0 until maxOf(r.size, l.size)) {
			val rv = r.getOrElse(i) { 0 }
			val lv = l.getOrElse(i) { 0 }
			if (rv != lv) return rv > lv
		}
		return false
	}

	private fun showUpdateDialog(tag: String, htmlUrl: String) {
		AlertDialog.Builder(this)
			.setTitle("Update available")
			.setMessage("Version $tag is available. Open the release page to download?")
			.setPositiveButton("Open") { _, _ ->
				val url = htmlUrl.ifBlank { "https://github.com/Mobinshahidi/swap/releases/latest" }
				startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
			}
			.setNegativeButton("Later", null)
			.show()
	}

	override fun onResume() {
		super.onResume()
		observeServerState()
	}

	override fun onNewIntent(intent: Intent?) {
		super.onNewIntent(intent)
		setIntent(intent)
		handleShareIntent(intent)
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
			tvFolder.text = formatSafDisplayPath(currentTreeUri)
			startOrRestartService()
			return
		}
	}

	private fun observeServerState() {
		collectJob?.cancel()
		collectJob = uiScope.launch {
			ServerService.state.collect { state ->
				tvStatus.text = if (state.running) "Status: running" else "Status: stopped"
				tvStatus.setTextColor(
					resources.getColor(
						if (state.running) R.color.status_running else R.color.status_stopped,
						theme
					)
				)
				currentUrl = state.url
				val display = if (state.mdnsUrl.isNotBlank()) "${state.url}\n${state.mdnsUrl}" else state.url
				tvUrl.text = if (state.url.isBlank()) getString(R.string.server_not_running) else display
				if (state.folderDisplay.isNotBlank()) tvFolder.text = formatSafDisplayPath(state.folderDisplay)
				tvQrMode.text = if (state.url.isBlank()) getString(R.string.qr_mode_none) else getString(R.string.qr_mode_server)
				renderQr(if (state.url.isBlank()) "" else state.url)
			}
		}
	}

	private fun ensureFolderSelected(): Boolean {
		if (!currentTreeUri.isNullOrBlank()) return true
		toast("Pick a folder to share first")
		val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
			addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
			addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
		}
		startActivityForResult(intent, REQ_PICK_FOLDER)
		return false
	}

	private fun formatSafDisplayPath(uriString: String?): String {
		if (uriString.isNullOrBlank()) return "Pick a folder"
		return try {
			val uri = Uri.parse(uriString)
			val treeId = DocumentsContract.getTreeDocumentId(uri)
			val decoded = Uri.decode(treeId)
			val parts = decoded.split(":", limit = 2)
			val volume = parts.getOrNull(0).orEmpty()
			val path = parts.getOrNull(1).orEmpty().trim('/')
			val root = if (volume == "primary") "Internal Storage" else "Storage $volume"
			if (path.isBlank()) root else "$root/$path"
		} catch (_: Throwable) {
			uriString
		}
	}

	private fun startOrRestartService(forcedPort: Int? = null) {
		val port = forcedPort ?: prefs.getInt(KEY_PORT, 1390)
		val intent = Intent(this, ServerService::class.java).apply {
			action = ServerService.ACTION_RESTART
			putExtra(ServerService.EXTRA_PORT, port)
			putExtra(ServerService.EXTRA_TREE_URI, currentTreeUri)
			putExtra(ServerService.EXTRA_AUTH_USER, prefs.getString(KEY_AUTH_USER, "") ?: "")
			putExtra(ServerService.EXTRA_AUTH_PASS, prefs.getString(KEY_AUTH_PASS, "") ?: "")
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

	private fun handleShareIntent(incomingIntent: Intent?) {
		if (incomingIntent == null) return
		val action = incomingIntent.action ?: return
		val type = incomingIntent.type ?: return
		if (action != Intent.ACTION_SEND || type.isBlank()) return
		val streamUri = incomingIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
		if (currentTreeUri.isNullOrBlank()) {
			toast("Pick a folder to share first")
			return
		}
		val displayName = queryDisplayName(streamUri) ?: "shared_file"
		val bytes = runCatching { contentResolver.openInputStream(streamUri)?.use { it.readBytes() } }.getOrNull() ?: return
		val storage = StorageAccess(this, currentTreeUri)
		if (!storage.ensureRootExists()) {
			toast("Shared folder unavailable")
			return
		}
		val saved = storage.saveFile("", displayName, bytes)
		if (saved != null) {
			toast("Imported: $saved")
			setIntent(Intent())
		}
	}

	private fun queryDisplayName(uri: Uri): String? {
		val c = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null) ?: return null
		c.use {
			if (it.moveToFirst()) return it.getString(0)
		}
		return null
	}

	companion object {
		private const val PREFS = "lan_share_prefs"
		private const val KEY_PORT = "port"
		private const val KEY_FOLDER_URI = "folder_uri"
		private const val KEY_AUTH_USER = "auth_user"
		private const val KEY_AUTH_PASS = "auth_pass"
		private const val REQ_PICK_FOLDER = 1201
	}
}
