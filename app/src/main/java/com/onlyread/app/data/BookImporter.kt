package com.onlyread.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.onlyread.app.App
import com.onlyread.app.R
import com.onlyread.app.epub.EpubArchive
import com.onlyread.app.util.EpubNames
import com.onlyread.app.util.Hashing
import com.onlyread.app.util.Logx
import com.onlyread.app.util.UriPath
import java.io.File

/**
 * 书籍导入的单一入口：应用内文件浏览器、SAF 选择、外部文件夹「打开方式」共用同一套逻辑，
 * 避免三处各自维护解析 / 去重 / 封面 / 目录缓存。
 *
 * ⚠️ 严格直引原文件（Reasily 式）：只接受能解析出真实路径且可读的**本地文件**，
 * 绝不把 EPUB 复制到应用私有目录。无法解析（第三方 content provider、`Android/data`
 * 等分区存储目录、云盘）时抛出带提示文案的异常，由调用方提示用户改用本机文件。
 */
object BookImporter {

    private const val TAG = "BookImporter"

    data class Result(
        val bookId: Long,
        val title: String,
        val message: String,
        val alreadyExists: Boolean
    )

    /** 兜底 intent-filter 会放行 octet-stream，导入前先按 MIME / 名字过滤。 */
    fun isEpubUri(context: Context, uri: Uri): Boolean {
        val cr = context.contentResolver
        if (EpubNames.isEpubMime(runCatching { cr.getType(uri) }.getOrNull())) return true
        if (EpubNames.looksLikeEpub(displayName(context, uri))) return true
        if (EpubNames.looksLikeEpub(uri.path)) return true
        if (EpubNames.looksLikeEpub(UriPath.resolve(cr, uri))) return true
        return false
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    } catch (e: Exception) {
        Logx.w(TAG, "读取 Uri 显示名失败", e)
        null
    }

    /**
     * Uri → 真实本地文件。解析不到或不可读即抛异常（文案取自 import_need_local），
     * 不复制、不降级到私有目录。
     */
    fun resolveFile(context: Context, uri: Uri): File {
        val file = UriPath.resolve(context.contentResolver, uri)?.let(::File)
        if (file == null || !file.isFile || !file.canRead()) {
            throw IllegalArgumentException(context.getString(R.string.import_need_local))
        }
        return file
    }

    /** 外部「打开方式」入口：严格解析后按绝对路径导入。 */
    fun importUri(context: Context, uri: Uri): Result = importFile(context, resolveFile(context, uri))

    /** 绝对路径导入：路径归一 → 去重 → 解析元数据 → 登记 → 目录缓存 → 封面落盘。 */
    fun importFile(context: Context, file: File): Result {
        val db = (context.applicationContext as App).db
        // ⚠️ 路径归一化：外部「打开方式」经 file:///sdcard/... 或 provider 的 _data 列得到的路径，
        // 与应用内文件浏览器遍历出的 /storage/emulated/0/... 常常只是同一文件的不同写法。
        // canonicalFile 解析符号链接后两者一致，避免同一本书被登记两次。
        val canonical = try {
            file.canonicalFile
        } catch (e: Exception) {
            Logx.w(TAG, "canonicalFile 归一失败，使用原路径", e)
            file
        }
        val path = canonical.absolutePath
        val fallback = canonical.nameWithoutExtension

        fun existing(id: Long, title: String): Result {
            val t = db.getById(id)?.title ?: title
            // 顺手把历史记录的路径归一到真实路径，后续直接打开不必再解析旧写法
            db.updatePath(id, path)
            return Result(id, t, "该书已在书架中", true)
        }

        db.getIdByPath(path)?.let { return existing(it, fallback) }
        // 兼容升级前按未归一路径登记的旧数据
        db.getIdByPath(file.absolutePath)?.let { return existing(it, fallback) }

        EpubArchive(canonical).use { archive ->
            val book = archive.parseBook()
                ?: throw IllegalArgumentException("EPUB 解析失败：未找到有效的 OPF")
            val identityHash = Hashing.shortHash(canonical)
            // 内容指纹兜底：同一本书以不同路径（副本 / 不同文件管理器给的不同写法）导入时
            // 也只登记一次，复用已有 id 直接打开。
            db.getIdByIdentityHash(identityHash)?.let {
                return existing(it, book.title.ifBlank { fallback })
            }
            val entity = BookEntity(
                id = 0,
                path = path,
                size = canonical.length(),
                mtimeMs = canonical.lastModified(),
                identityHash = identityHash,
                title = book.title.ifBlank { fallback },
                author = book.author,
                language = book.language,
                publisher = book.publisher,
                coverPath = null,
                spineCount = book.spine.size,
                addedAt = System.currentTimeMillis(),
                lastReadAt = 0L
            )
            val id = db.insert(entity)
            if (id < 0) {
                // path UNIQUE 冲突（并发导入等）：复用已有记录，绝不留下半条数据
                db.getIdByPath(path)?.let { return existing(it, entity.title) }
                throw IllegalArgumentException("书籍登记失败：路径冲突")
            }
            try {
                db.replaceToc(
                    id,
                    book.toc.mapIndexed { i, t ->
                        TocRow(i, t.spineIndex, t.title, t.level, t.frag ?: "")
                    }
                )
            } catch (e: Exception) {
                Logx.w(TAG, "目录缓存写入失败（不影响书籍登记）", e)
            }
            book.coverPath?.let { href ->
                archive.entryBytes(href)?.let { bytes ->
                    val covers = File(context.filesDir, "covers").apply { mkdirs() }
                    val coverFile = File(covers, "cover_$id")
                    coverFile.writeBytes(bytes)
                    db.updateCoverPath(id, coverFile.absolutePath)
                }
            }
            return Result(id, entity.title, "已导入：${entity.title}", false)
        }
    }
}
