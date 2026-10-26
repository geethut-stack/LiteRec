package com.literec

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView

class PlayerActivity : Activity() {
    companion object {
        fun intent(c: Context, uri: Uri, video: Boolean, name: String): Intent =
            Intent(c, PlayerActivity::class.java).setData(uri)
                .putExtra("video", video).putExtra("name", name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private lateinit var vv: VideoView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val uri = intent.data ?: run { finish(); return }
        val video = intent.getBooleanExtra("video", true)
        val name = intent.getStringExtra("name") ?: ""

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        vv = VideoView(this)
        root.addView(vv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        if (!video) {
            val icon = ImageView(this).apply { setImageResource(R.drawable.ic_audio) }
            val size = Ui.dp(this, 140)
            root.addView(icon, FrameLayout.LayoutParams(size, size, Gravity.CENTER).apply { bottomMargin = Ui.dp(this@PlayerActivity, 40) })
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x99000000.toInt())
        }
        Ui.applyInsets(bar, Ui.dp(this, 4))
        fun barBtn(t: String) = Ui.flat(this, t).apply { setTextColor(Color.WHITE) }
        val back = barBtn("Back")
        val title = TextView(this).apply {
            text = name; setTextColor(Color.WHITE); textSize = 17f * Ui.scale; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        val trim = barBtn("Trim")
        val share = barBtn("Share")
        val del = barBtn("Delete")
        bar.addView(back)
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(trim)
        bar.addView(share)
        bar.addView(del)
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)

        back.setOnClickListener { finish() }
        trim.setOnClickListener {
            if (::vv.isInitialized && vv.isPlaying) vv.pause()
            startActivityForResult(EditorActivity.intent(this, uri, video, name), 71)
        }
        share.setOnClickListener { Library.share(this, listOf(uri to video)) }
        del.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Delete recording?").setMessage(name)
                .setPositiveButton("Delete") { _, _ ->
                    if (Library.delete(this, uri)) finish() else Ui.toast(this, "Couldn't delete this file")
                }
                .setNegativeButton("Cancel", null).show()
        }

        val mc = MediaController(this)
        mc.setAnchorView(vv)
        vv.setMediaController(mc)
        vv.setOnPreparedListener { vv.start() }
        vv.setOnErrorListener { _, _, _ -> Ui.toast(this, "Can't play this file"); true }
        vv.setVideoURI(uri)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 71 && resultCode == RESULT_OK && data?.getBooleanExtra("deleted", false) == true) finish()
    }

    override fun onPause() {
        super.onPause()
        if (::vv.isInitialized && vv.isPlaying) vv.pause()
    }
}
