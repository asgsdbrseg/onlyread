package com.onlyread.app.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.onlyread.app.App
import com.onlyread.app.R
import com.onlyread.app.data.BookEntity
import com.onlyread.app.epub.EpubArchive
import com.onlyread.app.util.Hashing
import com.onlyread.app.util.UriPath
import java.io.File

/**
 * 应用内轻量文件浏览器（FR-01 按需导入）：
 * 仅展示目录与 .epub 文件；点击 EPUB 即解析元数据/封面并登记绝对路径。
 */
class FileBrowserActivity : Activity() {

    private data class Row(val file: File, val isDir: Boolean)

    private val ui = Handler(Looper.getMainLooper())
    private val rows = ArrayList<Row>()
    private lateinit var adapter: FileAdapter
    private lateinit var tvPath: TextView
    private lateinit var emptyText: TextView
    private val PICK_EPUB = 4202 // EPUB 选择请求码

    @Suppress("DEPRECATION")
    private var currentDir: File = Environment.getExternalStorageDirectory()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Environment.isExternalStorageManager()) {
            Toast.makeText(this, R.string.tip_no_permission, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(R.layout.activity_file_browser)
        tvPath = findViewById(R.id.tvPath)
        emptyText = findViewById(R.id.tvEmpty)
        findViewById<View>(R.id.btnBack).setOnClickListener {
            if (!navigateTo(currentDir.parentFile)) finish()
        }
        // M2.2：系统文件选择器导入（免手动浏览目录）
        findViewById<View>(R.id.btnPick).setOnClickListener { pickEpub() }
        adapter = FileAdapter()
        findViewById<ListView>(R.id.list).adapter = adapter
        savedInstanceState?.getString("dir")?.let { d ->
            val f = File(d)
            if (f.isDirectory) currentDir = f
        }
        navigateTo(currentDir)
    }

    /** M2.2：SAF 选择 EPUB → 拷贝到私有书库（同名覆盖，路径稳定以支持去重） */
    private fun pickEpub() {
        try {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/epub+zip", "application/zip"))
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(i, getString(R.string.import_pick)), PICK_EPUB)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.pick_font_fail, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_EPUB || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        // M2.2：Reasily 式直接引用原文件（不复制到私有目录）——解析真实路径后登记
        val path = UriPath.resolve(contentResolver, uri)
        if (path == null || !File(path).isFile) {
            Toast.makeText(this, R.string.import_need_local, Toast.LENGTH_LONG).show()
            return
        }
        importEpub(File(path))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("dir", currentDir.absolutePath)
    }

    private fun navigateTo(dir: File?): Boolean {
        if (dir == null || !dir.isDirectory || !dir.canRead()) return false
        currentDir = dir
        tvPath.text = dir.absolutePath
        val files = dir.listFiles()
        val list = ArrayList<Row>()
        if (files != null) {
            files.filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { it.name.lowercase() }
                .forEach { list.add(Row(it, true)) }
            files.filter { it.isFile && !it.name.startsWith(".") && it.name.lowercase().endsWith(".epub") }
                .sortedBy { it.name.lowercase() }
                .forEach { list.add(Row(it, false)) }
        }
        rows.clear()
        rows.addAll(list)
        adapter.notifyDataSetChanged()
        emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        return true
    }

    private fun importEpub(file: File) {
        Toast.makeText(this, R.string.tip_importing, Toast.LENGTH_SHORT).show()
        Thread {
            val msg = try {
                doImport(file)
            } catch (e: Exception) {
                "导入失败：${e.message}"
            }
            ui.post {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (msg.startsWith("已导入")) finish()
            }
        }.start()
    }

    private fun doImport(file: File): String {
        val db = (application as App).db
        if (db.countByPath(file.absolutePath) > 0) return "该书已在书架中"
        EpubArchive(file).use { archive ->
            val book = archive.parseBook() ?: return "EPUB 解析失败：未找到有效的 OPF"
            val entity = BookEntity(
                id = 0,
                path = file.absolutePath,
                size = file.length(),
                mtimeMs = file.lastModified(),
                identityHash = Hashing.shortHash(file),
                title = book.title.ifBlank { file.nameWithoutExtension },
                author = book.author,
                language = book.language,
                publisher = book.publisher,
                coverPath = null,
                spineCount = book.spine.size,
                addedAt = System.currentTimeMillis(),
                lastReadAt = 0L
            )
            val id = db.insert(entity)
            // M1：目录缓存（FR-06）
            try {
                db.replaceToc(
                    id,
                    book.toc.mapIndexed { i, t ->
                        com.onlyread.app.data.TocRow(i, t.spineIndex, t.title, t.level, t.frag ?: "")
                    }
                )
            } catch (_: Exception) {
            }
            val coverHref = book.coverPath
            if (coverHref != null) {
                val bytes = archive.entryBytes(coverHref)
                if (bytes != null) {
                    val dir = File(filesDir, "covers").apply { mkdirs() }
                    val coverFile = File(dir, "cover_$id")
                    coverFile.writeBytes(bytes)
                    db.updateCoverPath(id, coverFile.absolutePath)
                }
            }
            return "已导入：${entity.title}"
        }
    }

    private inner class FileAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView
                ?: LayoutInflater.from(this@FileBrowserActivity)
                    .inflate(R.layout.item_file, parent, false)
            val row = rows[position]
            v.findViewById<ImageView>(R.id.icon)
                .setImageResource(if (row.isDir) R.drawable.ic_folder else R.drawable.ic_file)
            v.findViewById<TextView>(R.id.name).text = row.file.name
            v.setOnClickListener {
                if (row.isDir) navigateTo(row.file) else importEpub(row.file)
            }
            return v
        }
    }
}
