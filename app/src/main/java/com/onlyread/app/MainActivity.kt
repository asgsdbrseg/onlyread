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
import android.util.LruCache
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
import com.onlyread.app.data.BookImporter
import com.onlyread.app.reader.BookActivity
import com.onlyread.app.util.Logx
import java.util.Collections
import java.util.concurrent.Executors

/**
 * 书架页：最近在读网格 + 权限引导 + 按需导入入口（FR-01）。
 * 纯框架实现：GridView + BaseAdapter + Handler 线程模型。
 *
 * 0.4.22：封面加载改为「LruCache + 固定线程池 + ImageView tag 校验」。
 * 旧实现每次 getView 都 new Thread 解码、无缓存、不校验条目复用，
 * 快速滑动时既会线程泛滥，也会把 A 书的封面贴到 B 书上（错位）。
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "onlyread-shelf-io").apply { isDaemon = true }
    }
    private val items = ArrayList<BookEntity>()
    private lateinit var adapter: BookAdapter
    private lateinit var permCard: View
    private lateinit var emptyText: TextView

    /** 封面位图缓存：上限为进程最大内存的 1/8，按字节计。 */
    private val coverCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024).toInt() / 8
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    /** 同一路径只保留一个在途解码任务，避免快速滑动时重复排队。 */
    private val inFlight = Collections.synchronizedSet(HashSet<String>())

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
        // 外部文件夹点 EPUB → 「打开方式」选 onlyread。仅首次创建时处理，
        // 旋转屏幕等重建（savedInstanceState != null）不重复导入。
        if (savedInstanceState == null) handleExternalEpub(intent)
    }

    override fun onResume() {
        super.onResume()
        permCard.visibility = if (hasStoragePermission()) View.GONE else View.VISIBLE
        reload()
    }

    private fun hasStoragePermission(): Boolean = Environment.isExternalStorageManager()

    /** 统一后台入口：Activity 销毁后不再回写 UI，异常也留下日志。 */
    private fun onIo(block: () -> Unit) {
        io.execute {
            if (isDestroyed) return@execute
            try {
                block()
            } catch (e: Exception) {
                Logx.w(TAG, "书架后台任务失败", e)
            }
        }
    }

    private fun reload() {
        onIo {
            val list = (application as App).db.getAll()
            ui.post {
                if (isDestroyed) return@post
                items.clear()
                items.addAll(list)
                adapter.notifyDataSetChanged()
                emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun requestManagePermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Logx.w(TAG, "应用专属权限设置页不可用，回退全局设置页", e)
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Exception) {
                Logx.w(TAG, "全局权限设置页也不可用", e2)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Activity 已在栈顶被复用时（部分文件管理器带 NEW_TASK 复用），同样接住外部 VIEW
        handleExternalEpub(intent)
    }

    private var lastExternalUri: String? = null

    /**
     * 外部文件夹「打开方式 / 用…打开」入口：严格解析真实路径后登记到书架，成功后直接打开阅读。
     * 解析不到真实路径（第三方 provider / 云盘）时**不复制文件**，直接提示用户改用本机文件。
     */
    private fun handleExternalEpub(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val key = uri.toString()
        if (key == lastExternalUri) return // 同一 Intent 重复投递不重复导入
        lastExternalUri = key
        // 兜底 filter 会放行 octet-stream：先按 MIME / 文件名过滤，别把任意文件登记进书架
        if (!BookImporter.isEpubUri(this, uri)) {
            Toast.makeText(this, R.string.import_not_epub, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.tip_importing, Toast.LENGTH_SHORT).show()
        onIo {
            val outcome = runCatching { BookImporter.importUri(this@MainActivity, uri) }
            ui.post {
                if (isDestroyed) return@post
                outcome.fold(
                    onSuccess = { res ->
                        Toast.makeText(this, res.message, Toast.LENGTH_SHORT).show()
                        reload()
                        startActivity(
                            Intent(this, BookActivity::class.java)
                                .putExtra(BookActivity.EXTRA_BOOK_ID, res.bookId)
                        )
                    },
                    onFailure = { e ->
                        Logx.w(TAG, "外部 EPUB 导入失败", e)
                        Toast.makeText(
                            this, e.message ?: getString(R.string.open_fail), Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }
    }

    private fun deleteBook(b: BookEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(R.string.dialog_delete_msg)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                onIo {
                    (application as App).db.deleteById(b.id)
                    // 删除后立即刷新书架（修复：此前要等 onResume 才消失）
                    ui.post {
                        if (isDestroyed) return@post
                        items.remove(b)
                        adapter.notifyDataSetChanged()
                        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
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
            // 先绑定「本条目应显示的路径」，异步解码回来时用它校验，防止条目复用导致错位
            cover.setTag(R.id.cover_path_tag, b.coverPath)
            val path = b.coverPath
            val cached = if (path != null) coverCache.get(path) else null
            if (cached != null) {
                cover.setImageBitmap(cached)
            } else {
                cover.setImageDrawable(null)
                if (path != null) loadCover(path, cover)
            }
            return v
        }
    }

    private fun loadCover(path: String, target: ImageView) {
        if (!inFlight.add(path)) return
        io.execute {
            val bmp = try {
                decodeSampled(path, 300, 400)
            } catch (e: Exception) {
                Logx.w(TAG, "封面解码失败: $path", e)
                null
            }
            if (bmp != null) coverCache.put(path, bmp)
            inFlight.remove(path)
            ui.post {
                if (isDestroyed || bmp == null) return@post
                if (target.getTag(R.id.cover_path_tag) == path) target.setImageBitmap(bmp)
            }
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

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        io.shutdownNow()
        coverCache.evictAll()
    }
}
