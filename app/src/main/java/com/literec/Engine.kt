package com.literec

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Bundle
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One recording session. Each source is an independent pipeline and only exists if enabled:
 *   screen       -> VirtualDisplay -> MediaCodec(surface) ----------------\
 *   system audio -> AudioRecord(playback capture) -\                       >-> MediaMuxer -> file
 *   mic          -> AudioRecord(mic) --------------+-> mixer -> AAC -------/
 * All timestamps share one monotonic clock (System.nanoTime) and are forced to be increasing.
 */
class Engine(
    private val cfg: Cfg,
    private val projection: MediaProjection?,
    private val out: Output,
    private val onError: (String) -> Unit
) {
    class Cfg(
        val screen: Boolean, val sys: Boolean, val mic: Boolean,
        val cands: List<Caps.Cand>, val bitrate: (Caps.Cand) -> Int, val dpi: Int,
        val rate: Int, val audioBps: Int, val sysGain: Int, val micGain: Int
    )

    companion object {
        const val CHUNK = 1024 // frames per AAC input chunk
        private fun nowUs() = System.nanoTime() / 1000
    }

    @Volatile private var running = false
    @Volatile var paused = false; private set
    @Volatile private var needKey = false
    @Volatile private var stopDeadlineNs = Long.MAX_VALUE
    private var pauseStartUs = 0L
    @Volatile private var pausedTotalUs = 0L
    private var recordStartUs = 0L
    private val errored = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    private lateinit var muxer: SafeMuxer
    var chosen: Caps.Cand? = null; private set

    private var vEnc: MediaCodec? = null
    private var vSurface: Surface? = null
    private var vDisplay: VirtualDisplay? = null
    private var vThread: Thread? = null

    private var aEnc: MediaCodec? = null
    private var aThread: Thread? = null
    private var sysRec: AudioRecord? = null
    private var micRec: AudioRecord? = null
    private var outCh = 1
    @Volatile private var audioBaseUs = 0L
    private var outFrames = 0L

    private val hasAudio get() = cfg.sys || cfg.mic

    private fun fail(msg: String) {
        if (errored.compareAndSet(false, true)) onError(msg)
    }

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        val mm = MediaMuxer(out.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = SafeMuxer(mm, (if (cfg.screen) 1 else 0) + (if (hasAudio) 1 else 0))
        try {
            if (cfg.screen) prepareVideo()
            if (hasAudio) prepareAudio()
            recordStartUs = nowUs()
            running = true
            if (hasAudio) { aThread = Thread({ audioLoop() }, "rec-audio").also { it.start() } }
            if (cfg.screen) {
                vThread = Thread({ videoLoop() }, "rec-video").also { it.start() }
                val c = chosen!!
                vDisplay = projection!!.createVirtualDisplay(
                    "LiteRec", c.w, c.h, cfg.dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, vSurface, null, null
                )
            }
        } catch (e: Throwable) {
            running = false
            releaseAll()
            throw e
        }
    }

    fun pause() {
        if (paused) return
        pauseStartUs = nowUs()
        paused = true
    }

    fun resume() {
        if (!paused) return
        pausedTotalUs += nowUs() - pauseStartUs
        needKey = true
        try {
            vEnc?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) { }
        paused = false
    }

    /** Finishes the file. Returns true if a playable file was written. */
    fun stop(): Boolean {
        if (!stopped.compareAndSet(false, true)) return false
        running = false
        stopDeadlineNs = System.nanoTime() + 4_000_000_000L
        try { vDisplay?.release() } catch (_: Exception) { }
        vDisplay = null
        try { vEnc?.signalEndOfInputStream() } catch (_: Exception) { }
        try { vThread?.join(5000) } catch (_: Exception) { }
        try { aThread?.join(5000) } catch (_: Exception) { }
        releaseCodecs()
        return muxer.finish()
    }

    private fun releaseAll() {
        try { vDisplay?.release() } catch (_: Exception) { }
        releaseCodecs()
        stopRecs()
        try { muxer.finish() } catch (_: Exception) { }
    }

    private fun releaseCodecs() {
        for (c in listOf(vEnc, aEnc)) {
            if (c == null) continue
            try { c.stop() } catch (_: Exception) { }
            try { c.release() } catch (_: Exception) { }
        }
        vEnc = null; aEnc = null
        try { vSurface?.release() } catch (_: Exception) { }
        vSurface = null
        stopRecs()
    }

    private fun stopRecs() {
        for (r in listOf(sysRec, micRec)) {
            if (r == null) continue
            try { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() } catch (_: Exception) { }
            try { r.release() } catch (_: Exception) { }
        }
        sysRec = null; micRec = null
    }

    // ---------------------------------------------------------------- timestamps

    /** Raw monotonic timestamp -> file timestamp (µs from start, pauses removed). -1 = drop. */
    private fun adjust(rawUs: Long): Long {
        if (paused) return -1
        val t = rawUs - recordStartUs - pausedTotalUs
        return if (t < 0) 0 else t
    }

    // ---------------------------------------------------------------- video

    private fun prepareVideo() {
        for (c in cfg.cands) {
            var codec: MediaCodec? = null
            try {
                val info = Caps.pick(c.mime) ?: continue
                codec = MediaCodec.createByCodecName(info.name)
                val f = MediaFormat.createVideoFormat(c.mime, c.w, c.h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, cfg.bitrate(c))
                    setInteger(MediaFormat.KEY_FRAME_RATE, c.fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    try {
                        val enc = info.getCapabilitiesForType(c.mime).encoderCapabilities
                        if (enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
                            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                        }
                    } catch (_: Exception) { }
                    // Keeps frames (and timestamps) flowing on a static screen so A/V stays in sync.
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L)
                }
                codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = codec.createInputSurface()
                codec.start()
                vEnc = codec; vSurface = surface; chosen = c
                return
            } catch (e: Exception) {
                try { codec?.release() } catch (_: Exception) { }
            }
        }
        throw IllegalStateException("This device can't encode video with any available setting")
    }

    private fun videoLoop() {
        val enc = vEnc ?: return
        val info = MediaCodec.BufferInfo()
        var skew = Long.MIN_VALUE
        try {
            while (true) {
                val i = enc.dequeueOutputBuffer(info, 10_000)
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxer.addTrack(true, enc.outputFormat)
                } else if (i >= 0) {
                    val buf = enc.getOutputBuffer(i)
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (buf != null && info.size > 0 && !config) {
                        if (skew == Long.MIN_VALUE) {
                            // Normally video timestamps are CLOCK_MONOTONIC already; if not, learn the offset.
                            val d = info.presentationTimeUs - nowUs()
                            skew = if (abs(d) > 2_000_000L) d else 0L
                        }
                        var keep = true
                        if (needKey) {
                            if ((info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) needKey = false else keep = false
                        }
                        val pts = if (keep) adjust(info.presentationTimeUs - skew) else -1L
                        if (pts >= 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            info.presentationTimeUs = pts
                            muxer.write(true, buf, info)
                        }
                    }
                    enc.releaseOutputBuffer(i, false)
                    if (eos) break
                }
                if (muxer.failed) { fail("Couldn't write to the file (${muxer.error})"); break }
                if (!running && System.nanoTime() > stopDeadlineNs) break
            }
        } catch (e: Exception) {
            if (running) fail("Video encoder stopped: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- audio

    @SuppressLint("MissingPermission")
    private fun prepareAudio() {
        val rate = cfg.rate
        outCh = if (cfg.sys) 2 else 1
        if (cfg.sys) {
            val pc = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minB = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(pc)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(max(minB * 4, CHUNK * 4 * 8))
                .build()
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); throw IllegalStateException("System audio capture unavailable") }
            sysRec = r
        }
        if (cfg.mic) {
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minB = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(max(minB * 4, CHUNK * 2 * 8))
                .build()
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); throw IllegalStateException("Microphone unavailable") }
            micRec = r
        }
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, outCh).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, cfg.audioBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * 2 * 2 * 4)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        aEnc = enc
    }

    /** Small ring buffer of PCM samples; oldest samples are dropped on overflow. */
    private class Fifo(cap: Int) {
        private val b = ShortArray(cap)
        private var h = 0
        var size = 0; private set
        fun put(s: ShortArray, n: Int) {
            for (i in 0 until n) {
                if (size == b.size) { h = (h + 1) % b.size; size-- }
                b[(h + size) % b.size] = s[i]
                size++
            }
        }
        fun take(d: ShortArray, n: Int) {
            val m = min(n, size)
            for (i in 0 until m) { d[i] = b[h]; h = (h + 1) % b.size }
            size -= m
            for (i in m until n) d[i] = 0
        }
    }

    private fun clamp(v: Int): Short = (if (v > 32767) 32767 else if (v < -32768) -32768 else v).toShort()

    private fun audioLoop() {
        val enc = aEnc ?: return
        val rate = cfg.rate
        val chunk = CHUNK
        val chunkUs = chunk * 1_000_000L / rate
        val sys = sysRec
        val mic = micRec
        val sysFifo = Fifo(chunk * 2 * 16)
        val micFifo = Fifo(chunk * 16)
        val tmp = ShortArray(chunk * 2)
        val sysChunk = ShortArray(chunk * 2)
        val micChunk = ShortArray(chunk)
        val mix = ShortArray(chunk * outCh)
        val info = MediaCodec.BufferInfo()
        val sg = cfg.sysGain
        val mg = cfg.micGain
        var k = 0L
        var anchor = -1L
        try {
            sys?.startRecording()
            mic?.startRecording()
            while (running) {
                var did = false
                if (sys != null) {
                    val n = sys.read(tmp, 0, tmp.size, AudioRecord.READ_NON_BLOCKING)
                    if (n < 0) throw IllegalStateException("System audio read error $n")
                    if (n > 0) { sysFifo.put(tmp, n and 1.inv()); did = true }
                }
                if (mic != null) {
                    val n = mic.read(tmp, 0, chunk, AudioRecord.READ_NON_BLOCKING)
                    if (n < 0) throw IllegalStateException("Microphone read error $n")
                    if (n > 0) { micFifo.put(tmp, n); did = true }
                }
                while (true) {
                    val sf = sysFifo.size / 2
                    val mf = micFifo.size
                    val ready = when {
                        sys != null && mic != null -> (sf >= chunk && mf >= chunk) || max(sf, mf) >= chunk * 4
                        sys != null -> sf >= chunk
                        else -> mf >= chunk
                    }
                    if (!ready) break
                    if (sys != null) sysFifo.take(sysChunk, chunk * 2)
                    if (mic != null) micFifo.take(micChunk, chunk)
                    if (outCh == 2) {
                        for (i in 0 until chunk) {
                            val m = if (mic != null) micChunk[i] * mg / 100 else 0
                            val l = (if (sys != null) sysChunk[2 * i] * sg / 100 else 0) + m
                            val r = (if (sys != null) sysChunk[2 * i + 1] * sg / 100 else 0) + m
                            mix[2 * i] = clamp(l)
                            mix[2 * i + 1] = clamp(r)
                        }
                    } else {
                        for (i in 0 until chunk) mix[i] = clamp(micChunk[i] * mg / 100)
                    }
                    if (anchor < 0) {
                        anchor = nowUs() - chunkUs
                        audioBaseUs = max(0L, anchor - recordStartUs)
                    }
                    val pts = k * chunkUs
                    k++
                    if (!paused) queueAudio(enc, mix, pts, info)
                    did = true
                }
                drainAudio(enc, info)
                if (muxer.failed) { fail("Couldn't write to the file (${muxer.error})"); break }
                if (!did) Thread.sleep(4)
            }
            // End of stream
            queueAudio(enc, null, k * chunkUs, info)
            while (System.nanoTime() < stopDeadlineNs) {
                if (drainAudio(enc, info)) break
                Thread.sleep(5)
            }
        } catch (e: Exception) {
            if (running) fail("Audio recording stopped: ${e.message}")
        } finally {
            stopRecs()
        }
    }

    private fun queueAudio(enc: MediaCodec, data: ShortArray?, pts: Long, info: MediaCodec.BufferInfo) {
        var tries = 0
        while (tries < 40) {
            val i = enc.dequeueInputBuffer(5_000)
            if (i >= 0) {
                val b = enc.getInputBuffer(i) ?: return
                b.clear()
                if (data != null) {
                    b.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(data)
                    enc.queueInputBuffer(i, 0, data.size * 2, max(0L, pts), 0)
                } else {
                    enc.queueInputBuffer(i, 0, 0, max(0L, pts), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                return
            }
            drainAudio(enc, info)
            tries++
        }
    }

    /** Pulls finished AAC frames into the muxer. Returns true once end-of-stream was seen. */
    private fun drainAudio(enc: MediaCodec, info: MediaCodec.BufferInfo): Boolean {
        while (true) {
            val i = enc.dequeueOutputBuffer(info, 0)
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) return false
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxer.addTrack(false, enc.outputFormat)
            } else if (i >= 0) {
                val buf = enc.getOutputBuffer(i)
                val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (buf != null && info.size > 0 && !config) {
                    // Audio timestamps come from our own frame counter, not from the encoder, so a pause
                    // (when no audio is fed in) can never create a gap or a backwards jump.
                    val pts = audioBaseUs + outFrames * 1_024_000_000L / cfg.rate
                    outFrames++
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    info.presentationTimeUs = pts
                    muxer.write(false, buf, info)
                }
                enc.releaseOutputBuffer(i, false)
                if (eos) return true
            }
        }
    }
}

/**
 * Wraps MediaMuxer: starts it once every expected track has a format, queues samples that arrive
 * early, and guarantees strictly increasing timestamps per track (a common cause of muxer crashes).
 */
class SafeMuxer(private val mm: MediaMuxer, private val expected: Int) {
    private class Pending(val t: Int, val data: ByteArray, val pts: Long, val flags: Int)

    private var added = 0
    private var started = false
    private val idx = intArrayOf(-1, -1) // 0 = video, 1 = audio
    private val last = longArrayOf(-1, -1)
    private val pending = ArrayList<Pending>()
    private var pendingBytes = 0
    private val bi = MediaCodec.BufferInfo()

    @Volatile var wrote = false; private set
    @Volatile var failed = false; private set
    @Volatile var error = ""; private set
    private var errors = 0

    @Synchronized fun addTrack(video: Boolean, f: MediaFormat) {
        val t = if (video) 0 else 1
        if (idx[t] >= 0) return
        idx[t] = mm.addTrack(f)
        added++
        if (added == expected) {
            mm.start()
            started = true
            for (p in pending) put(p.t, ByteBuffer.wrap(p.data), p.pts, p.flags)
            pending.clear()
            pendingBytes = 0
        }
    }

    @Synchronized fun write(video: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (failed) return
        val t = if (video) 0 else 1
        if (!started) {
            if (pendingBytes < 24_000_000) {
                val a = ByteArray(info.size)
                buf.get(a)
                pending.add(Pending(t, a, info.presentationTimeUs, info.flags))
                pendingBytes += a.size
            }
            return
        }
        put(t, buf, info.presentationTimeUs, info.flags)
    }

    private fun put(t: Int, buf: ByteBuffer, pts: Long, flags: Int) {
        var p = pts
        if (p <= last[t]) p = last[t] + 1
        last[t] = p
        bi.set(buf.position(), buf.remaining(), p, flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
        try {
            mm.writeSampleData(idx[t], buf, bi)
            wrote = true
            errors = 0
        } catch (e: Exception) {
            // One bad sample shouldn't end the recording; give up only if writes keep failing.
            error = e.javaClass.simpleName + ": " + e.message
            if (++errors >= 20) failed = true
        }
    }

    /** Returns true if the file contains data and was closed cleanly. */
    @Synchronized fun finish(): Boolean {
        var ok = started && wrote
        try { if (started) mm.stop() } catch (e: Exception) { ok = false }
        try { mm.release() } catch (_: Exception) { }
        return ok
    }
}
