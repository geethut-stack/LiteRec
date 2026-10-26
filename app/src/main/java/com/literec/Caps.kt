package com.literec

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Display
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Detects what the device's video encoders can really do, and builds fallback chains. */
object Caps {
    const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
    const val HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC

    class Cand(val mime: String, val w: Int, val h: Int, val fps: Int) {
        val label get() = "${w}x$h @ ${fps}fps ${if (mime == HEVC) "H.265" else "H.264"}"
    }

    fun pick(mime: String): MediaCodecInfo? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { ci ->
            ci.isEncoder && ci.supportedTypes.any { it.equals(mime, true) }
        }
        return list.firstOrNull { it.isHardwareAccelerated && !it.isSoftwareOnly } ?: list.firstOrNull()
    }

    fun video(mime: String): MediaCodecInfo.VideoCapabilities? = try {
        pick(mime)?.getCapabilitiesForType(mime)?.videoCapabilities
    } catch (e: Exception) { null }

    @Suppress("DEPRECATION")
    fun screenSize(ctx: Context): Point {
        val p = Point()
        val dm = ctx.getSystemService(DisplayManager::class.java)
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p)
        return p
    }

    private fun align(v: Int, a: Int): Int = max(a, v / a * a)

    /** Output size for a given short-side target (0 = native), orientation mode, and encoder alignment. */
    fun size(native: Point, shortSide: Int, orient: Int, v: MediaCodecInfo.VideoCapabilities?): Pair<Int, Int> {
        val ns = min(native.x, native.y)
        val nl = max(native.x, native.y)
        val scale = if (shortSide <= 0 || shortSide >= ns) 1.0 else shortSide.toDouble() / ns
        val sh = (ns * scale).roundToInt()
        val lo = (nl * scale).roundToInt()
        val portrait = when (orient) { 1 -> true; 2 -> false; else -> native.x <= native.y }
        var w = if (portrait) sh else lo
        var h = if (portrait) lo else sh
        val wa = max(2, v?.widthAlignment ?: 2)
        val ha = max(2, v?.heightAlignment ?: 2)
        w = align(w, wa)
        h = align(h, ha)
        return w to h
    }

    private val SHORTS = listOf(2160, 1440, 1080, 720, 480)

    /** Ordered list of settings to try: the requested one first, then progressively safer ones. */
    fun candidates(ctx: Context, p: Prefs): List<Cand> {
        val native = screenSize(ctx)
        val nativeShort = min(native.x, native.y)
        val out = ArrayList<Cand>()
        val mimes = if (p.hevc && video(HEVC) != null) listOf(HEVC, AVC) else listOf(AVC)
        for (mime in mimes) {
            val v = video(mime) ?: continue
            val cap = if (p.resolution == 0) nativeShort else p.resolution
            val steps = ArrayList<Int>()
            steps.add(p.resolution)
            for (s in SHORTS) if (s < cap && s < nativeShort) steps.add(s)
            for (s in steps) {
                val (w, h) = size(native, s, p.orientation, v)
                for (f in listOf(p.fps, 30).distinct()) {
                    val ok = try { v.areSizeAndRateSupported(w, h, f.toDouble()) } catch (e: Exception) { false }
                    if (ok) out.add(Cand(mime, w, h, f))
                }
            }
        }
        if (out.isEmpty()) {
            val (w, h) = size(native, 720, p.orientation, video(AVC))
            out.add(Cand(AVC, w, h, 30))
        }
        return out.distinctBy { "${it.mime}${it.w}${it.h}${it.fps}" }
    }

    fun bitrate(p: Prefs, c: Cand): Int {
        var bps: Long = if (p.bitrateMbps > 0) p.bitrateMbps * 1_000_000L else {
            val bpp = if (c.mime == HEVC) 0.045 else 0.07
            (c.w.toLong() * c.h * c.fps * bpp).toLong().coerceIn(2_000_000L, 60_000_000L)
        }
        video(c.mime)?.bitrateRange?.let { bps = bps.coerceIn(it.lower.toLong(), it.upper.toLong()) }
        return bps.toInt()
    }

    fun describe(): String {
        fun one(name: String, mime: String): String {
            val v = video(mime) ?: return "$name: not available"
            return "$name: up to ${v.supportedWidths.upper}x${v.supportedHeights.upper}"
        }
        return one("H.264", AVC) + "\n" + one("H.265", HEVC)
    }

    @Suppress("unused")
    fun near(a: Int, b: Int) = abs(a - b) < 4
}
