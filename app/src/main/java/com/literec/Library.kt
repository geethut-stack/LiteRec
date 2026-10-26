package com.literec

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.Executors

class Rec(
    val uri: Uri, val name: String, val video: Boolean, val size: Long,
    @Volatile var dur: Long, val date: Long, val path: String, val folder: Uri, val abs: String?
)

object Library {
    private val main = Handler(Looper.getMainLooper())
    private val durEx = Executors.newSingleThreadExecutor()
    private val inflight = HashSet<String>()

    fun load(ctx: Context, p: Prefs): List<Rec> {
        val all = ArrayList<Rec>()
        all += store(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, Folders.VIDEO_REL)
        all += store(ctx, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, false, Folders.AUDIO_REL)
        if (p.tree.isNotEmpty()) all += tree(ctx, Uri.parse(p.tree))
        return all.distinctBy { it.name + "|" + it.size }
    }

    private fun store(ctx: Context, base: Uri, video: Boolean, rel: String): List<Rec> {
        val out = ArrayList<Rec>()
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.DATE_MODIFIED
        )
        try {
            ctx.contentResolver.query(base, cols, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$rel/%"), null)?.use { c ->
                val folder = Folders.defaultFolderUri(rel)
                @Suppress("DEPRECATION") val abs = Environment.getExternalStorageDirectory().path + "/" + rel
                while (c.moveToNext()) {
                    out.add(Rec(ContentUris.withAppendedId(base, c.getLong(0)), c.getString(1) ?: "?", video,
                        c.getLong(2), c.getLong(3), c.getLong(4) * 1000, rel, folder, abs))
                }
            }
        } catch (_: Exception) { }
        return out
    }

    private fun tree(ctx: Context, tree: Uri): List<Rec> {
        val out = ArrayList<Rec>()
        try {
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            val label = Folders.pretty(tree)
            val folder = Folders.treeFolderUri(tree)
            ctx.contentResolver.query(kids, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(2) ?: continue
                    val video = mime.startsWith("video/")
                    if (!video && !mime.startsWith("audio/")) continue
                    out.add(Rec(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), c.getString(1) ?: "?",
                        video, c.getLong(3), -1L, c.getLong(4), label, folder, Folders.absPath(tree)))
                }
            }
        } catch (_: Exception) { }
        return out
    }

    /** Computes a duration in the background for providers that don't supply one. */
    fun fillDuration(ctx: Context, r: Rec, done: () -> Unit) {
        val key = r.uri.toString()
        if (!inflight.add(key)) return
        durEx.execute {
            var d = 0L
            try {
                val m = MediaMetadataRetriever()
                m.setDataSource(ctx, r.uri)
                d = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                m.release()
            } catch (_: Exception) { }
            r.dur = d
            main.post { done() }
        }
    }

    fun delete(ctx: Context, uri: Uri): Boolean = try {
        if (uri.authority == MediaStore.AUTHORITY) ctx.contentResolver.delete(uri, null, null) > 0
        else DocumentsContract.deleteDocument(ctx.contentResolver, uri)
    } catch (e: Exception) { false }

    fun share(ctx: Context, items: List<Pair<Uri, Boolean>>) {
        if (items.isEmpty()) return
        val i = if (items.size == 1) {
            Intent(Intent.ACTION_SEND)
                .setType(if (items[0].second) "video/mp4" else "audio/mp4")
                .putExtra(Intent.EXTRA_STREAM, items[0].first)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.first }))
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val ex = Executors.newFixedThreadPool(2)

    fun load(ctx: Context, uri: Uri, iv: ImageView) {
        val key = uri.toString()
        iv.tag = key
        val hit = cache.get(key)
        if (hit != null) { iv.setImageBitmap(hit); return }
        iv.setImageDrawable(null)
        ex.execute {
            val bmp = decode(ctx, uri)
            if (bmp != null) {
                cache.put(key, bmp)
                iv.post { if (iv.tag == key) iv.setImageBitmap(bmp) }
            }
        }
    }

    private fun decode(ctx: Context, uri: Uri): Bitmap? {
        try { return ctx.contentResolver.loadThumbnail(uri, Size(240, 240), null) } catch (_: Exception) { }
        return try {
            val m = MediaMetadataRetriever()
            m.setDataSource(ctx, uri)
            val b = m.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 240)
            m.release()
            b
        } catch (_: Exception) { null }
    }
}
