package com.lanshare.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

// Applies the user's app theme (accent / background / text hex colors stored in
// SharedPreferences) on top of the default warm palette from XML. Views opt in
// via android:tag: "themeCard" (surface), "themeAccent" (button background),
// "themeText" (title text). Untagged TextViews are remapped by their XML color
// (primary→text, muted→derived muted) and EditTexts get themed input styling,
// so the Dark preset stays readable everywhere. Shape drawables are recolored
// with setColor + setStroke (NOT setTint, which would paint over the border).
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
		val border = if (bg != null) mix(bg, if (isDark(bg)) Color.WHITE else Color.BLACK, if (isDark(bg)) 0.18f else 0.2f) else null
		// Inputs sit slightly lifted from a dark page, noticeably lighter on a light one.
		val inputBg = when {
			bg == null -> null
			isDark(bg) -> mix(bg, Color.WHITE, 0.06f)
			else -> mix(bg, Color.WHITE, 0.5f)
		}
		val strokePx = (activity.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
		val p = Palette(accent, bg, text, muted, border, inputBg, strokePx, xmlPrimary, xmlMuted)
		walk(content, p)
	}

	fun parse(raw: String?): Int? {
		val t = raw?.trim().orEmpty()
		if (t.isEmpty()) return null
		val hex = if (t.startsWith("#")) t else "#$t"
		return runCatching { Color.parseColor(hex) }.getOrNull()
	}

	private data class Palette(
		val accent: Int?,
		val bg: Int?,
		val text: Int?,
		val muted: Int?,
		val border: Int?,
		val inputBg: Int?,
		val strokePx: Int,
		val xmlPrimary: Int,
		val xmlMuted: Int
	)

	private fun walk(v: View, p: Palette) {
		if (v is ScrollView && p.bg != null) v.setBackgroundColor(p.bg)
		when (v.tag) {
			// Flat look: cards share the page color; the border does the separation.
			"themeCard" -> recolorShape(v, p.bg, p.border, p.strokePx)
			"themeAccent" -> recolorShape(v, p.accent, null, p.strokePx)
			"themeText" -> if (p.text != null && v is TextView) v.setTextColor(p.text)
			else -> when {
				v is EditText -> {
					recolorShape(v, p.inputBg, p.border, p.strokePx)
					if (p.text != null) v.setTextColor(p.text)
					if (p.muted != null) v.setHintTextColor(p.muted)
				}
				v is TextView -> when (v.currentTextColor) {
					p.xmlPrimary -> if (p.text != null) v.setTextColor(p.text)
					p.xmlMuted -> if (p.muted != null) v.setTextColor(p.muted)
				}
			}
		}
		if (v is ViewGroup) {
			for (i in 0 until v.childCount) walk(v.getChildAt(i), p)
		}
	}

	// Recolors a shape drawable's fill (and stroke, when given) without wiping
	// the stroke the way a blanket tint would.
	private fun recolorShape(v: View, fill: Int?, stroke: Int?, strokePx: Int) {
		if (fill == null) return
		val shape = v.background?.mutate() as? GradientDrawable
		if (shape != null) {
			shape.setColor(fill)
			if (stroke != null) shape.setStroke(strokePx, stroke)
		} else {
			v.background?.mutate()?.setTint(fill)
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
