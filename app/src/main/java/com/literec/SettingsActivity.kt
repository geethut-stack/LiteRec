package com.literec

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import kotlin.math.min

class SettingsActivity : Activity() {
    companion object {
        const val RC_TREE = 51
        const val ADB_CMD = "adb shell appops set com.literec android:project_media allow"
    }

    private lateinit var p: Prefs
    private lateinit var box: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        p = Prefs(this)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Ui.applyInsets(box, Ui.dp(this, 16))
        val sv = ScrollView(this)
        sv.addView(box, ViewGroup.LayoutParams(-1, -2))
        setContentView(sv)
        render()
    }

    // ------------------------------------------------------------------ builders

    private fun header(t: String) {
        box.addView(Ui.text(this, t, 15f, bold = true).apply { setTextColor(Ui.ACCENT) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.sz(this@SettingsActivity, 22) })
    }

    private fun ripple(v: LinearLayout) {
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        v.background = ta.getDrawable(0)
        ta.recycle()
    }

    private fun row(title: String, value: String, click: () -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.sz(this@SettingsActivity, 14), 0, Ui.sz(this@SettingsActivity, 14))
            addView(Ui.text(this@SettingsActivity, title, 18f))
            addView(Ui.text(this@SettingsActivity, value, 15f, secondary = true))
            setOnClickListener { click() }
        }
        ripple(r)
        box.addView(r, LinearLayout.LayoutParams(-1, -2))
    }

    private fun switchRow(title: String, sub: String, on: Boolean, set: (Boolean) -> Unit) {
        val sw = Switch(this).apply {
            isChecked = on
            scaleX = Ui.scale; scaleY = Ui.scale
            setOnCheckedChangeListener { _, v -> set(v) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(this@SettingsActivity, title, 18f))
            addView(Ui.text(this@SettingsActivity, sub, 15f, secondary = true))
        }
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.sz(this@SettingsActivity, 14), 0, Ui.sz(this@SettingsActivity, 14))
            addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            addView(sw, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.sz(this@SettingsActivity, 12); marginEnd = Ui.sz(this@SettingsActivity, 8) })
            setOnClickListener { sw.toggle() }
        }
        ripple(r)
        box.addView(r, LinearLayout.LayoutParams(-1, -2))
    }

    private fun choose(title: String, labels: List<String>, cur: Int, pick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), cur) { d, i -> pick(i); d.dismiss(); render() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun <T> pickRow(title: String, opts: List<Pair<T, String>>, cur: T, set: (T) -> Unit) {
        val idx = opts.indexOfFirst { it.first == cur }
        row(title, if (idx >= 0) opts[idx].second else opts.firstOrNull()?.second ?: "-") {
            choose(title, opts.map { it.second }, idx) { set(opts[it].first) }
        }
    }

    // ------------------------------------------------------------------ content

    private fun codecMime() = if (p.hevc && Caps.video(Caps.HEVC) != null) Caps.HEVC else Caps.AVC

    private fun resOptions(): List<Pair<Int, String>> {
        val n = Caps.screenSize(this)
        val ns = min(n.x, n.y)
        val v = Caps.video(codecMime())
        val (nw, nh) = Caps.size(n, 0, p.orientation, v)
        val out = ArrayList<Pair<Int, String>>()
        out.add(0 to "Native (${nw}x$nh)")
        listOf(2160 to "4K", 1440 to "1440p", 1080 to "1080p", 720 to "720p", 480 to "480p").forEach { (s, l) ->
            if (s < ns) {
                val (w, h) = Caps.size(n, s, p.orientation, v)
                if (v == null || v.isSizeSupported(w, h)) out.add(s to "$l (${w}x$h)")
            }
        }
        return out
    }

    private fun fpsOptions(): List<Pair<Int, String>> {
        val n = Caps.screenSize(this)
        val v = Caps.video(codecMime())
        val (w, h) = Caps.size(n, p.resolution, p.orientation, v)
        val ok = listOf(30, 60, 90, 120).filter { f ->
            v == null || try { v.areSizeAndRateSupported(w, h, f.toDouble()) } catch (e: Exception) { false }
        }
        return (if (ok.isEmpty()) listOf(30) else ok).map { it to "$it fps" }
    }

    private fun bitrateLabel(): String {
        if (p.bitrateMbps > 0) return "${p.bitrateMbps} Mbps  (about ${p.bitrateMbps * 60 / 8} MB per minute)"
        val c = Caps.candidates(this, p).firstOrNull() ?: return "Auto"
        val mbps = Caps.bitrate(p, c) / 1_000_000.0
        return "Auto  (about %.0f Mbps, %.0f MB per minute)".format(mbps, mbps * 60 / 8)
    }

    private fun delayLabel(s: Int) = if (s <= 0) "Off (starts immediately)" else if (s == 1) "1 second" else "$s seconds"

    private fun render() {
        box.removeAllViews()
        box.addView(Ui.text(this, "Settings", 30f, bold = true))

        header("INTERFACE")
        pickRow("Text and button size", listOf(100 to "Normal", 125 to "Large", 150 to "Extra large", 175 to "Huge"), p.uiScale) {
            p.uiScale = it
            Ui.scale = it / 100f
        }

        header("VIDEO")
        pickRow("Resolution", resOptions(), p.resolution) { p.resolution = it }
        pickRow("Frame rate", fpsOptions(), p.fps) { p.fps = it }
        val brs = listOf(0, 2, 4, 8, 12, 16, 24, 32, 48, 64)
        row("Video bitrate", bitrateLabel()) {
            choose("Video bitrate", brs.map { if (it == 0) "Auto" else "$it Mbps  (~${it * 60 / 8} MB/min)" }, brs.indexOf(p.bitrateMbps)) { p.bitrateMbps = brs[it] }
        }
        val codecs = ArrayList<Pair<Boolean, String>>()
        codecs.add(false to "H.264 (works everywhere)")
        if (Caps.video(Caps.HEVC) != null) codecs.add(true to "H.265 (smaller files)")
        pickRow("Video codec", codecs, p.hevc) { p.hevc = it }
        pickRow("Orientation", listOf(0 to "Auto (as the screen is when you start)", 1 to "Portrait", 2 to "Landscape"), p.orientation) { p.orientation = it }

        header("AUDIO")
        pickRow("Sample rate", listOf(44100 to "44.1 kHz", 48000 to "48 kHz"), p.sampleRate) { p.sampleRate = it }
        pickRow("Audio bitrate", listOf(96 to "96 kbps", 128 to "128 kbps", 192 to "192 kbps", 256 to "256 kbps"), p.audioKbps) { p.audioKbps = it }
        val gains = listOf(25, 50, 75, 100, 125, 150, 200).map { it to "$it%" }
        pickRow("System audio volume", gains, p.sysGain) { p.sysGain = it }
        pickRow("Microphone volume", gains, p.micGain) { p.micGain = it }

        header("START AND STOP")
        row("Start delay", delayLabel(p.countdown) + "\nTime between pressing Start and recording beginning") { pickDelay() }
        switchRow("Stop when screen locks", "Ends and saves the recording when the screen turns off", p.stopOnLock) { p.stopOnLock = it }

        header("SCREEN CAPTURE PERMISSION")
        if (Build.VERSION.SDK_INT >= 34) {
            switchRow("Full screen only", "Skips the \"one app or entire screen\" choice, so the prompt is a single tap", p.fullOnly) { p.fullOnly = it }
        }
        row("Skip the permission prompt", "Advanced: needs a one-time ADB command. Tap for details") { adbDialog() }

        header("FILES")
        row("File name", "${p.pattern}\nTokens: {date} {time} {type}") { editPattern() }
        val loc = if (p.tree.isEmpty()) "Default: ${Folders.VIDEO_REL} (video), ${Folders.AUDIO_REL} (audio)"
        else Folders.pretty(Uri.parse(p.tree))
        row("Save location", loc) {
            AlertDialog.Builder(this).setTitle("Save location")
                .setItems(arrayOf("Choose a folder...", "Use default location")) { _, i ->
                    if (i == 0) {
                        startActivityForResult(
                            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            ), RC_TREE
                        )
                    } else {
                        releaseOldTree(null)
                        p.tree = ""
                        render()
                    }
                }.show()
        }

        header("ABOUT THIS DEVICE")
        box.addView(Ui.text(this, Caps.describe() +
            "\n\nResolutions above your screen's native size are hidden: recording larger than the screen only upscales and wastes space. " +
            "4K appears when the screen and the encoder both support it.", 14f, secondary = true))
    }

    private fun pickDelay() {
        val vals = listOf(0, 1, 2, 3, 5, 10, 15, 30)
        val labels = vals.map { delayLabel(it) } + "Custom..."
        val cur = vals.indexOf(p.countdown).let { if (it < 0) vals.size else it }
        AlertDialog.Builder(this).setTitle("Start delay")
            .setSingleChoiceItems(labels.toTypedArray(), cur) { d, i ->
                d.dismiss()
                if (i < vals.size) { p.countdown = vals[i]; render() } else customDelay()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun customDelay() {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(p.countdown.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this).setTitle("Start delay in seconds")
            .setMessage("Any number from 0 to 300")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val n = et.text.toString().toIntOrNull()
                if (n == null || n < 0 || n > 300) Ui.toast(this, "Enter a number from 0 to 300")
                else p.countdown = n
                render()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun adbDialog() {
        AlertDialog.Builder(this).setTitle("Skip the permission prompt")
            .setMessage(
                "Android makes apps ask for screen-capture permission every time, and an app can't turn that off by itself.\n\n" +
                    "A workaround other recording apps document: turn on USB debugging, connect the phone to a computer, and run this once:\n\n" +
                    ADB_CMD + "\n\n" +
                    "The prompt should then stop appearing. I haven't tested this on your phone: it may not work on every Android version, " +
                    "and on some phones it resets after a restart.\n\n" +
                    "To undo it, run the same command with 'default' instead of 'allow'."
            )
            .setPositiveButton("Copy command") { _, _ ->
                try {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", ADB_CMD))
                    Ui.toast(this, "Command copied")
                } catch (_: Exception) { }
            }
            .setNegativeButton("Close", null).show()
    }

    private fun editPattern() {
        val et = EditText(this).apply { setText(p.pattern); setSingleLine() }
        AlertDialog.Builder(this).setTitle("File name")
            .setMessage("Use {date}, {time} and {type}. The extension is added for you.")
            .setView(et)
            .setPositiveButton("Save") { _, _ -> p.pattern = et.text.toString().ifBlank { "Rec_{date}_{time}" }; render() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun releaseOldTree(keep: Uri?) {
        if (p.tree.isEmpty()) return
        val old = Uri.parse(p.tree)
        if (old == keep) return
        try {
            contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) { }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_TREE || resultCode != RESULT_OK) return
        val u = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) {
            Ui.toast(this, "Can't use that folder. Pick a different one.")
            return
        }
        releaseOldTree(u)
        p.tree = u.toString()
        render()
    }
}
