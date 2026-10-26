package com.literec

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide recording state shared by the service, the UI and the tile. Mutated on the main thread. */
object RecState {
    enum class S { IDLE, COUNTDOWN, RECORDING, PAUSED, SAVING }

    @Volatile var s = S.IDLE
    @Volatile var countdown = 0
    @Volatile private var accum = 0L
    @Volatile private var lastResume = 0L

    @Volatile var lastUri: Uri? = null
    @Volatile var lastName: String = ""
    @Volatile var lastVideo = true

    val listeners = CopyOnWriteArrayList<Runnable>()
    private val main = Handler(Looper.getMainLooper())

    fun elapsed(): Long =
        accum + if (s == S.RECORDING) SystemClock.elapsedRealtime() - lastResume else 0L

    fun beginTimer() { accum = 0L; lastResume = SystemClock.elapsedRealtime() }
    fun pauseTimer() { accum += SystemClock.elapsedRealtime() - lastResume }
    fun resumeTimer() { lastResume = SystemClock.elapsedRealtime() }

    fun post() { main.post { for (l in listeners) l.run() } }
}
