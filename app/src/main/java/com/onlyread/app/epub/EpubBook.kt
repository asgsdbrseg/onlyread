package com.onlyread.app.epub

/**
 * EPUB 元数据模型（M0/M1）
 *
 * 所有 zip 内路径均为「规范化字面路径」：
 * 以 OPF 所在目录为基准解析、已做 URL 解码与 ./.. 折叠，
 * 可直接与 ZipFile 条目名比对。
 */
data class EpubItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val properties: String?
)

data class EpubSpineItem(
    val idref: String,
    val href: String,   // OPF 中原始 href（已 URL 解码）
    val path: String    // zip 内完整路径（字面量，可直接查 ZipFile）
)

/** 目录条目（FR-06）：M1 支持_EPUB3 NAV 与 EPUB2 NCX */
data class TocEntry(
    val title: String,
    val path: String,      // zip 内目标文档路径（不含 #fragment）
    val frag: String?,     // #fragment（如有）
    val level: Int,        // 缩进层级 0..
    val spineIndex: Int    // 解析出的 spine 序号；-1 表示未能映射
)

data class EpubBook(
    val title: String,
    val author: String?,
    val language: String?,
    val publisher: String?,
    val opfDir: String,        // OPF 所在目录（"" 表示 zip 根）
    val coverPath: String?,    // zip 内封面图片路径
    val spine: List<EpubSpineItem>,
    val toc: List<TocEntry> = emptyList(),
    val navPath: String? = null // M3：EPUB3 NAV（目录文件）zip 内路径——不作为正文渲染
)
