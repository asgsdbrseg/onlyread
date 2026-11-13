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
import android.os.SystemClock
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
 * 封面加载采用「LruCache + 固定线程池 + 在途任务去重 + 可见项路径标签回贴」。
 * 0.4.28：修复导入后封面不立即刷新——旧实现把异步解码结果只贴回「发起请求的那个
 * ImageView」，而 GridView 在 notifyDataSetChanged 后的布局里可能对同一条目多次
 * getView（convertView 为 null 时是不同的 View 实例），且 inFlight 去重使后续实例
 * 不会各自提交任务；一旦那个 target 已被复用（tag 变了）或已脱离视图树，标签校验
 * 失败便永久停在占位色，直到下次滑动 / 旋转 / 切后台触发重绘才显示。现在解码完成后
 * 遍历当前可见子项，凡此刻正显示该封面路径的格子都立即更新，并保留一次
 * notifyDataSetChanged 兜底；同时导入返回后由 onActivityResult 主动刷新书架。
 *
 * 0.4.33：Manifest 声明 singleTask，书架在应用 task 中全局唯一。任意来源再次启动
 * MainActivity 都由系统复用本实例并回调 onNewIntent，不再在 onCreate 里转发 intent
 * 或 finish 自己——旧转发方案在应用位于后台时会让新实例落进调用者 task，既可能把
 * CLEAR_TOP/SINGLE_TOP 命中未初始化的自己而崩溃，又会在 finish 后无书架可返回。
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_BROWSER = 4201
    }

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "onlyread-shelf-io").apply { isDaemon = true }
    }
    private val items = ArrayList<BookEntity>()
    private lateinit var adapter: BookAdapter
    private lateinit var grid: GridView
    private lateinit var permCard: View
    private lateinit var emptyText: TextView

    /** 导入返回时 onActivityResult 已立即刷新，跳过紧随其后的 onResume 重复查询。 */
    private var suppressResumeReload = false

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
        grid = findViewById(R.id.grid)
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
        // 吞掉所有点击导致 OnItemClickListener 永不触发
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
        // 防御：Activity 正在结束时不触碰任何视图字段（singleTask 下正常路径不会走到）
        if (isFinishing) return
        permCard.visibility = if (hasStoragePermission()) View.GONE else View.VISIBLE
        // 导入返回时已由 onActivityResult 立即刷新，跳过这次重复查询；
        // 其余情况（从阅读器返回、授权返回、首次启动）照常刷新最近在读顺序。
        if (suppressResumeReload) {
            suppressResumeReload = false
        } else {
            reload()
        }
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

    @Suppress("DEPRECATION")
    private fun openBrowser() {
        if (!hasStoragePermission()) {
            requestManagePermission()
            return
        }
        startActivityForResult(Intent(this, FileBrowserActivity::class.java), REQ_BROWSER)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 导入结束（新导入成功，或重复导入后返回）立即刷新书架，
        // 不再单纯依赖 onResume 的间接时序；封面解码完成后也会即时回贴可见项。
        if (requestCode == REQ_BROWSER && resultCode == RESULT_OK) {
            suppressResumeReload = true
            reload()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTask：任意来源再次启动 MainActivity 都由系统复用本实例并走到这里。
        // handleExternalEpub 内部只认 ACTION_VIEW，桌面 MAIN/LAUNCHER 恢复不会触发导入。
        if (isFinishing || isDestroyed) return
        handleExternalEpub(intent)
    }

    /** 防同一 Intent 被系统重复投递；带时间窗，允许用户稍后再次打开同一本书。 */
    private var lastExternalUri: String? = null
    private var lastExternalAt = 0L

    /**
     * 外部文件夹「打开方式 / 用…打开」入口：严格解析真实路径后登记到书架，成功后直接打开阅读。
     * 解析不到真实路径（Android/data 等分区存储目录、第三方 provider / 云盘）时**不复制文件**，
     * 直接提示用户改用本机文件。
     */
    private fun handleExternalEpub(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val key = uri.toString()
        val now = SystemClock.elapsedRealtime()
        if (key == lastExternalUri && now - lastExternalAt < 800L) return // 同一 Intent 重复投递
        lastExternalUri = key
        lastExternalAt = now
        // 兜底 filter 会放行 octet-stream：先按 MIME / 文件名过滤，别把任意文件登记进书架
        if (!BookImporter.isEpubUri(this, uri)) {
            Toast.makeText(this, R.string.import_not_epub, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.tip_importing, Toast.LENGTH_SHORT).show()
        onIo {
            val outcome = runCatching { BookImporter.importUri(this@MainActivity, uri) }
            ui.post {
                if (isDestroyed || isFinishing) return@post
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
                if (path != null) loadCover(path)
            }
            return v
        }
    }

    private fun loadCover(path: String) {
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
                // 0.4.28：不再只回贴「发起请求的 target」。GridView 布局阶段可能多次
                // getView 并给出不同 View 实例，inFlight 去重又让后续实例不再提交任务；
                // 只贴 target 时，一旦它被复用（tag 改变）或已脱离视图树，就永远停在
                // 占位色。改为遍历当前可见子项：凡此刻正显示该封面路径的格子都立即更新。
                var applied = false
                for (i in 0 until grid.childCount) {
                    val item = grid.getChildAt(i) ?: continue
                    val iv = item.findViewById<ImageView>(R.id.cover) ?: continue
                    if (iv.getTag(R.id.cover_path_tag) == path) {
                        iv.setImageBitmap(bmp)
                        applied = true
                    }
                }
                // 兜底：解码完成时条目尚未完成布局（childCount=0）等极端时序，
                // 触发一次 getView 以命中刚写入的缓存。
                if (!applied) adapter.notifyDataSetChanged()
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
