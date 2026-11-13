package com.onlyread.app.reader

/**
 * 阅读器 HTML 处理（纯函数，无 Android 依赖，可单元测试）。
 *
 * 全文搜索扫描、可见文本抽取、注释块标记、页码换算集中于此。
 * BookActivity 只负责线程编排与 WebView 渲染——繁重的字符串/正则变换
 * 通过 [markChapterForReader] 在后台线程执行，避免大书打开与搜索跳转时主线程冻结。
 *
 * 0.4.22：所有正则提升为预编译常量。此前 [extractVisibleText] 每调用一次就
 * 新建 3 个 Regex（全书搜索会对每个块元素调用上万次），[markChapterForReader]
 * 每次也重新编译 7 个，Pattern.compile 的开销与 GC 压力都相当可观。
 */
object ReaderHtml {

    /**
     * 0.4.34：阅读页主题样式（纯函数，与 INIT_JS 的 __setTheme 同源）。
     *
     * 三个主题一视同仁：只设置 html/body 的背景与默认继承色，以及链接的兜底色，
     * **不覆盖书内元素显式声明的颜色**。color 是继承属性，元素上的直接声明优先级
     * 高于继承值，所以 html/body 的 `color:... !important` 不会屏蔽书内彩色文字；
     * 旧版夜间额外注入的 `body p,body span,...{color:inherit !important}` 与
     * `body a{color:... !important}` 却会直接命中元素，把朱批、校注、彩色链接一律
     * 抹平，造成「夜」屏蔽文字颜色而「白」/「纸」正常的不一致，故一并移除。
     *
     * 搜索命中高亮是应用自身 UI（非书内内容），夜间单独配深底以免刺眼。
     */
    fun themeCss(bg: String, text: String, link: String, night: Boolean): String =
        "<style id=\"__theme\">" +
            "html,body{background:$bg !important;color:$text !important;}" +
            "body a{color:$link;}" +
            (if (night) "mark.__srch{background:#6b5900 !important;color:#fff !important;}"
            else "mark.__srch{background:#ffd54f !important;color:#111 !important;}") +
            "</style>"

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

    // ————— 预编译正则（0.4.22：不再在热路径里 Pattern.compile）—————
    private val SCRIPT_STYLE_RE = Regex("(?is)<(script|style)[\\s\\S]*?</\\1>")
    private val ANY_TAG_RE = Regex("(?is)</?([a-zA-Z][a-zA-Z0-9]*)[^>]*>")
    private val WS_COLLAPSE_RE = Regex("[ \t\n\u000B\u000C\r\u00A0]+")
    private val ASIDE_OPEN_RE = Regex("(?i)<aside([^>]*)>")
    private val ETYPE_TAG_RE = Regex("(?i)<([a-zA-Z][a-zA-Z0-9]*)((?:\\s[^>]*)?)>")
    private val ETYPE_ATTR_RE = Regex("(?i)\\sepub:type\\s*=\\s*[\"']([^\"']*)[\"']")
    private val WS_SPLIT_RE = Regex("\\s+")

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
            .replace(SCRIPT_STYLE_RE, " ")
            .replace(ANY_TAG_RE) { m ->
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
                // 畸形数字实体（超 Int 范围 / 非十六进制）按空格处理，属预期回退，
                // 不是静默吞异常：搜索扫描里这类脏数据很常见，打日志只会刷屏。
                val code = try {
                    if (m.groupValues[1] == "x") m.groupValues[2].toInt(16) else m.groupValues[2].toInt()
                } catch (_: NumberFormatException) {
                    return@replace " "
                }
                if (code in 0x20..0xFFFF && !Character.isISOControl(code)) code.toChar().toString() else " "
            }
        // 仅塌缩空格类空白（含 NBSP），保留 \n 作为块边界
        return stripped.replace(WS_COLLAPSE_RE, " ").trim()
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
     * 0.4.29：返回一个块内的**全部**命中位置（不重叠）。
     *
     * 古籍 EPUB 常以单个 `<div>` 承载整章正文、段落之间只有 `<br/>`，或由外层
     * `<div>` 包住多个 `<p>`；[BLOCK_PATTERN] 会把它们合成一个很大的块。旧扫描对
     * 每个块只取首个命中（indexOf / Regex.find），整章十几处命中就只报 1 条结果。
     * 现在逐命中生成结果，点击第 N 条按块内序号定位到对应位置。
     *
     * 纯文本快路径直接在 [text] 上忽略大小写查找，索引与 [text] 完全一致，
     * 不会因 lowercase 改变字符串长度而错位。
     */
    fun plainRanges(text: String, qLower: String): List<IntRange> {
        if (qLower.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        var start = 0
        val step = qLower.length
        while (start <= text.length - step) {
            val idx = text.indexOf(qLower, start, ignoreCase = true)
            if (idx < 0) break
            out.add(IntRange(idx, idx + step - 1))
            start = idx + step // 不重叠，与 Regex.findAll 行为一致
        }
        return out
    }

    /** 正则路径的全部命中；跳过空匹配（无法高亮 / 定位，且会产生大量无意义结果）。 */
    fun regexRanges(text: String, re: Regex): List<IntRange> =
        re.findAll(text).mapNotNull { if (it.range.isEmpty()) null else it.range }.toList()

    /**
     * 章节 HTML 注释块标记（data-fnhide）——M3 正文注释不进排版流、弹窗可取。
     * 原 BookActivity.doLoad 的多趟标记逻辑原样迁移为独立纯函数，以便在后台线程执行。
     *
     * ⚠️ 不变量：注入的 data-srch0（搜索命中标记）必须保留，否则 JS 无法定位命中页。
     * 本函数只新增 data-fnhide，绝不删除/改写既有属性，故 data-srch0 必然保留。
     *
     * 0.4.26：EPUB2 裸注释（标记 <a href="#fn1"> 无 noteref，目标 <p id="fn1"> 既非
     * <aside> 也无 epub:type="footnote"）不再隐藏。这类注释本就排在正文流里（多在章末），
     * 隐藏后只能弹注、且跳转时解除隐藏会整章重排错位；现保持可见，点击标记直接翻页跳转。
     * 仅标准注释容器（<aside>、epub:type=footnote|note|endnote|rearnote）才隐藏并弹注。
     * itemPath 参数保留以兼容既有调用点，当前不再参与判定。
     */
    fun markChapterForReader(html: String, itemPath: String): String {
        val h = html.replace(ASIDE_OPEN_RE, "<aside data-fnhide$1>")
        // epub:type 精确值匹配（按空格 split 判断）——防止 "noteref" 含 "note" 子串被误隐藏
        val fnTypes = hashSetOf("footnote", "note", "endnote", "rearnote")
        val sb2 = StringBuilder()
        var last2 = 0
        for (m in ETYPE_TAG_RE.findAll(h)) {
            val attrs = m.groupValues[2]
            val em = ETYPE_ATTR_RE.find(attrs) ?: continue
            val types = em.groupValues[1].trim().lowercase().split(WS_SPLIT_RE)
            if (types.none { it in fnTypes }) continue // noteref / other → 不隐藏
            if (attrs.contains("data-fnhide")) continue
            sb2.append(h, last2, m.range.first)
            sb2.append('<').append(m.groupValues[1]).append(" data-fnhide").append(attrs).append('>')
            last2 = m.range.last + 1
        }
        sb2.append(h.substring(last2))
        // 0.4.26：不再按「内部 # 锚引用的 id」把块级目标标 data-fnhide。
        // 只有上面的 <aside> / epub:type=footnote 系标准注释容器才隐藏并弹注。
        return sb2.toString()
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

    // ————— M3.10：书内 CSS 字体提取与字体栈（纯 JVM，供单元测试） —————

    private val STYLE_BLOCK_RE = Regex("(?is)<style\\b[^>]*>(.*?)</style\\s*>")
    private val STYLE_ATTR_RE = Regex("(?is)\\bstyle\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
    private val FONT_FAMILY_RE = Regex("(?i)font-family\\s*:\\s*([^;}]+)")
    private val IMPORTANT_RE = Regex("(?i)\\s*!important\\s*")
    private val GENERIC_FONTS = setOf(
        "serif", "sans-serif", "monospace", "cursive", "fantasy", "system-ui",
        "inherit", "initial", "unset", "revert", "default", "ui-serif", "ui-sans-serif",
        "ui-monospace", "math", "emoji", "fangsong"
    )
    private val FONT_NAME_RE = Regex("^[\\p{L}\\p{N} ._\\-]+$")

    /**
     * 从章节 HTML（内联 `<style>` 块、`style=` 属性）与外链 CSS 文本中提取书内声明的字体名。
     * 按出现顺序去重，剔除通用族 / 非法字符 / 过长名称，最多 24 个。
     *
     * 用途：自定义字体不含某些生僻字、异体字时，让浏览器按字符逐个回退到书籍 CSS 指定的
     * 专用字体（古籍常见做法），而不是落到同样缺字的系统字体显示豆腐块。
     */
    fun bookFontFamilies(html: String, cssTexts: List<String>): List<String> {
        val sources = ArrayList<String>(cssTexts.size + 4)
        STYLE_BLOCK_RE.findAll(html).forEach { sources += it.groupValues[1] }
        STYLE_ATTR_RE.findAll(html).forEach { m ->
            sources += if (m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.groupValues[2]
        }
        sources.addAll(cssTexts)
        val out = LinkedHashSet<String>()
        for (src in sources) {
            for (decl in FONT_FAMILY_RE.findAll(src)) {
                val body = IMPORTANT_RE.replace(decl.groupValues[1], "")
                for (raw in body.split(',')) {
                    val name = raw.trim().trim('\'', '"').trim()
                    if (name.isEmpty() || name.length > 64) continue
                    if (name.equals("__Custom", ignoreCase = true)) continue
                    if (name.lowercase() in GENERIC_FONTS) continue
                    if (!FONT_NAME_RE.matches(name)) continue
                    out += name
                    if (out.size >= 24) return out.toList()
                }
            }
        }
        return out.toList()
    }

    /** 生成字体栈 CSS 值：custom → 书内 CSS 字体 → 系统字体 → 链尾通用族。
     *  M3.13：[tailFamily] 可配（衬线模式传 `serif`）。通用族恒在链尾，不会截断
     *  前面书内字体的逐字符回退；中间系统字体按尾族选取，避免衬线模式被黑体名
     *  提前命中。不传时保持 M3.10/M3.12 的 `sans-serif` 行为。 */
    fun fontStackCss(custom: String?, bookFonts: List<String>, tailFamily: String = "sans-serif"): String {
        val parts = ArrayList<String>(bookFonts.size + 3)
        if (!custom.isNullOrBlank()) parts += custom
        for (f in bookFonts.take(24)) {
            parts += "'" + f.replace("\\", "\\\\").replace("'", "\\'") + "'"
        }
        parts += if (tailFamily == "serif") "'Noto Serif CJK SC'" else "'Noto Sans CJK SC'"
        parts += tailFamily
        return parts.joinToString(",")
    }

    // ————— M3.11：自定义字体缺字时「回退到 CSS」 —————

    private val FONT_FACE_BLOCK_RE = Regex("(?is)@font-face\\s*\\{[^}]*\\}")
    private val CSS_COMMENT_RE = Regex("(?s)/\\*.*?\\*/")
    private val FONT_FAMILY_DECL_RE = Regex("(?i)(font-family\\s*:\\s*)([^;}]+)")
    private val CSS_INHERIT_RE = Regex("(?i)^(inherit|initial|unset|revert|revert-layer)\\b")

    /**
     * 把 [stack] 插到书内 CSS 每个 `font-family` 使用声明的最前面，其后**原封不动**
     * 保留书内 CSS 的字体链。前置字体不含某字符时，浏览器按字符逐个回退到书内 CSS
     * 原本为该元素指定的字体，而不是被一个全局统一字体栈抹平。
     *
     * @param stack 完整的 CSS font-family 值，如 `'__Custom'` 或
     *   `Georgia,'Noto Serif CJK SC','Source Han Serif SC'`（不含通用族，避免截断书内回退链）。
     *
     * - `@font-face` 块内的 `font-family` 是「字体定义」而非「使用声明」，必须原样保留；
     * - CSS 注释、`inherit`/`initial`/`unset`/`revert`、已含栈首字体名的值一律跳过；
     * - 不改动 CSS 其余字节（`!important`、选择器、媒体查询等保持原样）。
     */
    fun prependFontFamily(css: String, stack: String = "'__Custom'"): String {
        if (css.isBlank() || stack.isBlank()) return css
        val firstName = stack.substringBefore(',').trim().replace("'", "").trim()
        val skipRanges = ArrayList<IntRange>()
        FONT_FACE_BLOCK_RE.findAll(css).forEach { skipRanges += it.range }
        CSS_COMMENT_RE.findAll(css).forEach { skipRanges += it.range }
        val sb = StringBuilder(css.length + 64)
        var cursor = 0
        var patched = false
        for (m in FONT_FAMILY_DECL_RE.findAll(css)) {
            val start = m.range.first
            if (skipRanges.any { start in it }) continue
            val value = m.groupValues[2]
            val trimmed = value.trim()
            if (firstName.isNotEmpty() && trimmed.contains(firstName, ignoreCase = true)) continue
            if (CSS_INHERIT_RE.containsMatchIn(trimmed)) continue
            sb.append(css, cursor, start)
            sb.append(m.groupValues[1]).append(stack).append(',').append(value)
            cursor = m.range.last + 1
            patched = true
        }
        if (!patched) return css
        sb.append(css, cursor, css.length)
        return sb.toString()
    }

    private val STYLE_TAG_RE = Regex("""(?is)(<style\b[^>]*>)(.*?)(</style\s*>)""")
    private val STYLE_ATTR_VAL_RE = Regex("""(?is)(\bstyle\s*=\s*)("[^"]*"|'[^']*')""")

    /**
     * 对章节 HTML 内联的 `<style>` 块与 `style=` 属性同样做 [prependFontFamily] 改写，
     * 与外链 CSS（资源拦截通道）口径完全一致。EPUB 常把字体声明写在章节内联样式里，
     * 只改写外链 CSS 会让这些元素漏掉前置字体。
     *
     * 只改 CSS 文本中的 `font-family` 声明，HTML 其余字节原样保留。
     */
    fun prependFontFamilyInHtml(html: String, stack: String): String {
        if (html.isBlank() || stack.isBlank()) return html
        var out = STYLE_TAG_RE.replace(html) { m ->
            m.groupValues[1] + prependFontFamily(m.groupValues[2], stack) + m.groupValues[3]
        }
        out = STYLE_ATTR_VAL_RE.replace(out) { m ->
            val quoted = m.groupValues[2]
            val q = quoted[0].toString()
            val body = quoted.substring(1, quoted.length - 1)
            m.groupValues[1] + q + prependFontFamily(body, stack) + q
        }
        return out
    }
}
