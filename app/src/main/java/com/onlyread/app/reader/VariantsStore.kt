package com.onlyread.app.reader

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 异体字表的外部文件存取（Android 侧）。
 *
 * 用户可编辑文件：`<外部存储根>/onlyread/variants.json`
 * （典型路径 `/storage/emulated/0/onlyread/variants.json`）。
 * 应用已申请 MANAGE_EXTERNAL_STORAGE，可直接读取。
 *
 * 原则：**原样读取，不做额外修改或补充**——
 * - 文件存在时按文件内容原样解析、原样返回，不增补/去重/替换任何条目；
 * - 读不到或 JSON 非法时返回空表并提示（按原词搜索），不擅自换成另一份数据；
 * - [ensureExternal] 只负责首次把模板文件放到外部（已存在则绝不覆盖、不改写用户的修改）。
 *
 * 读取时机：**仅在应用启动时读一次**（App.onCreate 依次调用 [ensureExternal]、
 * [loadOnStartup]），结果常驻进程内存；搜索通过 [current] 取缓存，绝不读盘。
 * 用户修改文件后需完全退出并重新打开 App 才会生效。
 */
object VariantsStore {

    private const val DIR_NAME = "onlyread"
    private const val FILE_NAME = "variants.json"
    private const val ASSET_NAME = "variants.json"

    /** 外部可编辑文件。 */
    fun externalFile(): File =
        File(File(Environment.getExternalStorageDirectory(), DIR_NAME), FILE_NAME)

    /** 供 UI 展示/排障用的绝对路径。 */
    fun externalPath(): String = externalFile().absolutePath

    /** 启动时读取/解析的错误说明；成功为 null。进程内不再变化。 */
    @Volatile
    var lastError: String? = null
        private set

    /** 启动时读入的异体表，进程内只读缓存。 */
    @Volatile
    private var cache: Map<String, List<String>> = emptyMap()

    /** 搜索取用的当前异体表：直接返回内存缓存，不做任何 I/O。 */
    fun current(): Map<String, List<String>> = cache

    /** assets 里与仓库根目录 variants.json 完全一致的模板内容。 */
    private fun templateJson(context: Context): String = try {
        context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (_: Exception) {
        "{}"
    }

    /** 首次启动把模板文件放到外部文件夹（已存在则绝不覆盖用户的修改）。 */
    fun ensureExternal(context: Context) {
        val f = externalFile()
        if (f.isFile) return
        try {
            f.parentFile?.mkdirs()
            f.writeText(templateJson(context), Charsets.UTF_8)
        } catch (_: Exception) {
            // 无写权限时静默：后续 loadOnStartup 使用 assets 模板内容
        }
    }

    /**
     * 应用启动时调用一次：读盘并填充 [cache] 与 [lastError]。
     * - 外部文件存在 → 原样读取、原样解析后缓存；
     * - 外部文件不存在 → 使用 assets 内的同一份模板内容（仅此一种初始化情形）；
     * - 读取失败 / JSON 非法 → 缓存空表并写入 [lastError]，不补充、不替换配置数据。
     *
     * 之后搜索一律走 [current]，因此本方法返回后直到进程结束都不会再读盘。
     */
    fun loadOnStartup(context: Context) {
        lastError = null
        val f = externalFile()
        val json = try {
            if (f.isFile) f.readText(Charsets.UTF_8) else templateJson(context)
        } catch (e: Exception) {
            lastError = "variants.json 读取失败，将按原词搜索：${e.message}"
            cache = emptyMap()
            return
        }
        cache = try {
            Variants.parse(json)
        } catch (e: Exception) {
            lastError = "variants.json 格式错误，将按原词搜索：${e.message}"
            emptyMap()
        }
    }
}
