package com.literec

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class RecTile : TileService() {
    private val listener = Runnable { update() }

    override fun onStartListening() {
        RecState.listeners.add(listener)
        update()
    }

    override fun onStopListening() {
        RecState.listeners.remove(listener)
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        when (RecState.s) {
            RecState.S.COUNTDOWN, RecState.S.RECORDING, RecState.S.PAUSED ->
                startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
            RecState.S.IDLE -> {
                val i = Intent(this, QuickStartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(
                        PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    )
                } else {
                    startActivityAndCollapse(i)
                }
            }
            else -> {}
        }
    }

    private fun update() {
        val t = qsTile ?: return
        val active = RecState.s == RecState.S.RECORDING || RecState.s == RecState.S.PAUSED || RecState.s == RecState.S.COUNTDOWN
        t.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = if (active) "Stop recording" else "Record"
        t.subtitle = when (RecState.s) {
            RecState.S.RECORDING -> Ui.fmtTime(RecState.elapsed())
            RecState.S.PAUSED -> "Paused"
            RecState.S.SAVING -> "Saving..."
            else -> null
        }
        t.updateTile()
    }
}
