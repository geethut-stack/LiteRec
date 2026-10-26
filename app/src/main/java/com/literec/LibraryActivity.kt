package com.literec

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Paint
import android.provider.OpenableColumns
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class LibraryActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var normalBar: LinearLayout
    private lateinit var selBar: LinearLayout
    private lateinit var selCount: TextView
    private lateinit var sortBtn: Button
    private lateinit var trimBtn: Button

    private var items: List<Rec> = emptyList()
    private val selected = LinkedHashSet<String>()
    private val adapter = Adapter()
    private val bg = Executors.newSingleThreadExecutor()
    private val sortNames = listOf("Date", "Size", "Length")
    private val RC_BROWSE = 61

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Ui.applyInsets(root, Ui.dp(this, 8))

        fun flat(t: String) = Ui.flat(this, t)

        // Normal bar
        normalBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = flat("Back")
        val title = Ui.text(this, "Library", 22f, bold = true)
        sortBtn = flat("")
        normalBar.addView(back)
        normalBar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        normalBar.addView(sortBtn)

        // Selection bar
        selBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        val close = flat("Cancel")
        selCount = Ui.text(this, "", 17f, bold = true)
        val all = flat("All")
        trimBtn = flat("Trim")
        val share = flat("Share")
        val del = flat("Delete").apply { setTextColor(Ui.ACCENT) }
        selBar.addView(close)
        selBar.addView(selCount, LinearLayout.LayoutParams(0, -2, 1f))
        selBar.addView(all); selBar.addView(trimBtn); selBar.addView(share); selBar.addView(del)

        val bars = FrameLayout(this)
        bars.addView(normalBar); bars.addView(selBar)
        root.addView(bars, LinearLayout.LayoutParams(-1, -2))

        list = ListView(this).apply {
            divider = null
            adapter = this@LibraryActivity.adapter
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        empty = Ui.text(this, "No recordings yet", 18f, secondary = true).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        val frame = FrameLayout(this)
        frame.addView(list, FrameLayout.LayoutParams(-1, -1))
        frame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        back.setOnClickListener { finish() }
        sortBtn.setOnClickListener {
            prefs.sortKey = (prefs.sortKey + 1) % sortNames.size
            items = sorted(items)
            adapter.notifyDataSetChanged()
            updateBars()
        }
        close.setOnClickListener { selected.clear(); updateBars(); adapter.notifyDataSetChanged() }
        all.setOnClickListener { items.forEach { selected.add(it.uri.toString()) }; updateBars(); adapter.notifyDataSetChanged() }
        trimBtn.setOnClickListener {
            val r = picked().firstOrNull() ?: return@setOnClickListener
            selected.clear(); updateBars(); adapter.notifyDataSetChanged()
            startActivity(EditorActivity.intent(this, r.uri, r.video, r.name))
        }
        share.setOnClickListener { Library.share(this, picked().map { it.uri to it.video }) }
        del.setOnClickListener { confirmDelete() }

        list.setOnItemClickListener { _, _, pos, _ ->
            val r = items[pos]
            if (selected.isNotEmpty()) toggle(r)
            else startActivity(PlayerActivity.intent(this, r.uri, r.video, r.name))
        }
        list.setOnItemLongClickListener { _, _, pos, _ -> toggle(items[pos]); true }
        updateBars()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_BROWSE || resultCode != RESULT_OK) return
        val u = data?.data ?: return
        val mime = contentResolver.getType(u) ?: ""
        if (!mime.startsWith("video/") && !mime.startsWith("audio/")) { Ui.toast(this, "That isn't a recording"); return }
        var name = u.lastPathSegment ?: "recording"
        try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
        } catch (_: Exception) { }
        startActivity(PlayerActivity.intent(this, u, mime.startsWith("video/"), name))
    }

    override fun onBackPressed() {
        if (selected.isNotEmpty()) { selected.clear(); updateBars(); adapter.notifyDataSetChanged() } else super.onBackPressed()
    }

    private fun sorted(l: List<Rec>): List<Rec> = when (prefs.sortKey) {
        1 -> l.sortedByDescending { it.size }
        2 -> l.sortedByDescending { it.dur }
        else -> l.sortedByDescending { it.date }
    }

    private fun reload() {
        bg.execute {
            val l = sorted(Library.load(this, prefs))
            runOnUiThread {
                items = l
                val keys = l.map { it.uri.toString() }.toSet()
                selected.retainAll(keys)
                empty.visibility = if (l.isEmpty()) View.VISIBLE else View.GONE
                adapter.notifyDataSetChanged()
                updateBars()
            }
        }
    }

    private fun picked(): List<Rec> = items.filter { selected.contains(it.uri.toString()) }

    private fun toggle(r: Rec) {
        val k = r.uri.toString()
        if (!selected.remove(k)) selected.add(k)
        updateBars()
        adapter.notifyDataSetChanged()
    }

    private fun updateBars() {
        val sel = selected.isNotEmpty()
        normalBar.visibility = if (sel) View.GONE else View.VISIBLE
        selBar.visibility = if (sel) View.VISIBLE else View.GONE
        selCount.text = "${selected.size} selected"
        trimBtn.visibility = if (selected.size == 1) View.VISIBLE else View.GONE
        sortBtn.text = "Sort: ${sortNames[prefs.sortKey]}"
    }

    private fun confirmDelete() {
        val n = selected.size
        AlertDialog.Builder(this).setTitle("Delete $n recording${if (n == 1) "" else "s"}?")
            .setMessage("This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                val targets = picked()
                bg.execute {
                    val failed = targets.count { !Library.delete(this, it.uri) }
                    runOnUiThread {
                        selected.clear()
                        if (failed > 0) Ui.toast(this, "$failed file(s) couldn't be deleted")
                        reload()
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ rows

    private inner class Holder {
        val ctx = this@LibraryActivity
        var rec: Rec? = null
        val thumb = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ui.round(ctx, 0x33888888, 10f)
            clipToOutline = true
        }
        val title = Ui.text(ctx, "", 17f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        val info = Ui.text(ctx, "", 14f, secondary = true)
        val path = Ui.text(ctx, "", 14f).apply {
            setTextColor(Ui.ACCENT)
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            maxLines = 1
            isFocusable = false
            setOnClickListener { rec?.let { r -> Folders.open(ctx, r.folder, r.path, r.abs, RC_BROWSE) } }
            setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4))
        }
        val check = CheckBox(ctx).apply { isClickable = false; isFocusable = false; visibility = View.GONE }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8))
            addView(thumb, LinearLayout.LayoutParams(Ui.sz(ctx, 84), Ui.sz(ctx, 60)))
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 8), 0)
                addView(title); addView(info); addView(path)
            }
            addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            addView(check)
        }

        fun bind(r: Rec, sel: Boolean, anySelected: Boolean) {
            rec = r
            title.text = r.name
            val d = if (r.dur > 0) Ui.fmtTime(r.dur) else "--:--"
            val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.date))
            info.text = "$d  -  ${Ui.fmtSize(r.size)}  -  $date"
            path.text = r.path
            check.visibility = if (anySelected) View.VISIBLE else View.GONE
            check.isChecked = sel
            if (r.video) {
                thumb.setPadding(0, 0, 0, 0)
                Thumbs.load(ctx, r.uri, thumb)
            } else {
                thumb.tag = null
                thumb.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 12), Ui.dp(ctx, 16), Ui.dp(ctx, 12))
                thumb.setImageResource(R.drawable.ic_audio)
            }
            if (r.dur < 0) Library.fillDuration(ctx, r) { adapter.notifyDataSetChanged() }
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder: Holder
            val view: View
            if (convertView == null) {
                holder = Holder(); view = holder.root; view.tag = holder
            } else {
                view = convertView; holder = convertView.tag as Holder
            }
            val r = items[position]
            holder.bind(r, selected.contains(r.uri.toString()), selected.isNotEmpty())
            return view
        }
    }
}
