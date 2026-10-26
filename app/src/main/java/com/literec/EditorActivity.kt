package com.literec

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * Split / trim editor. The clip is a row of pieces; split at the playhead, then remove or restore pieces.
 * Saving copies the kept pieces without re-encoding (video pieces start at the next keyframe).
 */
class EditorActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        fun intent(c: Context, uri: Uri, video: Boolean, name: String): Intent =
            Intent(c, EditorActivity::class.java).setData(uri)
                .putExtra("video", video).putExtra("name", name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        private const val MIN_US = 100_000L
    }

    private lateinit var prefs: Prefs
    private lateinit var uri: Uri
    private var video = true
    private var name = ""
    private var durationUs = 0L

    private var segs = ArrayList<Seg>()
    private val undo = ArrayList<List<Seg>>()
    private var selected = 0
    private var playUs = 0L
    private var zoom = 1f

    @Volatile private var kfs: LongArray? = null
    @Volatile private var kfsDone = false
    private var wave: WaveData? = null

    private var mp: MediaPlayer? = null
    private var prepared = false
    private var vw = 0
    private var vh = 0
    private var ignoreUntil = 0L
    private var surface: SurfaceView? = null

    private val h = Handler(Looper.getMainLooper())
    private lateinit var timeline: Timeline
    private lateinit var hsv: HorizontalScrollView
    private lateinit var box: FrameLayout
    private lateinit var info: TextView
    private lateinit var hint: TextView
    private lateinit var playBtn: Button
    private lateinit var delBtn: Button
    private var lastHsvW = 0

    private val ticker = object : Runnable {
        override fun run() { tick(); h.postDelayed(this, 40) }
    }
    private val waveTick = object : Runnable {
        override fun run() {
            val w = wave ?: return
            timeline.invalidate()
            if (!w.done) h.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        prefs = Prefs(this)
        uri = intent.data ?: run { finish(); return }
        video = intent.getBooleanExtra("video", true)
        name = intent.getStringExtra("name") ?: "recording"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        durationUs = try {
            val m = MediaMetadataRetriever()
            m.setDataSource(this, uri)
            val d = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            m.release()
            d * 1000
        } catch (e: Exception) { 0L }
        if (durationUs <= 0) { Ui.toast(this, "Can't read this file"); finish(); return }
        segs.add(Seg(0, durationUs, false))

        buildUi()
        startPlayer()

        // waveform + keyframes in the background
        val wd = WaveData.forDuration(durationUs)
        wave = wd
        timeline.wave = wd
        Thread({ wd.decode(applicationContext, uri) }, "wave").start()
        h.postDelayed(waveTick, 300)
        if (video) {
            Thread({
                val k = Trimmer.keyframes(applicationContext, uri)
                kfs = k
                kfsDone = true
                runOnUiThread {
                    if (k != null) timeline.snap = { t -> Trimmer.snap(k, t) }
                    refresh()
                }
            }, "keyframes").start()
        } else kfsDone = true
        refresh()
        h.post(ticker)
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Ui.applyInsets(root, Ui.dp(this, 8))

        // top bar
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val cancel = Ui.flat(this, "Cancel")
        val title = Ui.text(this, "Trim and split", 20f, bold = true).apply { gravity = Gravity.CENTER }
        val save = Ui.small(this, "Save", Ui.ACCENT)
        top.addView(cancel)
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(save)
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        // preview
        box = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        if (video) {
            val sv = SurfaceView(this)
            sv.holder.addCallback(this)
            surface = sv
            box.addView(sv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        } else {
            val icon = ImageView(this).apply { setImageResource(R.drawable.ic_audio) }
            val sz = Ui.dp(this, 96)
            box.addView(icon, FrameLayout.LayoutParams(sz, sz, Gravity.CENTER))
        }
        box.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
        root.addView(box, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = Ui.dp(this@EditorActivity, 4) })

        // time + zoom
        info = Ui.text(this, "", 15f, bold = true)
        val zOut = Ui.flat(this, "Zoom -")
        val zIn = Ui.flat(this, "Zoom +")
        val infoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        infoRow.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        infoRow.addView(zOut); infoRow.addView(zIn)
        root.addView(infoRow, LinearLayout.LayoutParams(-1, -2))

        // timeline
        timeline = Timeline(this).apply {
            durationUs = this@EditorActivity.durationUs
            segs = this@EditorActivity.segs
            onTap = { t -> moveTo(t, selectPiece = true) }
        }
        hsv = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            background = Ui.round(this@EditorActivity, 0x14888888, 10f)
            addView(timeline, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, -2))
            addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
                if (r - l != lastHsvW) { lastHsvW = r - l; applyZoom(false) }
            }
        }
        root.addView(hsv, LinearLayout.LayoutParams(-1, -2))

        // transport
        playBtn = Ui.small(this, "Play", 0xFF2E7D32.toInt())
        val m1 = Ui.small(this, "-1 s"); val m01 = Ui.small(this, "-0.1"); val p01 = Ui.small(this, "+0.1"); val p1 = Ui.small(this, "+1 s")
        root.addView(row(playBtn, m1, m01, p01, p1), rowLp())

        // actions
        val split = Ui.small(this, "Split here", Ui.ACCENT)
        delBtn = Ui.small(this, "Remove piece", 0xFFC62828.toInt())
        val undoBtn = Ui.small(this, "Undo")
        root.addView(row(split, delBtn, undoBtn), rowLp())
        val before = Ui.small(this, "Remove before")
        val after = Ui.small(this, "Remove after")
        val reset = Ui.small(this, "Reset")
        root.addView(row(before, after, reset), rowLp())

        hint = Ui.text(this, "", 13f, secondary = true)
        root.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@EditorActivity, 2) })
        setContentView(root)

        cancel.setOnClickListener { onBackPressed() }
        save.setOnClickListener { saveDialog() }
        zIn.setOnClickListener { zoom = min(64f, zoom * 2f); applyZoom(true) }
        zOut.setOnClickListener { zoom = max(1f, zoom / 2f); applyZoom(true) }
        playBtn.setOnClickListener { togglePlay() }
        m1.setOnClickListener { nudge(-1_000_000L) }
        m01.setOnClickListener { nudge(-100_000L) }
        p01.setOnClickListener { nudge(100_000L) }
        p1.setOnClickListener { nudge(1_000_000L) }
        split.setOnClickListener { doSplit() }
        delBtn.setOnClickListener { toggleRemoved() }
        undoBtn.setOnClickListener { doUndo() }
        before.setOnClickListener { removeBeforeAfter(true) }
        after.setOnClickListener { removeBeforeAfter(false) }
        reset.setOnClickListener { push(); segs = arrayListOf(Seg(0, durationUs, false)); selected = 0; timeline.segs = segs; refresh() }
    }

    private fun row(vararg v: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (b in v) addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(Ui.dp(this@EditorActivity, 2), 0, Ui.dp(this@EditorActivity, 2), 0) })
    }

    private fun rowLp() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@EditorActivity, 4) }

    private fun applyZoom(keepCenter: Boolean) {
        val vwid = hsv.width
        if (vwid <= 0) return
        val lp = timeline.layoutParams
        lp.width = (vwid * zoom).toInt()
        timeline.layoutParams = lp
        if (keepCenter) hsv.post { hsv.scrollTo(max(0, timeline.xForScroll(playUs) - hsv.width / 2), 0) }
    }

    private fun fit() {
        val sv = surface ?: return
        val cw = box.width
        val ch = box.height
        if (cw == 0 || ch == 0 || vw == 0 || vh == 0) return
        val sc = min(cw.toFloat() / vw, ch.toFloat() / vh)
        val w = (vw * sc).toInt()
        val hh = (vh * sc).toInt()
        val cur = sv.layoutParams
        if (cur.width != w || cur.height != hh) {
            sv.layoutParams = FrameLayout.LayoutParams(w, hh, Gravity.CENTER)
        }
    }

    // ------------------------------------------------------------------ player

    private fun startPlayer() {
        try {
            val m = MediaPlayer()
            m.setDataSource(this, uri)
            m.setOnPreparedListener {
                prepared = true
                val first = segs.firstOrNull { !it.removed }
                if (first != null) seekPlayer(effStart(first))
            }
            m.setOnVideoSizeChangedListener { _, w, hh -> vw = w; vh = hh; fit() }
            m.setOnCompletionListener { playBtn.text = "Play"; moveTo(durationUs, false) }
            m.setOnErrorListener { _, _, _ -> Ui.toast(this, "Can't preview this file"); true }
            surface?.holder?.surface?.let { if (it.isValid) m.setSurface(it) }
            m.prepareAsync()
            mp = m
        } catch (e: Exception) {
            Ui.toast(this, "Can't preview this file")
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) { mp?.setDisplay(holder) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { try { mp?.setDisplay(null) } catch (_: Exception) { } }

    private fun seekPlayer(us: Long) {
        val m = mp ?: return
        if (!prepared) return
        ignoreUntil = System.currentTimeMillis() + 250
        try { m.seekTo(us / 1000, MediaPlayer.SEEK_CLOSEST) } catch (_: Exception) { }
    }

    private fun togglePlay() {
        val m = mp ?: return
        if (!prepared) return
        if (m.isPlaying) { m.pause(); playBtn.text = "Play"; return }
        var pos = playUs
        val i = indexAt(pos)
        if (segs[i].removed || pos < effStart(segs[i]) || pos >= durationUs - 50_000) {
            val next = nextKept(pos) ?: segs.firstOrNull { !it.removed }?.let { effStart(it) }
            if (next == null) { Ui.toast(this, "Everything is removed"); return }
            pos = next
            moveTo(pos, false)
        }
        m.start()
        playBtn.text = "Pause"
    }

    private fun tick() {
        val m = mp ?: return
        if (!prepared || !m.isPlaying || System.currentTimeMillis() < ignoreUntil) return
        val pos = m.currentPosition * 1000L
        playUs = pos
        timeline.playUs = pos
        val s = segs[indexAt(pos)]
        if (s.removed || pos < effStart(s)) {
            val next = nextKept(pos)
            if (next == null) { m.pause(); playBtn.text = "Play"; moveTo(durationUs, false) }
            else { seekPlayer(next); playUs = next; timeline.playUs = next }
        }
        timeline.invalidate()
        followPlayhead()
        updateInfo()
    }

    private fun followPlayhead() {
        val x = timeline.xForScroll(playUs)
        val l = hsv.scrollX
        if (x < l || x > l + hsv.width) hsv.scrollTo(max(0, x - hsv.width / 4), 0)
    }

    // ------------------------------------------------------------------ model

    private fun indexAt(t: Long): Int {
        for (i in segs.indices) if (t < segs[i].endUs) return i
        return segs.size - 1
    }

    /** Where this piece will really start in the saved file. */
    private fun effStart(s: Seg): Long {
        val k = kfs
        return if (video && k != null) min(Trimmer.snap(k, s.startUs), s.endUs) else s.startUs
    }

    /** Start of the first kept piece that begins after [pos], or null if there isn't one. */
    private fun nextKept(pos: Long): Long? {
        for (s in segs) {
            if (s.removed) continue
            val st = effStart(s)
            if (st < s.endUs && pos < st) return st
        }
        return null
    }

    private var pushes = 0

    private fun push() {
        pushes++
        undo.add(segs.map { it.copy() })
        if (undo.size > 50) undo.removeAt(0)
    }

    private fun moveTo(us: Long, selectPiece: Boolean) {
        playUs = us.coerceIn(0L, durationUs)
        if (selectPiece) selected = indexAt(playUs)
        timeline.playUs = playUs
        seekPlayer(playUs)
        refresh()
    }

    private fun nudge(d: Long) { moveTo(playUs + d, false); followPlayhead() }

    /** Makes sure a piece boundary exists near [t]; returns its exact time, or null if t is at the very edge. */
    private fun ensureBoundary(t: Long): Long? {
        if (t < MIN_US || t > durationUs - MIN_US) return null
        for (s in segs) {
            if (kotlin.math.abs(s.startUs - t) < MIN_US && s.startUs > 0) return s.startUs
        }
        val i = indexAt(t)
        val s = segs[i]
        if (t - s.startUs < MIN_US || s.endUs - t < MIN_US) return null
        push()
        segs.add(i + 1, Seg(t, s.endUs, s.removed))
        s.endUs = t
        return t
    }

    private fun doSplit() {
        val before = segs.size
        val b = ensureBoundary(playUs)
        if (b == null || segs.size == before) { Ui.toast(this, "Move the playhead at least 0.1 s away from an edge or split"); return }
        selected = indexAt(playUs + 1)
        refresh()
    }

    private fun toggleRemoved() {
        if (selected !in segs.indices) return
        push()
        segs[selected].removed = !segs[selected].removed
        refresh()
    }

    private fun removeBeforeAfter(before: Boolean) {
        val n = pushes
        val b = ensureBoundary(playUs)
        if (b == null) { Ui.toast(this, "Move the playhead into the clip first"); return }
        if (pushes == n) push() // no new split was made, so save an undo state for the removal itself
        for (s in segs) {
            if (before && s.endUs <= b) s.removed = true
            if (!before && s.startUs >= b) s.removed = true
        }
        selected = segs.indexOfFirst { !it.removed }.coerceAtLeast(0)
        refresh()
    }

    private fun doUndo() {
        if (undo.isEmpty()) { Ui.toast(this, "Nothing to undo"); return }
        segs = ArrayList(undo.removeAt(undo.size - 1).map { it.copy() })
        selected = selected.coerceIn(0, segs.size - 1)
        timeline.segs = segs
        refresh()
    }

    // ------------------------------------------------------------------ display

    private fun t1(us: Long): String {
        val ms = us / 1000
        return "%d:%02d.%d".format(ms / 60000, ms / 1000 % 60, ms % 1000 / 100)
    }

    private fun resultUs(): Long {
        var d = 0L
        for (s in segs) if (!s.removed) { val e = effStart(s); if (e < s.endUs) d += s.endUs - e }
        return d
    }

    private fun updateInfo() {
        info.text = "${t1(playUs)} / ${t1(durationUs)}   Result: ${t1(resultUs())}"
    }

    private fun refresh() {
        timeline.segs = segs
        timeline.selected = selected
        timeline.playUs = playUs
        timeline.durationUs = durationUs
        timeline.invalidate()
        updateInfo()
        val s = segs.getOrNull(selected)
        delBtn.text = if (s != null && s.removed) "Restore piece" else "Remove piece"
        val sb = StringBuilder()
        if (s != null) {
            sb.append("Selected piece ${t1(s.startUs)} - ${t1(s.endUs)}, ${if (s.removed) "removed" else "kept"}. ")
            if (video && !s.removed) {
                val k = kfs
                if (k == null) sb.append(if (kfsDone) "" else "Analysing keyframes... ")
                else {
                    val lost = effStart(s) - s.startUs
                    if (lost > 50_000) sb.append("Its start moves ${"%.1f".format(lost / 1e6)} s later (next keyframe). ")
                }
            }
        }
        sb.append("Tap the timeline to move the playhead, drag it to scroll.")
        hint.text = sb.toString()
    }

    // ------------------------------------------------------------------ saving

    private fun saveDialog() {
        val kept = segs.filter { !it.removed }
        if (kept.isEmpty()) { Ui.toast(this, "Everything is removed"); return }
        if (segs.size == 1) { Ui.toast(this, "Nothing to cut yet. Split or remove a part first."); return }
        if (video && !kfsDone) { Ui.toast(this, "Still analysing the video. Try again in a moment."); return }
        val base = name.substringBeforeLast('.') + "_trimmed"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@EditorActivity, 20), Ui.dp(this@EditorActivity, 8), Ui.dp(this@EditorActivity, 20), 0)
        }
        col.addView(TextView(this).apply {
            text = "Saves $base as a new file, ${t1(resultUs())} long. Nothing is re-encoded, so quality stays identical."
        })
        val del = CheckBox(this).apply { text = "Delete the original after saving" }
        col.addView(del)
        AlertDialog.Builder(this).setTitle("Save trimmed clip").setView(col)
            .setPositiveButton("Save") { _, _ -> runExport(base, del.isChecked) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun runExport(base: String, deleteOriginal: Boolean) {
        mp?.let { if (it.isPlaying) it.pause() }
        playBtn.text = "Play"
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val label = TextView(this).apply { text = "Saving..." }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@EditorActivity, 24), Ui.dp(this@EditorActivity, 16), Ui.dp(this@EditorActivity, 24), Ui.dp(this@EditorActivity, 8))
            addView(label); addView(bar)
        }
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val dlg = AlertDialog.Builder(this).setTitle("Saving trimmed clip").setView(col).setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }.create()
        dlg.show()

        val pieces = segs.map { it.copy() }
        val k = kfs
        val useTree = uri.authority != android.provider.MediaStore.AUTHORITY
        val ctx = applicationContext
        Thread({
            var out: Output? = null
            var ok = false
            var err: String? = null
            try {
                out = Outputs.createNamed(ctx, prefs, video, base, useTree)
                ok = Trimmer.export(ctx, uri, pieces, k, out, { pct -> runOnUiThread { bar.progress = pct; label.text = "Saving... $pct%" } }, { cancelled.get() })
            } catch (e: Throwable) {
                err = e.message ?: e.javaClass.simpleName
            }
            try { out?.finish(ok) } catch (_: Exception) { }
            val fo = out
            val fok = ok
            val ferr = err
            runOnUiThread {
                try { dlg.dismiss() } catch (_: Exception) { }
                if (fok && fo != null) {
                    var deleted = false
                    if (deleteOriginal) deleted = Library.delete(this, uri)
                    Ui.toast(this, if (deleteOriginal && !deleted) "Saved. The original couldn't be deleted." else "Saved ${fo.name}")
                    startActivity(PlayerActivity.intent(this, fo.uri, fo.video, fo.name))
                    if (deleted) setResult(RESULT_OK, Intent().putExtra("deleted", true))
                    finish()
                } else if (!cancelled.get()) {
                    Ui.toast(this, "Couldn't save: ${ferr ?: "unknown error"}")
                }
            }
        }, "trim-export").start()
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onBackPressed() {
        if (undo.isEmpty() && segs.size <= 1) { super.onBackPressed(); return }
        AlertDialog.Builder(this).setTitle("Discard changes?")
            .setPositiveButton("Discard") { _, _ -> finish() }
            .setNegativeButton("Keep editing", null).show()
    }

    override fun onPause() {
        super.onPause()
        mp?.let { try { if (it.isPlaying) it.pause() } catch (_: Exception) { } }
        if (::playBtn.isInitialized) playBtn.text = "Play"
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        wave?.cancel = true
        try { mp?.release() } catch (_: Exception) { }
        mp = null
        super.onDestroy()
    }
}
