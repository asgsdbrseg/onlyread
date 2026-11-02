package com.onlyread.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.onlyread.app.browser.FileBrowserActivity
import com.onlyread.app.data.BookEntity
import com.onlyread.app.reader.BookActivity

/**
 * 书架页：最近在读网格 + 权限引导 + 按需导入入口（FR-01）。
 * 纯框架实现：GridView + BaseAdapter + Handler 线程模型。
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val items = ArrayList<BookEntity>()
    private lateinit var adapter: BookAdapter
    private lateinit var permCard: View
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permCard = findViewById(R.id.permCard)
        emptyText = findViewById(R.id.emptyText)
        findViewById<View>(R.id.permBtn).setOnClickListener { requestManagePermission() }
        findViewById<View>(R.id.importBtn).setOnClickListener { openBrowser() }

        adapter = BookAdapter()
        val grid = findViewById<GridView>(R.id.grid)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ ->
            val b = items.getOrNull(pos) ?: return@setOnItemClickListener
            startActivity(
                Intent(this, BookActivity::class.java)
                    .putExtra(BookActivity.EXTRA_BOOK_ID, b.id)
            )
        }
        // 长按删除必须挂在 GridView 的条目长按监听上：
        // 若在 item 根视图 setOnLongClickListener 会使条目变为可消费触摸，
        // 吞掉所有点击导致 OnItemClickListener 永不触发（0.2.1 修复的关键 bug）
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            val b = items.getOrNull(pos)
            if (b != null) {
                deleteBook(b)
                true
            } else {
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permCard.visibility = if (hasStoragePermission()) View.GONE else View.VISIBLE
        reload()
    }

    private fun hasStoragePermission(): Boolean = Environment.isExternalStorageManager()

    private fun reload() {
        Thread {
            val list = (application as App).db.getAll()
            ui.post {
                items.clear()
                items.addAll(list)
                adapter.notifyDataSetChanged()
                emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun requestManagePermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.perm_fallback, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openBrowser() {
        if (!hasStoragePermission()) {
            requestManagePermission()
            return
        }
        startActivity(Intent(this, FileBrowserActivity::class.java))
    }

    private fun deleteBook(b: BookEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(R.string.dialog_delete_msg)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                Thread {
                    (application as App).db.deleteById(b.id)
                    // 删除后立即刷新书架（修复：此前要等 onResume 才消失）
                    ui.post {
                        items.remove(b)
                        adapter.notifyDataSetChanged()
                        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    }
                }.start()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private inner class BookAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView
                ?: LayoutInflater.from(this@MainActivity)
                    .inflate(R.layout.item_book, parent, false)
            val b = items[position]
            val cover = v.findViewById<ImageView>(R.id.cover)
            v.findViewById<TextView>(R.id.title).text = b.title
            v.findViewById<TextView>(R.id.author).text = b.author ?: ""
            cover.setImageDrawable(null)
            b.coverPath?.let { p ->
                Thread {
                    val bmp = decodeSampled(p, 300, 400)
                    if (bmp != null) ui.post { cover.setImageBitmap(bmp) }
                }.start()
            }
            return v
        }
    }

    private fun decodeSampled(path: String, reqW: Int, reqH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqW || bounds.outHeight / (sample * 2) >= reqH) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, opts)
    }
}
