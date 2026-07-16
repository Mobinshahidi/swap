package com.lanshare.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView

// Applies the user's app theme (accent / background / text hex colors stored in
// SharedPreferences) on top of the default warm palette from XML. Views opt in
// via android:tag: "themeCard" (surface), "themeAccent" (button background),
// "themeText" (title text). Untagged TextViews are remapped by their XML color
// (primary→text, muted→derived muted) and EditTexts get themed input styling,
// so the Dark preset stays readable everywhere.
object AppTheme {

	fun apply(activity: Activity) {
		val prefs = activity.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
		val accent = parse(prefs.getString(AppPrefs.KEY_THEME_ACCENT, null))
		val bg = parse(prefs.getString(AppPrefs.KEY_THEME_BG, null))
		val text = parse(prefs.getString(AppPrefs.KEY_THEME_TEXT, null))
		if (accent == null && bg == null && text == null) return
		val content = activity.findViewById<View>(android.R.id.content) ?: return
		val xmlPrimary = activity.resources.getColor(R.color.text_primary, activity.theme)
		val xmlMuted = activity.resources.getColor(R.color.text_muted, activity.theme)
		val muted = if (text != null && bg != null) mix(text, bg, 0.4f) else null
		// Inputs sit slightly lifted from a dark page, noticeably lighter on a light one.
		val inputBg = when {
			bg == null -> null
			isDark(bg) -> mix(bg, Color.WHITE, 0.06f)
			else -> mix(bg, Color.WHITE, 0.5f)
		}
		walk(content, accent, bg, text, muted, inputBg, xmlPrimary, xmlMuted)
	}

	fun parse(raw: String?): Int? {
		val t = raw?.trim().orEmpty()
		if (t.isEmpty()) return null
		val hex = if (t.startsWith("#")) t else "#$t"
		return runCatching { Color.parseColor(hex) }.getOrNull()
	}

	private fun walk(
		v: View,
		accent: Int?,
		bg: Int?,
		text: Int?,
		muted: Int?,
		inputBg: Int?,
		xmlPrimary: Int,
		xmlMuted: Int
	) {
		if (v is ScrollView && bg != null) v.setBackgroundColor(bg)
		when (v.tag) {
			// Flat look: cards share the page color; the border does the separation.
			"themeCard" -> if (bg != null) v.background?.mutate()?.setTint(bg)
			"themeAccent" -> if (accent != null) v.background?.mutate()?.setTint(accent)
			"themeText" -> if (text != null && v is TextView) v.setTextColor(text)
			else -> when {
				v is EditText -> {
					if (inputBg != null) v.background?.mutate()?.setTint(inputBg)
					if (text != null) v.setTextColor(text)
					if (muted != null) v.setHintTextColor(muted)
				}
				v is TextView -> when (v.currentTextColor) {
					xmlPrimary -> if (text != null) v.setTextColor(text)
					xmlMuted -> if (muted != null) v.setTextColor(muted)
				}
			}
		}
		if (v is ViewGroup) {
			for (i in 0 until v.childCount) walk(v.getChildAt(i), accent, bg, text, muted, inputBg, xmlPrimary, xmlMuted)
		}
	}

	private fun isDark(color: Int): Boolean {
		return (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f < 0.5f
	}

	private fun mix(color: Int, target: Int, amount: Float): Int {
		val a = amount.coerceIn(0f, 1f)
		val r = Color.red(color) + ((Color.red(target) - Color.red(color)) * a).toInt()
		val g = Color.green(color) + ((Color.green(target) - Color.green(color)) * a).toInt()
		val b = Color.blue(color) + ((Color.blue(target) - Color.blue(color)) * a).toInt()
		return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
	}
}
