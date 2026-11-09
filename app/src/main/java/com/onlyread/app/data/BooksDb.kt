package com.onlyread.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.onlyread.app.util.Logx

/**
 * 书库表（FR-01/FR-02 数据层地基）。
 * 身份键 = path（唯一约束）+ size/mtimeMs/identityHash 快检字段；
 * font_id 为决策 #17「每本书字体覆盖」预留（M2 启用）。
 *
 * 实现说明：为保持工程零第三方依赖（纯框架 + Kotlin 标准库），
 * 使用框架 SQLiteOpenHelper 直接实现，表结构与《设计文档 v1.1》§5 一致。
 * v2（M1）：新增 progress（进度锚点，FR-04/FR-11）与 toc（目录缓存，FR-06）。
 * v4（0.4.22）：toc 增加 frag 列；此前 DB 版本号误设为 3，导致 onUpgrade 的
 * `oldVersion < 4` 分支永不触发，v3 老库查询 frag 会抛异常并被静默吞掉。
 */
data class BookEntity(
    val id: Long,
    val path: String,
    val size: Long,
    val mtimeMs: Long,
    val identityHash: String,
    val title: String,
    val author: String?,
    val language: String?,
    val publisher: String?,
    val coverPath: String?,
    val spineCount: Int,
    val addedAt: Long,
    val lastReadAt: Long,
    val fontId: Long? = null,
    val fontPx: Int = 0,        // M2：每书字号（0=书内默认）
    val lineH: Double = 0.0,    // M2：每书行距（0=书内默认）
    val fontFamily: String = "" // M2：每书字族（''=书内样式 / serif / sans）
)

data class TocRow(val ord: Int, val spineIndex: Int, val title: String, val level: Int, val frag: String = "")

data class ProgressRow(
    val spineIndex: Int,
    val pageIndex: Int,
    val totalPages: Int,
    val elemOrdinal: Int,
    val percent: Double
)

class BooksDb(context: Context) : SQLiteOpenHelper(context, "onlyread.db", null, 4) {

    companion object {
        private const val TAG = "BooksDb"
        /** 显式列清单：禁止 SELECT *，避免表结构演进后列序错位。 */
        private const val BOOK_SELECT = "id,path,size,mtime_ms,identity_hash,title,author," +
            "language,publisher,cover_path,spine_count,added_at,last_read_at," +
            "font_id,font_px,line_h,font_family"
        private const val TOC_SELECT = "ord,spine_index,title,level,frag"
        private const val PROGRESS_SELECT = "spine_index,page_index,total_pages,elem_ordinal,percent"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE books(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                path TEXT NOT NULL UNIQUE,
                size INTEGER, mtime_ms INTEGER, identity_hash TEXT,
                title TEXT, author TEXT, language TEXT, publisher TEXT,
                cover_path TEXT, spine_count INTEGER,
                added_at INTEGER, last_read_at INTEGER, font_id INTEGER,
                font_px INTEGER DEFAULT 0, line_h REAL DEFAULT 0, font_family TEXT DEFAULT ''
            )"""
        )
        createV2Tables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createV2Tables(db)
        if (oldVersion < 3) {
            // M2：每书排版参数（决策 #17 启用）
            if (!hasColumn(db, "books", "font_px")) {
                db.execSQL("ALTER TABLE books ADD COLUMN font_px INTEGER DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN line_h REAL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN font_family TEXT DEFAULT ''")
            }
        }
        if (oldVersion < 4) {
            // M2.1：目录缓存增加 #fragment 列（同文件多标题跳转）。
            // 先查列再改表：既不吞异常，也能兼容「v2 建表时已带 frag」的库。
            if (!hasColumn(db, "toc", "frag")) {
                db.execSQL("ALTER TABLE toc ADD COLUMN frag TEXT DEFAULT ''")
            }
        }
    }

    /** PRAGMA 查询列是否存在，替代 try/catch 静默忽略 ALTER 失败。 */
    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            if (nameIdx < 0) return@use false
            while (c.moveToNext()) {
                if (c.getString(nameIdx).equals(column, ignoreCase = true)) return@use true
            }
            false
        }

    private fun createV2Tables(db: SQLiteDatabase) {
        // 进度锚点（FR-04）：每书一行，INSERT OR REPLACE 覆盖
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS progress(
                book_id INTEGER PRIMARY KEY,
                spine_index INTEGER, page_index INTEGER, total_pages INTEGER,
                elem_ordinal INTEGER, percent REAL, updated_at INTEGER
            )"""
        )
        // 目录缓存（FR-06）
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS toc(
                book_id INTEGER, ord INTEGER, spine_index INTEGER,
                title TEXT, level INTEGER, frag TEXT DEFAULT ''
            )"""
        )
    }

    // ————— 书架 —————

    fun getAll(): List<BookEntity> {
        val out = ArrayList<BookEntity>()
        readableDatabase.rawQuery(
            "SELECT $BOOK_SELECT FROM books ORDER BY CASE WHEN last_read_at > 0 THEN last_read_at ELSE added_at END DESC",
            null
        ).use { c ->
            while (c.moveToNext()) out.add(fromCursor(c))
        }
        return out
    }

    fun getById(id: Long): BookEntity? {
        readableDatabase.rawQuery(
            "SELECT $BOOK_SELECT FROM books WHERE id=?", arrayOf(id.toString())
        ).use { c ->
            return if (c.moveToFirst()) fromCursor(c) else null
        }
    }

    fun countByPath(path: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM books WHERE path=?", arrayOf(path)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /** 外部「打开方式」重复打开同一文件时，直接取已有 id 以打开阅读，不重复插入。 */
    fun getIdByPath(path: String): Long? =
        readableDatabase.rawQuery("SELECT id FROM books WHERE path=?", arrayOf(path)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }

    fun insert(b: BookEntity): Long {
        val cv = ContentValues().apply {
            put("path", b.path)
            put("size", b.size)
            put("mtime_ms", b.mtimeMs)
            put("identity_hash", b.identityHash)
            put("title", b.title)
            put("author", b.author)
            put("language", b.language)
            put("publisher", b.publisher)
            put("cover_path", b.coverPath)
            put("spine_count", b.spineCount)
            put("added_at", b.addedAt)
            put("last_read_at", b.lastReadAt)
            put("font_id", b.fontId)
        }
        return writableDatabase.insert("books", null, cv)
    }

    fun updateCoverPath(id: Long, path: String?) {
        val cv = ContentValues().apply { put("cover_path", path) }
        writableDatabase.update("books", cv, "id=?", arrayOf(id.toString()))
    }

    fun touch(id: Long, time: Long) {
        val cv = ContentValues().apply { put("last_read_at", time) }
        writableDatabase.update("books", cv, "id=?", arrayOf(id.toString()))
    }

    fun deleteById(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("books", "id=?", arrayOf(id.toString()))
            db.delete("progress", "book_id=?", arrayOf(id.toString()))
            db.delete("toc", "book_id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ————— 进度锚点（M1，FR-04/FR-11） —————

    fun getProgress(bookId: Long): ProgressRow? {
        readableDatabase.rawQuery(
            "SELECT $PROGRESS_SELECT FROM progress WHERE book_id=?",
            arrayOf(bookId.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                return ProgressRow(
                    c.getInt(c.getColumnIndexOrThrow("spine_index")),
                    c.getInt(c.getColumnIndexOrThrow("page_index")),
                    c.getInt(c.getColumnIndexOrThrow("total_pages")),
                    c.getInt(c.getColumnIndexOrThrow("elem_ordinal")),
                    c.getDouble(c.getColumnIndexOrThrow("percent"))
                )
            }
        }
        return null
    }

    fun saveProgress(bookId: Long, row: ProgressRow) {
        val cv = ContentValues().apply {
            put("book_id", bookId)
            put("spine_index", row.spineIndex)
            put("page_index", row.pageIndex)
            put("total_pages", row.totalPages)
            put("elem_ordinal", row.elemOrdinal)
            put("percent", row.percent)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("progress", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ————— 目录缓存（M1，FR-06） —————

    fun replaceToc(bookId: Long, rows: List<TocRow>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("toc", "book_id=?", arrayOf(bookId.toString()))
            rows.forEach { r ->
                val cv = ContentValues().apply {
                    put("book_id", bookId)
                    put("ord", r.ord)
                    put("spine_index", r.spineIndex)
                    put("title", r.title)
                    put("level", r.level)
                    put("frag", r.frag)
                }
                db.insert("toc", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getToc(bookId: Long): List<TocRow> {
        val out = ArrayList<TocRow>()
        readableDatabase.rawQuery(
            "SELECT $TOC_SELECT FROM toc WHERE book_id=? ORDER BY ord",
            arrayOf(bookId.toString())
        ).use { c ->
            val iOrd = c.getColumnIndexOrThrow("ord")
            val iSpine = c.getColumnIndexOrThrow("spine_index")
            val iTitle = c.getColumnIndexOrThrow("title")
            val iLevel = c.getColumnIndexOrThrow("level")
            val iFrag = c.getColumnIndexOrThrow("frag")
            while (c.moveToNext()) {
                out.add(
                    TocRow(
                        c.getInt(iOrd), c.getInt(iSpine), c.getString(iTitle) ?: "",
                        c.getInt(iLevel), c.getString(iFrag) ?: ""
                    )
                )
            }
        }
        return out
    }

    /** 按列名取值，绝不依赖 SELECT * 的列顺序。 */
    private fun fromCursor(c: Cursor): BookEntity = BookEntity(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        path = c.getString(c.getColumnIndexOrThrow("path")) ?: "",
        size = c.getLong(c.getColumnIndexOrThrow("size")),
        mtimeMs = c.getLong(c.getColumnIndexOrThrow("mtime_ms")),
        identityHash = c.getString(c.getColumnIndexOrThrow("identity_hash")) ?: "",
        title = c.getString(c.getColumnIndexOrThrow("title")) ?: "",
        author = c.getString(c.getColumnIndexOrThrow("author")),
        language = c.getString(c.getColumnIndexOrThrow("language")),
        publisher = c.getString(c.getColumnIndexOrThrow("publisher")),
        coverPath = c.getString(c.getColumnIndexOrThrow("cover_path")),
        spineCount = c.getInt(c.getColumnIndexOrThrow("spine_count")),
        addedAt = c.getLong(c.getColumnIndexOrThrow("added_at")),
        lastReadAt = c.getLong(c.getColumnIndexOrThrow("last_read_at")),
        fontId = if (c.isNull(c.getColumnIndexOrThrow("font_id"))) null
        else c.getLong(c.getColumnIndexOrThrow("font_id")),
        fontPx = if (c.isNull(c.getColumnIndexOrThrow("font_px"))) 0
        else c.getInt(c.getColumnIndexOrThrow("font_px")),
        lineH = if (c.isNull(c.getColumnIndexOrThrow("line_h"))) 0.0
        else c.getDouble(c.getColumnIndexOrThrow("line_h")),
        fontFamily = c.getString(c.getColumnIndexOrThrow("font_family")) ?: ""
    )

    // ————— 导入去重（M3.10） —————

    /** 同一内容指纹（首/尾 64KB SHA-1 + 长度）已登记 → 视为同一本书，直接复用 id。 */
    fun getIdByIdentityHash(hash: String): Long? {
        if (hash.isEmpty()) return null
        return readableDatabase.rawQuery(
            "SELECT id FROM books WHERE identity_hash=? ORDER BY id LIMIT 1", arrayOf(hash)
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }

    /** 去重命中后把旧记录的路径归一到本次解析出的真实路径，保证后续直接打开有效。 */
    fun updatePath(id: Long, path: String) {
        val cv = ContentValues().apply { put("path", path) }
        try {
            writableDatabase.update("books", cv, "id=?", arrayOf(id.toString()))
        } catch (e: Exception) {
            // UNIQUE 冲突（该路径已被另一条记录占用）时保持原记录不动
            Logx.w(TAG, "路径归一跳过（可能 UNIQUE 冲突）: $path", e)
        }
    }

    /**
     * 清理历史重复记录：同一 identity_hash 或同一 canonical 真实路径只保留 id 最小的一条。
     * 旧版本仅按 path 字符串精确去重，/sdcard 与 /storage/emulated/0 指向同一文件时会出现两本书；
     * 启动时一次性归并，避免升级后书架仍显示重复条目（连带清掉其进度/目录缓存）。
     */
    fun dedupeDuplicates() {
        val db = writableDatabase
        val toDelete = LinkedHashSet<Long>()
        // ① 内容指纹相同
        val groups = HashMap<String, MutableList<Long>>()
        db.rawQuery(
            "SELECT id, identity_hash FROM books WHERE identity_hash IS NOT NULL AND identity_hash<>'' ORDER BY id",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val h = c.getString(1) ?: ""
                groups.getOrPut(h) { ArrayList() }.add(id)
            }
        }
        for (ids in groups.values) {
            for (i in 1 until ids.size) toDelete += ids[i]
        }
        // ② 真实路径相同（符号链接 / 不同 provider 写法）
        val seen = HashMap<String, Long>()
        db.rawQuery("SELECT id, path FROM books ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val p = c.getString(1) ?: ""
                if (p.isEmpty()) continue
                val key = try {
                    java.io.File(p).canonicalPath
                } catch (e: Exception) {
                    Logx.w(TAG, "canonicalPath 失败，退化为原路径: $p", e)
                    p
                }
                val prev = seen[key]
                if (prev == null) seen[key] = id else if (id != prev) toDelete += id
            }
        }
        if (toDelete.isEmpty()) return
        db.beginTransaction()
        try {
            for (id in toDelete) {
                db.delete("books", "id=?", arrayOf(id.toString()))
                db.delete("progress", "book_id=?", arrayOf(id.toString()))
                db.delete("toc", "book_id=?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ————— 每书排版参数（M2） —————

    fun saveTypography(bookId: Long, fontPx: Int, lineH: Double, fontFamily: String) {
        val cv = ContentValues().apply {
            put("font_px", fontPx)
            put("line_h", lineH)
            put("font_family", fontFamily)
        }
        writableDatabase.update("books", cv, "id=?", arrayOf(bookId.toString()))
    }
}
