package com.onlyread.app.reader

import android.content.Context
import android.os.Environment
import com.onlyread.app.util.Logx
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

    private const val TAG = "VariantsStore"

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

    /** 取出并清除启动时的错误/回退提示（进程内只提示一次）。 */
    fun consumeError(): String? {
        val e = lastError
        lastError = null
        return e
    }

    /** assets 里与仓库根目录 variants.json 完全一致的模板内容。 */
    private fun templateJson(context: Context): String = try {
        context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: Exception) {
        Logx.w(TAG, "读取内置异体字模板失败", e)
        "{}"
    }

    /** 首次启动把模板文件放到外部文件夹（已存在则绝不覆盖用户的修改）。 */
    fun ensureExternal(context: Context) {
        val f = externalFile()
        if (f.isFile) return
        try {
            f.parentFile?.mkdirs()
            f.writeText(templateJson(context), Charsets.UTF_8)
        } catch (e: Exception) {
            // 无写权限时不阻断启动：后续 loadOnStartup 使用 assets 模板内容
            Logx.w(TAG, "写出外部异体字表失败", e)
        }
    }

    /**
     * 应用启动时调用一次：读盘并填充 [cache] 与 [lastError]。
     * - 外部文件存在且可解析 → 原样读取、原样使用（不补字、不去重、不改顺序）；
     * - 外部文件不存在 / 为空 / 读取失败 / JSON 非法 / 空表 → 回退 assets 内置模板，
     *   并通过 [lastError] 提示用户，避免异体字关联搜索静默失效；
     * - 之后搜索一律走 [current]，本方法返回后直到进程结束都不再读盘。
     */
    fun loadOnStartup(context: Context) {
        lastError = null
        val f = externalFile()
        val external = try {
            if (f.isFile) f.readText(Charsets.UTF_8).takeIf { it.isNotBlank() } else null
        } catch (e: Exception) {
            lastError = "variants.json 读取失败，已改用内置字表：${e.message}"
            null
        }
        val template = templateJson(context)
        cache = try {
            val parsed = Variants.parse(external ?: template)
            if (parsed.isNotEmpty()) {
                parsed
            } else {
                if (lastError == null) lastError = "variants.json 为空表，已改用内置字表"
                Variants.parse(template)
            }
        } catch (e: Exception) {
            lastError = "variants.json 格式错误，已改用内置字表：${e.message}"
            try {
                Variants.parse(template)
            } catch (e: Exception) {
                Logx.w(TAG, "内置异体字模板也解析失败", e)
                emptyMap()
            }
        }
    }
}
