package com.literec

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A destination file for the muxer. Call [finish] exactly once when the muxer is done. */
class Output(
    val pfd: ParcelFileDescriptor,
    val uri: Uri,
    val name: String,
    val video: Boolean,
    val pathLabel: String,
    val fellBack: Boolean,
    private val done: (Boolean) -> Unit
) {
    fun finish(ok: Boolean) {
        try { pfd.close() } catch (_: Exception) { }
        done(ok)
    }
}

object Outputs {

    fun fileName(p: Prefs, video: Boolean): String {
        val d = Date()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)
        val time = SimpleDateFormat("HH-mm-ss", Locale.US).format(d)
        var s = p.pattern.replace("{date}", date).replace("{time}", time)
            .replace("{type}", if (video) "screen" else "audio")
        s = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim()
        return if (s.isEmpty()) "Rec_$date" else s
    }

    fun create(ctx: Context, p: Prefs, video: Boolean): Output =
        createNamed(ctx, p, video, fileName(p, video), p.tree.isNotEmpty())

    /** [useTree] = put the file in the custom folder (if one is set), otherwise in the default folders. */
    fun createNamed(ctx: Context, p: Prefs, video: Boolean, base: String, useTree: Boolean): Output {
        var fell = false
        if (useTree && p.tree.isNotEmpty()) {
            try {
                return inTree(ctx, Uri.parse(p.tree), base, video)
            } catch (e: Exception) {
                fell = true
            }
        }
        return inStore(ctx, base, video, fell)
    }

    private fun inStore(ctx: Context, base: String, video: Boolean, fell: Boolean): Output {
        val res = ctx.contentResolver
        val rel = if (video) Folders.VIDEO_REL else Folders.AUDIO_REL
        val ext = if (video) "mp4" else "m4a"
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$base.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val coll = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val uri = res.insert(coll, cv) ?: throw IOException("Could not create file")
        val pfd = res.openFileDescriptor(uri, "rw") ?: throw IOException("Could not open file")
        return Output(pfd, uri, "$base.$ext", video, rel, fell) { ok ->
            try {
                if (ok) {
                    res.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } else {
                    res.delete(uri, null, null)
                }
            } catch (_: Exception) { }
        }
    }

    private fun seekable(pfd: ParcelFileDescriptor): Boolean = try {
        Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_CUR)
        true
    } catch (e: Exception) { false }

    private fun inTree(ctx: Context, tree: Uri, base: String, video: Boolean): Output {
        val res = ctx.contentResolver
        val ext = if (video) "mp4" else "m4a"
        val mime = if (video) "video/mp4" else "audio/mp4"
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(res, parent, mime, "$base.$ext")
            ?: throw IOException("Could not create file in folder")
        val label = Folders.pretty(tree)
        val direct = res.openFileDescriptor(doc, "rw")
        if (direct == null) {
            try { DocumentsContract.deleteDocument(res, doc) } catch (_: Exception) { }
            throw IOException("Could not open file in folder")
        }
        if (seekable(direct)) {
            return Output(direct, doc, "$base.$ext", video, label, false) { ok ->
                if (!ok) try { DocumentsContract.deleteDocument(res, doc) } catch (_: Exception) { }
            }
        }
        // Provider can't seek (some SD cards / cloud-backed folders): record locally, copy at the end.
        try { direct.close() } catch (_: Exception) { }
        val tmp = File(ctx.cacheDir, "rec_tmp.$ext")
        val pfd = ParcelFileDescriptor.open(
            tmp,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        )
        return Output(pfd, doc, "$base.$ext", video, label, false) { ok ->
            try {
                if (ok) {
                    res.openOutputStream(doc, "wt")!!.use { o -> tmp.inputStream().use { it.copyTo(o, 64 * 1024) } }
                } else {
                    DocumentsContract.deleteDocument(res, doc)
                }
            } catch (_: Exception) {
            } finally {
                tmp.delete()
            }
        }
    }
}
