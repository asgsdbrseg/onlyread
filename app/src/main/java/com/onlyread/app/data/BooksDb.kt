package com.onlyread.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 书库表（FR-01/FR-02 数据层地基）。
 * 身份键 = path（唯一约束）+ size/mtimeMs/identityHash 快检字段；
 * font_id 为决策 #17「每本书字体覆盖」预留（M2 启用）。
 *
 * 实现说明：为保持工程零第三方依赖（纯框架 + Kotlin 标准库），
 * 使用框架 SQLiteOpenHelper 直接实现，表结构与《设计文档 v1.1》§5 一致。
 * v2（M1）：新增 progress（进度锚点，FR-04/FR-11）与 toc（目录缓存，FR-06）。
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

class BooksDb(context: Context) : SQLiteOpenHelper(context, "onlyread.db", null, 3) {

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
            db.execSQL("ALTER TABLE books ADD COLUMN font_px INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE books ADD COLUMN line_h REAL DEFAULT 0")
            db.execSQL("ALTER TABLE books ADD COLUMN font_family TEXT DEFAULT ''")
        }
        if (oldVersion < 4) {
            // M2.1：目录缓存增加 #fragment 列（同文件多标题跳转）
            try { db.execSQL("ALTER TABLE toc ADD COLUMN frag TEXT DEFAULT ''") } catch (_: Exception) {}
        }
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
            "SELECT * FROM books ORDER BY CASE WHEN last_read_at > 0 THEN last_read_at ELSE added_at END DESC",
            null
        ).use { c ->
            while (c.moveToNext()) out.add(fromCursor(c))
        }
        return out
    }

    fun getById(id: Long): BookEntity? {
        readableDatabase.rawQuery("SELECT * FROM books WHERE id=?", arrayOf(id.toString())).use { c ->
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
            "SELECT spine_index, page_index, total_pages, elem_ordinal, percent FROM progress WHERE book_id=?",
            arrayOf(bookId.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                return ProgressRow(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getDouble(4))
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
            "SELECT ord, spine_index, title, level, frag FROM toc WHERE book_id=? ORDER BY ord",
            arrayOf(bookId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(TocRow(c.getInt(0), c.getInt(1), c.getString(2) ?: "", c.getInt(3), c.getString(4) ?: ""))
            }
        }
        return out
    }

    private fun fromCursor(c: Cursor) = BookEntity(
        id = c.getLong(0),
        path = c.getString(1) ?: "",
        size = c.getLong(2),
        mtimeMs = c.getLong(3),
        identityHash = c.getString(4) ?: "",
        title = c.getString(5) ?: "",
        author = c.getString(6),
        language = c.getString(7),
        publisher = c.getString(8),
        coverPath = c.getString(9),
        spineCount = c.getInt(10),
        addedAt = c.getLong(11),
        lastReadAt = c.getLong(12),
        fontId = if (c.isNull(13)) null else c.getLong(13),
        fontPx = if (c.isNull(14)) 0 else c.getInt(14),
        lineH = if (c.isNull(15)) 0.0 else c.getDouble(15),
        fontFamily = c.getString(16) ?: ""
    )

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
