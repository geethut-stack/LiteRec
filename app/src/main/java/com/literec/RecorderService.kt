package com.literec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StatFs

class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.literec.START"
        const val ACTION_STOP = "com.literec.STOP"
        const val ACTION_PAUSE = "com.literec.PAUSE"
        const val ACTION_RESUME = "com.literec.RESUME"
        const val X_SCREEN = "screen"
        const val X_SYS = "sys"
        const val X_MIC = "mic"
        const val X_CODE = "code"
        const val X_DATA = "data"
        private const val CH_REC = "rec"
        private const val CH_SAVED = "saved"
        private const val ID_REC = 1
        private const val ID_SAVED = 2
        private const val MIN_FREE = 60L * 1024 * 1024
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var projection: MediaProjection? = null
    private var engine: Engine? = null
    private var output: Output? = null
    @Volatile private var cancelled = false
    private var srcScreen = false
    private var srcSys = false
    private var srcMic = false
    private var lockReceiver: BroadcastReceiver? = null
    private val storageCheck = object : Runnable {
        override fun run() {
            if (RecState.s != RecState.S.RECORDING && RecState.s != RecState.S.PAUSED) return
            try {
                val free = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
                if (free < MIN_FREE) {
                    Ui.toast(this@RecorderService, "Stopped: storage almost full")
                    stopRecording()
                    return
                }
            } catch (_: Exception) { }
            main.postDelayed(this, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_REC, "Recording", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_SAVED, "Saved recordings", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> pause(true)
            ACTION_RESUME -> pause(false)
            else -> if (RecState.s == RecState.S.IDLE) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------ start

    @Suppress("DEPRECATION")
    private fun start(i: Intent) {
        srcScreen = i.getBooleanExtra(X_SCREEN, false)
        srcSys = i.getBooleanExtra(X_SYS, false)
        srcMic = i.getBooleanExtra(X_MIC, false)

        var types = 0
        if (srcScreen || srcSys) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (srcMic && Build.VERSION.SDK_INT >= 30) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (types == 0) types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        try {
            startForeground(ID_REC, recNotification(), types)
        } catch (e: Exception) {
            Ui.toast(this, "Can't start the recorder: ${e.message}")
            stopSelf()
            return
        }
        if (RecState.s != RecState.S.IDLE) return

        if (srcScreen || srcSys) {
            val code = i.getIntExtra(X_CODE, 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(X_DATA, Intent::class.java)
            else i.getParcelableExtra(X_DATA)
            if (data == null) { abort("Missing screen-capture permission"); return }
            try {
                val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        main.post {
                            if (RecState.s == RecState.S.RECORDING || RecState.s == RecState.S.PAUSED) stopRecording()
                        }
                    }
                }, main)
                projection = p
            } catch (e: Exception) {
                abort("Screen capture failed: ${e.message}")
                return
            }
        }

        cancelled = false
        val n = prefs.countdown
        RecState.countdown = n
        RecState.s = if (n > 0) RecState.S.COUNTDOWN else RecState.S.RECORDING
        RecState.post()
        Thread({ begin(n) }, "rec-start").start()
    }

    private fun begin(n: Int) {
        try {
            var left = n
            while (left > 0 && !cancelled) {
                RecState.countdown = left
                RecState.post()
                main.post { notifyRec() }
                Thread.sleep(1000)
                left--
            }
            if (cancelled) { main.post { cleanup() }; return }

            val cands = Caps.candidates(this, prefs)
            val out = Outputs.create(this, prefs, srcScreen)
            output = out
            val cfg = Engine.Cfg(
                srcScreen, srcSys, srcMic, cands, { c -> Caps.bitrate(prefs, c) },
                resources.displayMetrics.densityDpi,
                prefs.sampleRate, prefs.audioKbps * 1000, prefs.sysGain, prefs.micGain
            )
            val eng = Engine(cfg, projection, out) { msg ->
                main.post { Ui.toast(this, msg); stopRecording() }
            }
            eng.start()
            engine = eng
            val chosen = eng.chosen
            main.post {
                if (out.fellBack) Ui.toast(this, "Custom folder unavailable - saving to the default location")
                if (chosen != null && cands.isNotEmpty() && chosen !== cands[0]) {
                    Ui.toast(this, "Device limit - recording at ${chosen.label}")
                }
                RecState.beginTimer()
                RecState.s = RecState.S.RECORDING
                RecState.post()
                notifyRec()
                registerWatchers()
                if (cancelled) stopRecording()
            }
        } catch (e: Throwable) {
            val msg = e.message ?: e.javaClass.simpleName
            try { output?.finish(false) } catch (_: Exception) { }
            main.post {
                Ui.toast(this, "Couldn't start recording: $msg")
                cleanup()
            }
        }
    }

    private fun registerWatchers() {
        if (prefs.stopOnLock) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { stopRecording() }
            }
            lockReceiver = r
            val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(r, f)
        }
        main.postDelayed(storageCheck, 5000)
    }

    // ------------------------------------------------------------ pause / stop

    private fun pause(p: Boolean) {
        val e = engine ?: return
        if (p && RecState.s == RecState.S.RECORDING) {
            e.pause()
            RecState.pauseTimer()
            RecState.s = RecState.S.PAUSED
        } else if (!p && RecState.s == RecState.S.PAUSED) {
            e.resume()
            RecState.resumeTimer()
            RecState.s = RecState.S.RECORDING
        } else return
        RecState.post()
        notifyRec()
    }

    private fun stopRecording() {
        when (RecState.s) {
            RecState.S.COUNTDOWN -> { cancelled = true; return }
            RecState.S.RECORDING, RecState.S.PAUSED -> {}
            else -> return
        }
        if (RecState.s == RecState.S.RECORDING) RecState.pauseTimer()
        RecState.s = RecState.S.SAVING
        RecState.post()
        notifyRec()
        main.removeCallbacks(storageCheck)
        lockReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) { } }
        lockReceiver = null
        Thread({ finalizeRecording() }, "rec-finish").start()
    }

    private fun finalizeRecording() {
        val eng = engine
        val out = output
        val ok = try { eng?.stop() ?: false } catch (e: Throwable) { false }
        try { out?.finish(ok) } catch (_: Exception) { }
        try { projection?.stop() } catch (_: Exception) { }
        main.post {
            if (ok && out != null) {
                RecState.lastUri = out.uri
                RecState.lastName = out.name
                RecState.lastVideo = out.video
                showSaved(out)
            } else {
                Ui.toast(this, "Nothing was recorded")
            }
            cleanup()
        }
    }

    private fun abort(msg: String) {
        Ui.toast(this, msg)
        cleanup()
    }

    private fun cleanup() {
        engine = null
        output = null
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        RecState.s = RecState.S.IDLE
        RecState.post()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(storageCheck)
        lockReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) { } }
        super.onDestroy()
    }

    // ------------------------------------------------------------ notifications

    private fun svc(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, RecorderService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    @Suppress("DEPRECATION")
    private fun recNotification(): Notification {
        val s = RecState.s
        val what = listOfNotNull(
            if (srcScreen) "screen" else null, if (srcSys) "system audio" else null, if (srcMic) "mic" else null
        ).joinToString(" + ")
        val title = when (s) {
            RecState.S.COUNTDOWN -> "Starting in ${RecState.countdown}..."
            RecState.S.PAUSED -> "Paused"
            RecState.S.SAVING -> "Saving..."
            else -> "Recording"
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CH_REC)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(what)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (s == RecState.S.RECORDING) {
            b.setShowWhen(true).setUsesChronometer(true).setWhen(System.currentTimeMillis() - RecState.elapsed())
        } else {
            b.setShowWhen(false)
        }
        if (s == RecState.S.RECORDING) b.addAction(Notification.Action.Builder(0, "Pause", svc(ACTION_PAUSE, 1)).build())
        if (s == RecState.S.PAUSED) b.addAction(Notification.Action.Builder(0, "Resume", svc(ACTION_RESUME, 2)).build())
        if (s != RecState.S.SAVING) b.addAction(Notification.Action.Builder(0, "Stop", svc(ACTION_STOP, 3)).build())
        return b.build()
    }

    private fun notifyRec() {
        getSystemService(NotificationManager::class.java).notify(ID_REC, recNotification())
    }

    @Suppress("DEPRECATION")
    private fun showSaved(out: Output) {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val play = PendingIntent.getActivity(this, 10, PlayerActivity.intent(this, out.uri, out.video, out.name), flags)
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (out.video) "video/mp4" else "audio/mp4")
            .putExtra(Intent.EXTRA_STREAM, out.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val share = PendingIntent.getActivity(this, 11, Intent.createChooser(send, "Share"), flags)
        val n = Notification.Builder(this, CH_SAVED)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Recording saved")
            .setContentText(out.name)
            .setContentIntent(play)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(0, "Watch", play).build())
            .addAction(Notification.Action.Builder(0, "Share", share).build())
            .build()
        getSystemService(NotificationManager::class.java).notify(ID_SAVED, n)
    }
}
