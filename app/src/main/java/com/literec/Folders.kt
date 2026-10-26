package com.literec

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

object Folders {
    const val APP = "LiteRec"
    private const val EXT_AUTH = "com.android.externalstorage.documents"

    const val VIDEO_REL = "Movies/$APP"
    const val AUDIO_REL = "Music/$APP"

    fun defaultFolderUri(rel: String): Uri = DocumentsContract.buildDocumentUri(EXT_AUTH, "primary:$rel")

    fun treeFolderUri(tree: Uri): Uri =
        DocumentsContract.buildDocumentUri(tree.authority ?: EXT_AUTH, DocumentsContract.getTreeDocumentId(tree))

    fun pretty(tree: Uri): String {
        val id = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: Exception) { return tree.toString() }
        val i = id.indexOf(':')
        if (i < 0) return id
        val vol = id.substring(0, i)
        val path = id.substring(i + 1)
        val v = if (vol == "primary") "Internal storage" else "SD card ($vol)"
        return if (path.isEmpty()) v else "$v/$path"
    }

    /** Real filesystem path for folders on internal storage (used by some file managers). */
    @Suppress("DEPRECATION")
    fun absPath(tree: Uri): String? = try {
        val id = DocumentsContract.getTreeDocumentId(tree)
        if (id.startsWith("primary:")) Environment.getExternalStorageDirectory().path + "/" + id.substring(8) else null
    } catch (e: Exception) { null }

    /**
     * Shows a folder. File managers differ a lot, so try several routes in turn:
     * 1. the standard "view directory" intent, 2. the system Files app by package name,
     * 3. Samsung My Files, 4. the system file picker opened at that folder, 5. copy the path.
     */
    fun open(act: Activity, folder: Uri, label: String, abs: String?, browseRequest: Int) {
        val dir = DocumentsContract.Document.MIME_TYPE_DIR
        val tries = ArrayList<Intent>()
        tries.add(Intent(Intent.ACTION_VIEW).setDataAndType(folder, dir))
        for (pkg in listOf("com.google.android.documentsui", "com.android.documentsui")) {
            tries.add(Intent(Intent.ACTION_VIEW).setDataAndType(folder, dir).setPackage(pkg))
        }
        if (abs != null) {
            tries.add(
                Intent("samsung.myfiles.intent.action.LAUNCH_MY_FILES")
                    .setPackage("com.sec.android.app.myfiles")
                    .putExtra("samsung.myfiles.intent.extra.START_PATH", abs)
            )
        }
        for (i in tries) {
            try {
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                act.startActivity(i)
                return
            } catch (_: Exception) { }
        }
        try {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder)
            act.startActivityForResult(pick, browseRequest)
            Ui.toast(act, "Showing the folder in the system file picker. Tap a file to play it.")
            return
        } catch (_: Exception) { }
        try {
            act.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("path", label))
        } catch (_: Exception) { }
        Ui.toast(act, "Couldn't open the folder. Path copied: $label")
    }
}
