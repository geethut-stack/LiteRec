package com.literec

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** Tiny helpers so every screen can be built in code with platform widgets only. */
object Ui {
    const val ACCENT = 0xFFE53935.toInt()

    /** Interface size multiplier (Settings > Interface size). */
    @JvmStatic var scale = 1.25f

    fun init(c: Context) { scale = Prefs(c).uiScale / 100f }

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).roundToInt()

    /** dp that grows with the interface size. */
    fun sz(c: Context, v: Int): Int = dp(c, (v * scale).roundToInt())

    fun attr(c: Context, a: Int): Int {
        val t = c.obtainStyledAttributes(intArrayOf(a))
        val v = t.getColor(0, Color.GRAY)
        t.recycle()
        return v
    }

    fun text(c: Context, s: CharSequence, sp: Float = 16f, bold: Boolean = false, secondary: Boolean = false): TextView =
        TextView(c).apply {
            text = s
            textSize = sp * scale
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (secondary) setTextColor(attr(c, android.R.attr.textColorSecondary))
        }

    fun round(c: Context, color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * c.resources.displayMetrics.density
    }

    fun pill(c: Context, s: String, color: Int = ACCENT): Button = Button(c).apply {
        text = s
        isAllCaps = false
        textSize = 18f * scale
        setTextColor(Color.WHITE)
        background = round(c, color, 32f)
        stateListAnimator = null
        minHeight = sz(c, 58)
        minimumHeight = sz(c, 58)
    }

    /** Compact filled button for dense toolbars. */
    fun small(c: Context, s: String, color: Int = 0xFF546E7A.toInt()): Button = Button(c).apply {
        text = s
        isAllCaps = false
        textSize = 15f * scale
        setTextColor(Color.WHITE)
        background = round(c, color, 24f)
        stateListAnimator = null
        minHeight = sz(c, 46)
        minimumHeight = sz(c, 46)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(c, 6), 0, dp(c, 6), 0)
    }

    /** Borderless text button. */
    fun flat(c: Context, s: String): Button = Button(c, null, android.R.attr.borderlessButtonStyle).apply {
        text = s
        isAllCaps = false
        textSize = 16f * scale
        minHeight = sz(c, 48)
        minimumHeight = sz(c, 48)
    }

    /** Android 15+ forces edge-to-edge, so pad the content by the system bar insets there. */
    @Suppress("DEPRECATION")
    fun applyInsets(v: View, pad: Int) {
        v.setPadding(pad, pad, pad, pad)
        if (Build.VERSION.SDK_INT < 35) return
        v.setOnApplyWindowInsetsListener { view, w ->
            val i = w.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(pad + i.left, pad + i.top, pad + i.right, pad + i.bottom)
            WindowInsets.CONSUMED
        }
    }

    fun toast(c: Context, s: String) = Toast.makeText(c.applicationContext, s, Toast.LENGTH_LONG).show()

    fun fmtTime(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    fun fmtSize(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
        b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
        else -> "%d KB".format(b / 1024)
    }
}
