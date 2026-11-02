package com.onlyread.app.util

import java.net.URLDecoder
import java.nio.charset.Charset

/**
 * zip 路径与编码工具。
 *
 * 约定：EPUB 内部的 href 是 URL 编码的相对 IRI，而 ZipFile 条目名是字面路径。
 * 因此所有来自 OPF 的 href 先 decode 再 join/normalize，
 * 所有来自 WebView 请求的 path 也先 decode 再 normalize，两边即可直接比对。
 */
object ZipPaths {

    /** 折叠 "."、".."、空段与多余的 "/"，禁止路径逃逸（越界段直接丢弃） */
    fun normalize(path: String): String {
        val stack = ArrayDeque<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(seg)
            }
        }
        return stack.joinToString("/")
    }

    fun join(dir: String, href: String): String =
        normalize(if (dir.isEmpty()) href else "$dir/$href")

    fun decode(path: String): String =
        try {
            URLDecoder.decode(path, "UTF-8")
        } catch (_: Exception) {
            path
        }

    /** 依据 BOM 与 XML prolog 推断字节流的字符集，兜底 UTF-8 */
    fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return Charsets.UTF_16LE
            if (b0 == 0xFE && b1 == 0xFF) return Charsets.UTF_16BE
        }
        val head = String(bytes, 0, minOf(bytes.size, 600), Charsets.US_ASCII)
        val m = Regex("encoding\\s*=\\s*[\"']([^\"']+)[\"']").find(head)
        if (m != null) {
            runCatching { return Charset.forName(m.groupValues[1].trim()) }
        }
        return Charsets.UTF_8
    }

    fun mimeTypeFor(entryPath: String): String {
        val ext = entryPath.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "xhtml" -> "application/xhtml+xml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ncx" -> "application/x-dtbncx+xml"
            "opf" -> "application/oebps-package+xml"
            "txt" -> "text/plain"
            "xml" -> "application/xml"
            else -> "application/octet-stream"
        }
    }
}
