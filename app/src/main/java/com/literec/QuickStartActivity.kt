package com.literec

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Invisible activity that lets the Quick Settings tile run the permission flow with the last-used sources. */
class QuickStartActivity : Activity() {
    private lateinit var flow: StartFlow

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs(this)
        flow = StartFlow(this, p) { finish() }
        if (savedInstanceState == null) flow.begin(p.screen, p.sys, p.mic)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!flow.onActivityResult(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (!flow.onPermissionsResult(requestCode)) super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
