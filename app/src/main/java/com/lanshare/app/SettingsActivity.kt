package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : Activity() {
	private lateinit var prefs: SharedPreferences
	private lateinit var etPort: EditText
	private lateinit var etAuthUser: EditText
	private lateinit var etAuthPass: EditText
	private lateinit var tvFolder: TextView
	private var currentTreeUri: String? = null
	private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_settings)
		prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
		currentTreeUri = prefs.getString(AppPrefs.KEY_FOLDER_URI, null)

		etPort = findViewById(R.id.etPort)
		etAuthUser = findViewById(R.id.etAuthUser)
		etAuthPass = findViewById(R.id.etAuthPass)
		tvFolder = findViewById(R.id.tvFolder)

		etPort.setText(prefs.getInt(AppPrefs.KEY_PORT, 1390).toString())
		etAuthUser.setText(prefs.getString(AppPrefs.KEY_AUTH_USER, "") ?: "")
		etAuthPass.setText(prefs.getString(AppPrefs.KEY_AUTH_PASS, "") ?: "")
		tvFolder.text = MainActivity.formatSafDisplayPath(currentTreeUri)

		findViewById<EditText>(R.id.etThemeAccent).setText(prefs.getString(AppPrefs.KEY_THEME_ACCENT, "") ?: "")
		findViewById<EditText>(R.id.etThemeBg).setText(prefs.getString(AppPrefs.KEY_THEME_BG, "") ?: "")
		findViewById<EditText>(R.id.etThemeText).setText(prefs.getString(AppPrefs.KEY_THEME_TEXT, "") ?: "")

		findViewById<Button>(R.id.btnChangeFolder).setOnClickListener {
			val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
				addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
				addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
				addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
				addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
			}
			startActivityForResult(intent, REQ_PICK_FOLDER)
		}

		findViewById<Button>(R.id.btnEnableHotspot).setOnClickListener {
			val tetherIntent = Intent("android.settings.TETHER_SETTINGS")
			val fallbackIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
			runCatching { startActivity(tetherIntent) }
				.onFailure { runCatching { startActivity(fallbackIntent) } }
			toast("Open hotspot settings and turn it on")
		}

		findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener { checkForUpdates() }

		findViewById<ImageButton>(R.id.btnGithub).setOnClickListener {
			startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Mobinshahidi/swap")))
		}

		findViewById<Button>(R.id.btnThemeApply).setOnClickListener {
			val accent = findViewById<EditText>(R.id.etThemeAccent).text.toString().trim()
			val bg = findViewById<EditText>(R.id.etThemeBg).text.toString().trim()
			val text = findViewById<EditText>(R.id.etThemeText).text.toString().trim()
			val bad = listOf(accent, bg, text).filter { it.isNotBlank() && AppTheme.parse(it) == null }
			if (bad.isNotEmpty()) {
				toast("Invalid color: ${bad.first()}")
				return@setOnClickListener
			}
			prefs.edit()
				.putString(AppPrefs.KEY_THEME_ACCENT, accent)
				.putString(AppPrefs.KEY_THEME_BG, bg)
				.putString(AppPrefs.KEY_THEME_TEXT, text)
				.apply()
			toast("Theme applied")
			recreate()
		}

		findViewById<Button>(R.id.btnThemeReset).setOnClickListener {
			prefs.edit()
				.remove(AppPrefs.KEY_THEME_ACCENT)
				.remove(AppPrefs.KEY_THEME_BG)
				.remove(AppPrefs.KEY_THEME_TEXT)
				.apply()
			findViewById<EditText>(R.id.etThemeAccent).setText("")
			findViewById<EditText>(R.id.etThemeBg).setText("")
			findViewById<EditText>(R.id.etThemeText).setText("")
			toast("Theme reset")
			recreate()
		}

		AppTheme.apply(this)
	}

	// Persist port + auth whenever the user leaves settings; the main screen's
	// Start/Restart reads these prefs.
	override fun onPause() {
		val port = etPort.text.toString().trim().toIntOrNull()
		val editor = prefs.edit()
		if (port != null && port in 1024..65535) editor.putInt(AppPrefs.KEY_PORT, port)
		editor.putString(AppPrefs.KEY_AUTH_USER, etAuthUser.text.toString().trim())
		editor.putString(AppPrefs.KEY_AUTH_PASS, etAuthPass.text.toString())
		editor.apply()
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
			prefs.edit().putString(AppPrefs.KEY_FOLDER_URI, currentTreeUri).apply()
			tvFolder.text = MainActivity.formatSafDisplayPath(currentTreeUri)
			toast("Folder updated — restart the server to apply")
		}
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

	private fun toast(msg: String) {
		Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
	}

	companion object {
		private const val REQ_PICK_FOLDER = 1202
	}
}
