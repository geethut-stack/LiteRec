package com.literec

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build

/**
 * Runs the permission steps for a recording: mic permission, notification permission, then the
 * system screen-capture consent (needed for screen and/or system audio), then starts the service.
 */
class StartFlow(private val act: Activity, private val prefs: Prefs, private val onFinished: (Boolean) -> Unit) {
    companion object {
        const val RC_PERM = 41
        const val RC_PROJ = 42
    }

    private var screen = false
    private var sys = false
    private var mic = false

    private fun granted(p: String) = act.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun begin(screen: Boolean, sys: Boolean, mic: Boolean) {
        if (!(screen || sys || mic)) {
            Ui.toast(act, "Select at least one source")
            onFinished(false); return
        }
        if (RecState.s != RecState.S.IDLE) { onFinished(false); return }
        this.screen = screen; this.sys = sys; this.mic = mic
        prefs.screen = screen; prefs.sys = sys; prefs.mic = mic

        val need = ArrayList<String>()
        if ((sys || mic) && !granted(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isEmpty()) afterPermissions() else act.requestPermissions(need.toTypedArray(), RC_PERM)
    }

    fun onPermissionsResult(code: Int): Boolean {
        if (code != RC_PERM) return false
        if ((sys || mic) && !granted(Manifest.permission.RECORD_AUDIO)) {
            Ui.toast(act, "Microphone/audio permission is required for audio recording. You can allow it in system Settings.")
            onFinished(false)
        } else afterPermissions()
        return true
    }

    private fun afterPermissions() {
        if (screen || sys) {
            val mgr = act.getSystemService(MediaProjectionManager::class.java)
            val intent = if (Build.VERSION.SDK_INT >= 34 && prefs.fullOnly)
                mgr.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else mgr.createScreenCaptureIntent()
            act.startActivityForResult(intent, RC_PROJ)
        } else launch(0, null)
    }

    fun onActivityResult(req: Int, res: Int, data: Intent?): Boolean {
        if (req != RC_PROJ) return false
        if (res == Activity.RESULT_OK && data != null) launch(res, data)
        else { Ui.toast(act, "Screen capture was not allowed"); onFinished(false) }
        return true
    }

    private fun launch(code: Int, data: Intent?) {
        val i = Intent(act, RecorderService::class.java)
            .setAction(RecorderService.ACTION_START)
            .putExtra(RecorderService.X_SCREEN, screen)
            .putExtra(RecorderService.X_SYS, sys)
            .putExtra(RecorderService.X_MIC, mic)
            .putExtra(RecorderService.X_CODE, code)
        if (data != null) i.putExtra(RecorderService.X_DATA, data)
        act.startForegroundService(i)
        onFinished(true)
    }
}
