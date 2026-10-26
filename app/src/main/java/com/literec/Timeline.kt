package com.literec

import android.content.Context
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Waveform + pieces + playhead. Lives inside a HorizontalScrollView; its width is set by the zoom level.
 * Blue = kept, red = removed, dark overlay = the bit lost when a piece starts at the next keyframe.
 */
class Timeline(ctx: Context) : View(ctx) {
    var durationUs = 1L
    var segs: List<Seg> = emptyList()
    var selected = 0
    var playUs = 0L
    var wave: WaveData? = null
    var snap: ((Long) -> Long)? = null
    var onTap: ((Long) -> Unit)? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val txt = Ui.attr(ctx, android.R.attr.textColorPrimary)
    private val sec = Ui.attr(ctx, android.R.attr.textColorSecondary)
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val dens = ctx.resources.displayMetrics.density
    private val rulerH = Ui.sz(ctx, 24).toFloat()
    private var downX = 0f

    private val steps = longArrayOf(1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1800, 3600)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Ui.sz(context, 130))
    }

    private fun xOf(us: Long): Float = us.toFloat() / durationUs * width
    fun usOf(x: Float): Long = (x / max(1, width) * durationUs).toLong().coerceIn(0L, durationUs)

    override fun onDraw(c: android.graphics.Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val clip = c.clipBounds
        val x0 = max(0, clip.left)
        val x1 = min(width, clip.right)

        // pieces
        for ((i, s) in segs.withIndex()) {
            val l = xOf(s.startUs)
            val r = xOf(s.endUs)
            if (r < x0 || l > x1) continue
            p.style = Paint.Style.FILL
            p.color = if (s.removed) 0x44E53935 else 0x332196F3
            c.drawRect(l, rulerH, r, h, p)
            val sn = snap
            if (sn != null && !s.removed) {
                val lostEnd = min(sn(s.startUs), s.endUs)
                if (lostEnd > s.startUs) {
                    p.color = 0xAA000000.toInt()
                    c.drawRect(l, rulerH, xOf(lostEnd), h, p)
                }
            }
            if (i == selected) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.5f * dens
                p.color = 0xFFFFC107.toInt()
                c.drawRect(l + dens, rulerH + dens, r - dens, h - dens, p)
            }
        }

        // waveform
        val wd = wave
        val mid = (rulerH + h) / 2f
        val maxHalf = (h - rulerH) / 2f - 4 * dens
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1f
        if (wd != null) {
            val pk = wd.peaks
            val filled = wd.filled
            var si = 0
            val perPx = durationUs / w
            for (x in x0 until x1) {
                val t0 = (x * perPx).toLong()
                while (si < segs.size - 1 && segs[si].endUs <= t0) si++
                val b0 = (t0 / 1_000_000.0 * WaveData.PER_SEC).toInt()
                val b1 = max(b0, (((x + 1) * perPx) / 1_000_000.0 * WaveData.PER_SEC).toInt())
                var a = 0
                var b = b0
                while (b <= b1 && b < pk.size && b <= filled) { a = max(a, pk[b].toInt() and 0xFF); b++ }
                if (a == 0) continue
                val half = a / 255f * maxHalf + 1f
                p.color = if (segs.getOrNull(si)?.removed == true) 0xFF9E9E9E.toInt() else 0xFF1E88E5.toInt()
                c.drawLine(x + 0.5f, mid - half, x + 0.5f, mid + half, p)
            }
        }

        // split lines
        p.color = txt
        p.strokeWidth = 1.5f * dens
        for (i in 1 until segs.size) {
            val x = xOf(segs[i].startUs)
            if (x in x0.toFloat()..x1.toFloat()) c.drawLine(x, rulerH, x, h, p)
        }

        // ruler
        val pxPerSec = w / (durationUs / 1_000_000f)
        var step = steps.last()
        for (s in steps) if (s * pxPerSec >= 72 * dens) { step = s; break }
        p.style = Paint.Style.FILL
        p.textSize = 11f * dens * Ui.scale
        p.strokeWidth = 1f
        val firstTick = floor(x0 / pxPerSec / step).toLong() * step
        val lastTick = ceil(x1 / pxPerSec / step).toLong() * step
        var t = max(0L, firstTick)
        while (t <= lastTick) {
            val x = t * pxPerSec
            p.color = sec
            c.drawLine(x, rulerH - 6 * dens, x, rulerH, p)
            c.drawText(Ui.fmtTime(t * 1000), x + 3 * dens, rulerH - 8 * dens, p)
            t += step
        }

        // playhead
        val px = xOf(playUs)
        p.color = 0xFFE53935.toInt()
        p.style = Paint.Style.FILL
        p.strokeWidth = 2f * dens
        c.drawLine(px, 0f, px, h, p)
        path.reset()
        path.moveTo(px - 6 * dens, 0f); path.lineTo(px + 6 * dens, 0f); path.lineTo(px, 9 * dens); path.close()
        c.drawPath(path, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> downX = e.x
            MotionEvent.ACTION_UP -> if (abs(e.x - downX) < slop) { onTap?.invoke(usOf(e.x)); performClick() }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    fun xForScroll(us: Long): Int = xOf(us).toInt()
}
