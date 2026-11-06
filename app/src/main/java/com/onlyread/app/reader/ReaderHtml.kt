package com.onlyread.app.reader

/**
 * 阅读器 HTML 处理（纯函数，无 Android 依赖，可单元测试）。
 *
 * 全文搜索扫描、可见文本抽取、注释块标记、页码换算集中于此。
 * BookActivity 只负责线程编排与 WebView 渲染——繁重的字符串/正则变换
 * 通过 [markChapterForReader] 在后台线程执行，避免大书打开与搜索跳转时主线程冻结。
 */
object ReaderHtml {

    /**
     * 块元素扫描正则（扫描与注入共用同一迭代序，保证 elemIndex 一致）。
     * 与 BookActivity 原 blockRe 完全一致。
     */
    val BLOCK_PATTERN: Regex =
        Regex("(?is)<(p|li|blockquote|h[1-6]|dd|dt|div)\\b([^>]*)>(.*?)</\\1\\s*>")

    private val inlineTags = setOf(
        "a", "span", "u", "b", "i", "em", "strong", "sup", "sub", "small", "big",
        "code", "cite", "q", "mark", "font", "s", "strike", "tt", "abbr", "bdi",
        "bdo", "ruby", "rt", "rp", "time", "var", "kbd", "samp", "del", "ins",
        "label", "br", "wbr"
    )

    private val entNameRe = Regex("&[a-zA-Z][a-zA-Z0-9]*;")
    private val entNumRe = Regex("&#(x?)([0-9a-fA-F]+);")

    /** 行内标签判定（抽掉标签后文字应无缝拼接，跨 <u>/<span> 的词组可命中） */
    fun isInlineTag(tag: String): Boolean = tag.lowercase() in inlineTags

    /**
     * 抽取块内可见文本（搜索扫描提速，不依赖 Html.fromHtml）：
     * 行内标签移除（无缝拼接），块级标签换成换行（防止跨段落误配）；
     * 命名与数字（十进制/十六进制）字符实体就地解码。
     */
    fun extractVisibleText(inner: String): String {
        // 纯文本块（无标签无实体，大量正文短块）直接返回，跳过 5 趟正则
        if ('<' !in inner && '&' !in inner) return inner
        val stripped = inner
            .replace(Regex("(?is)<(script|style)[\\s\\S]*?</\\1>"), " ")
            .replace(Regex("(?is)</?([a-zA-Z][a-zA-Z0-9]*)[^>]*>")) { m ->
                if (m.groupValues[1].lowercase() in inlineTags) "" else "\n"
            }
            .replace(entNameRe) { m ->
                when (m.value.lowercase()) {
                    "&nbsp;" -> " "
                    "&amp;" -> "&"
                    "&lt;" -> "<"
                    "&gt;" -> ">"
                    "&quot;" -> "\""
                    "&apos;", "&#39;" -> "'"
                    else -> " "
                }
            }
            .replace(entNumRe) { m ->
                val code = try {
                    if (m.groupValues[1] == "x") m.groupValues[2].toInt(16) else m.groupValues[2].toInt()
                } catch (_: Exception) {
                    return@replace " "
                }
                if (code in 0x20..0xFFFF && !Character.isISOControl(code)) code.toChar().toString() else " "
            }
        // 仅塌缩空格类空白（含 NBSP），保留 \n 作为块边界
        return stripped.replace(Regex("[ \t\n\u000B\u000C\r\u00A0]+"), " ").trim()
    }

    /**
     * 命中片段（前后各取 [context] 字符，越界补省略号）。
     * 与 BookActivity 原扫描逻辑一致（前 24、后 25 字符）。
     */
    fun buildSnippet(text: String, match: IntRange, context: Int = 24): String {
        val s = (match.first - context).coerceAtLeast(0)
        val e = (match.last + context + 1).coerceAtMost(text.length)
        val sb = StringBuilder()
        if (s > 0) sb.append("…")
        sb.append(text.substring(s, e).replace("\n", " "))
        if (e < text.length) sb.append("…")
        return sb.toString()
    }

    /**
     * 章节 HTML 注释块标记（data-fnhide）——M3 正文注释不进排版流、弹窗可取。
     * 原 BookActivity.doLoad 的多趟标记逻辑原样迁移为独立纯函数，以便在后台线程执行。
     *
     * ⚠️ 不变量：注入的 data-srch0（搜索命中标记）必须保留，否则 JS 无法定位命中页。
     * 本函数只新增 data-fnhide，绝不删除/改写既有属性，故 data-srch0 必然保留。
     */
    fun markChapterForReader(html: String, itemPath: String): String {
        var h = html.replace(Regex("(?i)<aside([^>]*)>"), "<aside data-fnhide$1>")
        // epub:type 精确值匹配（按空格 split 判断）——防止 "noteref" 含 "note" 子串被误隐藏
        val fnTypes = hashSetOf("footnote", "note", "endnote", "rearnote")
        val etypeRe = Regex("(?i)<([a-zA-Z][a-zA-Z0-9]*)((?:\\s[^>]*)?)>")
        val etypeAttrRe = Regex("(?i)\\sepub:type\\s*=\\s*[\"']([^\"']*)[\"']")
        val sb2 = StringBuilder()
        var last2 = 0
        for (m in etypeRe.findAll(h)) {
            val attrs = m.groupValues[2]
            val em = etypeAttrRe.find(attrs) ?: continue
            val types = em.groupValues[1].trim().lowercase().split(Regex("\\s+"))
            if (types.none { it in fnTypes }) continue // noteref / other → 不隐藏
            if (attrs.contains("data-fnhide")) continue
            sb2.append(h, last2, m.range.first)
            sb2.append('<').append(m.groupValues[1]).append(" data-fnhide").append(attrs).append('>')
            last2 = m.range.last + 1
        }
        sb2.append(h.substring(last2))
        h = sb2.toString()
        val selfName = itemPath.substringAfterLast('/')
        val refIds = HashSet<String>()
        Regex("""href\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val href = m.groupValues[1]
            val i = href.indexOf('#')
            if (i >= 0 && href.length > i + 1) {
                val file = href.substring(0, i).substringAfterLast('/')
                if (file.isEmpty() || file.equals(selfName, ignoreCase = true)) {
                    refIds += try {
                        java.net.URLDecoder.decode(href.substring(i + 1), "UTF-8")
                    } catch (_: Exception) {
                        href.substring(i + 1)
                    }
                }
            }
        }
        if (refIds.isNotEmpty()) {
            // 只标记块级目标——回链指向的正文引用锚（行内 <a id="refN">）不隐藏；
            // body/head 绝不标记（防止整页消失）
            val blockTags = hashSetOf(
                "p", "div", "li", "ul", "ol", "blockquote", "dd", "dt", "dl", "td", "th", "tr",
                "section", "article", "table", "figure", "figcaption", "main", "pre",
                "header", "footer", "address", "details", "summary", "fieldset", "form", "caption"
            )
            val tagRe = Regex("""<([a-zA-Z][a-zA-Z0-9]*)((?:\s[^>]*)?)>""")
            val idRe = Regex("""\bid\s*=\s*["']?([^"'\s>/]+)""")
            val sb = StringBuilder()
            var last = 0
            for (m in tagRe.findAll(h)) {
                val tag = m.groupValues[1].lowercase()
                if (tag == "body" || tag == "html" || tag == "head") continue
                if (tag !in blockTags) continue // 行内锚（引用标记）保持可见
                val attrs = m.groupValues[2]
                if (attrs.contains("data-fnhide")) continue
                val idv = idRe.find(attrs)?.groupValues?.get(1)
                if (idv != null && idv in refIds) {
                    sb.append(h, last, m.range.first)
                    sb.append('<').append(m.groupValues[1]).append(" data-fnhide").append(attrs).append('>')
                    last = m.range.last + 1
                }
            }
            sb.append(h.substring(last))
            h = sb.toString()
        }
        return h
    }

    /**
     * 命中元素内容坐标 → 页码（与 JS __pageOfElem / __initReader.frag 同公式）：
     * page = floor((contentLeft - marginLeft) / pageWidth)。
     *
     * 该公式在 JS 侧于字体/布局就绪后的重定位（__reanchor）中被复用，
     * 保证搜索跳转落到正确页（修复“页码比正确页码少两三页”的漂移）。
     */
    fun pageFromContentLeft(contentLeftPx: Double, pageWidthPx: Double, marginLeftPx: Double): Int {
        if (pageWidthPx <= 0) return 0
        val p = kotlin.math.floor((contentLeftPx - marginLeftPx) / pageWidthPx).toInt()
        return if (p < 0) 0 else p
    }
}
