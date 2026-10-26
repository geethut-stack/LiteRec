package com.literec

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** A piece of the timeline. Pieces are contiguous and cover the whole file. */
class Seg(var startUs: Long, var endUs: Long, var removed: Boolean) {
    fun copy() = Seg(startUs, endUs, removed)
}

/** Waveform peaks, PER_SEC buckets per second, 0..255. Filled in by a background thread. */
class WaveData(val peaks: ByteArray) {
    @Volatile var filled = 0
    @Volatile var done = false
    @Volatile var cancel = false

    companion object {
        const val PER_SEC = 50
        fun forDuration(durationUs: Long) = WaveData(ByteArray((durationUs / 1_000_000L * PER_SEC + PER_SEC + 2).toInt()))
    }

    /** Decodes the first audio track and records the loudest sample in every bucket. */
    fun decode(ctx: Context, uri: Uri) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var ti = -1
            for (i in 0 until ex.trackCount) {
                if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { ti = i; break }
            }
            if (ti < 0) return
            ex.selectTrack(ti)
            val fmt = ex.getTrackFormat(ti)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = max(1, fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var frames = 0L
            var lastB = 0
            var cur = 0
            fun store(b: Int, peak: Int) {
                if (b in 0 until peaks.size) {
                    peaks[b] = (sqrt(peak / 32768.0) * 255).toInt().coerceIn(0, 255).toByte()
                    filled = b
                }
            }
            while (!outDone && !cancel) {
                if (!inDone) {
                    val i = c.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val b = c.getInputBuffer(i)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 5000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = max(1, f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                } else if (o >= 0) {
                    if (info.size > 0) {
                        val bb = c.getOutputBuffer(o)!!
                        bb.position(info.offset)
                        bb.limit(info.offset + info.size)
                        val sb = bb.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val spb = max(1, rate / PER_SEC)
                        val n = sb.remaining() / ch
                        for (f in 0 until n) {
                            var a = 0
                            for (k in 0 until ch) a = max(a, abs(sb.get().toInt()))
                            val b = (frames / spb).toInt()
                            if (b != lastB) { store(lastB, cur); lastB = b; cur = 0 }
                            if (a > cur) cur = a
                            frames++
                        }
                    }
                    c.releaseOutputBuffer(o, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                }
            }
            store(lastB, cur)
        } catch (_: Exception) {
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { ex.release() } catch (_: Exception) { }
            done = true
        }
    }
}

object Trimmer {

    /** Times of all keyframes (sync samples) in the first video track, or null if there's no video. */
    fun keyframes(ctx: Context, uri: Uri): LongArray? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            var ti = -1
            for (i in 0 until ex.trackCount) {
                if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) { ti = i; break }
            }
            if (ti < 0) return null
            ex.selectTrack(ti)
            val out = ArrayList<Long>()
            while (true) {
                val t = ex.sampleTime
                if (t < 0) break
                if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) out.add(t)
                ex.advance()
            }
            return out.toLongArray()
        } catch (e: Exception) {
            return null
        } finally {
            try { ex.release() } catch (_: Exception) { }
        }
    }

    /** First keyframe at or after [t]; Long.MAX_VALUE if there is none. */
    fun snap(kfs: LongArray, t: Long): Long {
        var lo = 0
        var hi = kfs.size
        while (lo < hi) {
            val m = (lo + hi) / 2
            if (kfs[m] < t) lo = m + 1 else hi = m
        }
        return if (lo < kfs.size) kfs[lo] else Long.MAX_VALUE
    }

    /**
     * Lossless export: copies the compressed samples of every kept piece into a new file, nothing is
     * re-encoded. Video pieces start at the first keyframe at or after their start; ends are exact.
     */
    fun export(
        ctx: Context, uri: Uri, segs: List<Seg>, kfs: LongArray?, out: Output,
        progress: (Int) -> Unit, cancelled: () -> Boolean
    ): Boolean {
        val ex = MediaExtractor()
        var mm: MediaMuxer? = null
        var started = false
        var wrote = false
        try {
            ex.setDataSource(ctx, uri, null)
            mm = MediaMuxer(out.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val map = HashMap<Int, Int>()
            var vTrack = -1
            var bufSize = 6 shl 20
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                map[i] = mm.addTrack(f)
                ex.selectTrack(i)
                if (mime.startsWith("video/")) {
                    vTrack = i
                    if (f.containsKey(MediaFormat.KEY_ROTATION)) mm.setOrientationHint(f.getInteger(MediaFormat.KEY_ROTATION))
                }
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) bufSize = max(bufSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
            if (map.isEmpty()) throw IllegalStateException("No audio or video found")

            // Work out the real start of each kept piece.
            class Piece(val s: Long, val e: Long)
            val pieces = ArrayList<Piece>()
            for (g in segs) {
                if (g.removed) continue
                var s = g.startUs
                if (vTrack >= 0 && kfs != null) s = snap(kfs, s)
                if (s < g.endUs) pieces.add(Piece(s, g.endUs))
            }
            if (pieces.isEmpty()) throw IllegalStateException("Nothing left to save")
            val total = max(1L, pieces.sumOf { it.e - it.s })

            mm.start()
            started = true
            val buf = ByteBuffer.allocate(bufSize)
            val info = MediaCodec.BufferInfo()
            val last = HashMap<Int, Long>()
            var offset = 0L

            for (pc in pieces) {
                if (cancelled()) return false
                ex.seekTo(pc.s, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val passed = HashSet<Int>()
                var videoStarted = vTrack < 0
                while (passed.size < map.size) {
                    if (cancelled()) return false
                    val t = ex.sampleTime
                    if (t < 0) break
                    val ti = ex.sampleTrackIndex
                    if (t >= pc.e) { passed.add(ti); ex.advance(); continue }
                    if (t < pc.s) { ex.advance(); continue }
                    val sync = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    if (ti == vTrack && !videoStarted) {
                        if (!sync) { ex.advance(); continue }
                        videoStarted = true
                    }
                    buf.clear()
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    var pts = t - pc.s + offset
                    val lp = last[ti]
                    if (lp != null && pts <= lp) pts = lp + 1
                    last[ti] = pts
                    info.set(0, n, pts, if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    mm.writeSampleData(map[ti]!!, buf, info)
                    wrote = true
                    progress(((offset + (t - pc.s)) * 100 / total).toInt().coerceIn(0, 99))
                    ex.advance()
                }
                offset += pc.e - pc.s
            }
            progress(100)
            try { mm.stop() } catch (e: Exception) { wrote = false }
            started = false
            return wrote
        } finally {
            try { if (started) mm?.stop() } catch (_: Exception) { wrote = false }
            try { mm?.release() } catch (_: Exception) { }
            try { ex.release() } catch (_: Exception) { }
        }
    }
}
