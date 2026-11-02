package com.onlyread.app.util

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment

/**
 * M2.2：SAF/file Uri → 真实文件路径（Reasily 式直接引用原文件，不复制）。
 * 覆盖三类常见来源：file://、「文件」应用的 externalstorage document、媒体/其他 provider 的 _data 列。
 */
object UriPath {

    fun resolve(cr: ContentResolver, uri: Uri): String? {
        // 1) file:// 直接取路径
        if (uri.scheme == "file") {
            val p = uri.path
            if (!p.isNullOrBlank()) return p
        }
        // 2) 系统文件应用（DocumentsUI）本地存储：primary:Download/x.epub → /storage/emulated/0/Download/x.epub
        if (uri.authority == "com.android.externalstorage.documents") {
            val doc = uri.lastPathSegment ?: return null
            val parts = doc.split(":", limit = 2)
            if (parts.size == 2) {
                val base = when (parts[0]) {
                    "primary" -> Environment.getExternalStorageDirectory()?.absolutePath
                    else -> "/storage/${parts[0]}"
                }
                if (!base.isNullOrBlank()) return "$base/${parts[1]}"
            }
            return null
        }
        // 3) 常规 provider（MediaStore 等）的 _data 列
        return try {
            cr.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
