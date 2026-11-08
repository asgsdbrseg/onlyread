package com.onlyread.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.onlyread.app.App
import com.onlyread.app.R
import com.onlyread.app.epub.EpubArchive
import com.onlyread.app.util.EpubNames
import com.onlyread.app.util.Hashing
import com.onlyread.app.util.UriPath
import java.io.File

/**
 * 书籍导入的单一入口：应用内文件浏览器、SAF 选择、外部文件夹「打开方式」共用同一套逻辑，
 * 避免三处各自维护解析 / 去重 / 封面 / 目录缓存。
 *
 * ⚠️ 严格直引原文件（Reasily 式）：只接受能解析出真实路径且可读的**本地文件**，
 * 绝不把 EPUB 复制到应用私有目录。无法解析（第三方 content provider、云盘等）时抛出
 * 带提示文案的异常，由调用方提示用户改用系统「文件」应用选择本机文件。
 */
object BookImporter {

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
    } catch (_: Exception) {
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

    /** 绝对路径导入：解析元数据 → 登记 → 目录缓存 → 封面落盘。 */
    fun importFile(context: Context, file: File): Result {
        val db = (context.applicationContext as App).db
        db.getIdByPath(file.absolutePath)?.let { id ->
            val title = db.getById(id)?.title ?: file.nameWithoutExtension
            return Result(id, title, "该书已在书架中", true)
        }
        EpubArchive(file).use { archive ->
            val book = archive.parseBook()
                ?: throw IllegalArgumentException("EPUB 解析失败：未找到有效的 OPF")
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
            try {
                db.replaceToc(
                    id,
                    book.toc.mapIndexed { i, t ->
                        TocRow(i, t.spineIndex, t.title, t.level, t.frag ?: "")
                    }
                )
            } catch (_: Exception) {
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
