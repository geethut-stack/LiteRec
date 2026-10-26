package com.literec

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var flow: StartFlow
    private lateinit var cbScreen: CheckBox
    private lateinit var cbSys: CheckBox
    private lateinit var cbMic: CheckBox
    private lateinit var hint: TextView
    private lateinit var timer: TextView
    private lateinit var startBtn: Button
    private lateinit var pauseBtn: Button
    private lateinit var saved: TextView
    private var builtScale = 0f

    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); h.postDelayed(this, 500) }
    }
    private val listener = Runnable { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        builtScale = Ui.scale
        prefs = Prefs(this)
        flow = StartFlow(this, prefs) { started -> if (!started) refresh() }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Ui.applyInsets(col, Ui.dp(this, 20))

        col.addView(Ui.text(this, "LiteRec", 30f, bold = true))
        col.addView(Ui.text(this, "Choose what to record", 14f, secondary = true))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 8))
            background = Ui.round(this@MainActivity, 0x1F888888, 16f)
        }
        cbScreen = source(card, "Screen", "Video of everything on display", prefs.screen)
        cbSys = source(card, "System audio", "Sound playing on the phone", prefs.sys)
        cbMic = source(card, "Microphone", "Your voice and surroundings", prefs.mic)
        col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@MainActivity, 16) })

        hint = Ui.text(this, "", 14f, secondary = true)
        col.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@MainActivity, 8) })

        timer = Ui.text(this, "00:00", 52f).apply { gravity = Gravity.CENTER; typeface = android.graphics.Typeface.MONOSPACE }
        col.addView(timer, LinearLayout.LayoutParams(-1, 0, 1f))

        saved = Ui.text(this, "", 17f).apply {
            gravity = Gravity.CENTER
            setTextColor(Ui.ACCENT)
            visibility = View.GONE
            setPadding(0, Ui.dp(this@MainActivity, 8), 0, Ui.dp(this@MainActivity, 12))
            setOnClickListener {
                RecState.lastUri?.let { startActivity(PlayerActivity.intent(this@MainActivity, it, RecState.lastVideo, RecState.lastName)) }
            }
        }
        col.addView(saved, LinearLayout.LayoutParams(-1, -2))

        startBtn = Ui.pill(this, "Start recording")
        pauseBtn = Ui.pill(this, "Pause", 0xFF607D8B.toInt()).apply { visibility = View.GONE }
        col.addView(pauseBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@MainActivity, 8) })
        col.addView(startBtn, LinearLayout.LayoutParams(-1, -2))

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lib = Ui.flat(this, "Library")
        val set = Ui.flat(this, "Settings")
        nav.addView(lib, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(set, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(nav, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@MainActivity, 8) })

        lib.setOnClickListener { startActivity(Intent(this, LibraryActivity::class.java)) }
        set.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        startBtn.setOnClickListener {
            when (RecState.s) {
                RecState.S.IDLE -> {
                    startBtn.isEnabled = false
                    flow.begin(cbScreen.isChecked, cbSys.isChecked, cbMic.isChecked)
                }
                RecState.S.COUNTDOWN, RecState.S.RECORDING, RecState.S.PAUSED ->
                    startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
                else -> {}
            }
        }
        pauseBtn.setOnClickListener {
            val a = if (RecState.s == RecState.S.PAUSED) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE
            startService(Intent(this, RecorderService::class.java).setAction(a))
        }

        val sv = ScrollView(this).apply { isFillViewport = true }
        sv.addView(col, ViewGroup.LayoutParams(-1, -1))
        setContentView(sv)
        updateHint()
    }

    private fun source(parent: LinearLayout, title: String, sub: String, checked: Boolean): CheckBox {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.sz(this@MainActivity, 10), 0, Ui.sz(this@MainActivity, 10))
        }
        val cb = CheckBox(this).apply {
            isChecked = checked
            scaleX = Ui.scale; scaleY = Ui.scale
            setOnCheckedChangeListener { _, _ -> updateHint() }
        }
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@MainActivity, 8), 0, 0, 0)
            addView(Ui.text(this@MainActivity, title, 19f, bold = true))
            addView(Ui.text(this@MainActivity, sub, 15f, secondary = true))
        }
        row.addView(cb, LinearLayout.LayoutParams(Ui.sz(this, 40), -2))
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        row.setOnClickListener { if (cb.isEnabled) cb.toggle() }
        parent.addView(row)
        return cb
    }

    private fun updateHint() {
        val lines = ArrayList<String>()
        if (cbScreen.isChecked || cbSys.isChecked) lines.add("Android asks for screen-capture permission each time you start.")
        if (cbSys.isChecked) lines.add("Some apps block audio capture; those parts record as silence.")
        if (cbSys.isChecked && cbMic.isChecked) lines.add("Tip: use headphones so the mic doesn't pick up the speaker.")
        if (!cbScreen.isChecked && (cbSys.isChecked || cbMic.isChecked)) lines.add("Audio-only recordings are saved as .m4a.")
        hint.text = lines.joinToString("\n")
    }

    private fun refresh() {
        val s = RecState.s
        val idle = s == RecState.S.IDLE
        cbScreen.isEnabled = idle; cbSys.isEnabled = idle; cbMic.isEnabled = idle
        startBtn.text = when (s) {
            RecState.S.IDLE -> "Start recording"
            RecState.S.COUNTDOWN -> "Cancel (${RecState.countdown})"
            RecState.S.RECORDING, RecState.S.PAUSED -> "Stop"
            RecState.S.SAVING -> "Saving..."
        }
        startBtn.isEnabled = s != RecState.S.SAVING
        val rec = s == RecState.S.RECORDING || s == RecState.S.PAUSED
        pauseBtn.visibility = if (rec) View.VISIBLE else View.GONE
        pauseBtn.text = if (s == RecState.S.PAUSED) "Resume" else "Pause"
        timer.text = Ui.fmtTime(if (rec || s == RecState.S.SAVING) RecState.elapsed() else 0L)
        val u = RecState.lastUri
        if (idle && u != null) {
            saved.text = "Saved: ${RecState.lastName}  -  tap to play"
            saved.visibility = View.VISIBLE
        } else saved.visibility = View.GONE
    }

    override fun onStart() {
        super.onStart()
        Ui.init(this)
        if (Ui.scale != builtScale) { recreate(); return }
        RecState.listeners.add(listener)
        h.post(tick)
    }

    override fun onStop() {
        RecState.listeners.remove(listener)
        h.removeCallbacks(tick)
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!flow.onActivityResult(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (!flow.onPermissionsResult(requestCode)) super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
