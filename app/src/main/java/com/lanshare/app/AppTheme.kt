package com.lanshare.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView

// Applies the user's custom app theme (accent / background / text hex colors
// stored in SharedPreferences) on top of the default warm palette from XML.
// Views opt in via android:tag: "themeCard" (surface), "themeAccent" (button
// background), "themeText" (title text). Untagged views keep their XML colors.
object AppTheme {

	fun apply(activity: Activity) {
		val prefs = activity.getSharedPreferences(AppPrefs.PREFS, Context.MODE_PRIVATE)
		val accent = parse(prefs.getString(AppPrefs.KEY_THEME_ACCENT, null))
		val bg = parse(prefs.getString(AppPrefs.KEY_THEME_BG, null))
		val text = parse(prefs.getString(AppPrefs.KEY_THEME_TEXT, null))
		if (accent == null && bg == null && text == null) return
		val content = activity.findViewById<View>(android.R.id.content) ?: return
		walk(content, accent, bg, text)
	}

	fun parse(raw: String?): Int? {
		val t = raw?.trim().orEmpty()
		if (t.isEmpty()) return null
		val hex = if (t.startsWith("#")) t else "#$t"
		return runCatching { Color.parseColor(hex) }.getOrNull()
	}

	private fun walk(v: View, accent: Int?, bg: Int?, text: Int?) {
		if (v is ScrollView && bg != null) v.setBackgroundColor(bg)
		when (v.tag) {
			// Flat look: cards share the page color; the border does the separation.
			"themeCard" -> if (bg != null) v.background?.mutate()?.setTint(bg)
			"themeAccent" -> if (accent != null) v.background?.mutate()?.setTint(accent)
			"themeText" -> if (text != null && v is TextView) v.setTextColor(text)
		}
		if (v is ViewGroup) {
			for (i in 0 until v.childCount) walk(v.getChildAt(i), accent, bg, text)
		}
	}
}
