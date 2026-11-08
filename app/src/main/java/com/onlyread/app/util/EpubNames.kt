package com.onlyread.app.util

/**
 * EPUB 识别（纯 JVM，不依赖 Android，供单元测试直接验证）。
 *
 * 外部「打开方式」的兜底 intent-filter 会放行 application/octet-stream，
 * 因此导入前需要按 MIME / 文件名做一次过滤，避免把任意二进制文件也登记进书架。
 * 这里只做识别，不涉及任何文件复制或命名。
 */
object EpubNames {

    /** 名字末段是否以 .epub 结尾（大小写不敏感）。 */
    fun looksLikeEpub(name: String?): Boolean =
        name?.substringAfterLast('/')?.trim()?.lowercase()?.endsWith(".epub") == true

    /** MIME 是否为 EPUB（标准 application/epub+zip 及各类变体）。 */
    fun isEpubMime(mime: String?): Boolean =
        mime?.contains("epub", ignoreCase = true) == true
}
