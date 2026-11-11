package com.onlyread.app.reader

import com.onlyread.app.epub.EpubArchive
import com.onlyread.app.util.Logx
import com.onlyread.app.util.ZipPaths

/**
 * 从 BookActivity 抽出的无状态 HTML / CSS 工具（0.4.22 渐进拆分 God Class）。
 *
 * BookActivity 承担分页状态、WebView、浮层与线程编排；本对象只做纯字符串变换与
 * zip 文本读取，无 Activity/Context 依赖，可在 JVM 单测中直接验证。
 * 正则统一预编译；动态标签正则按标签名缓存，避免每次点击弹注都重新 compile。
 */
object ReaderFragments {

    private const val TAG = "ReaderFragments"

    private val SEG_RE = Regex("<[^>]*>|[^<]+")
    private val BODY_RE = Regex("(?is)<body[^>]*>(.*?)</body>")
    private val SCRIPT_RE = Regex("(?is)<script[\\s\\S]*?</script>")
    private val LINK_TAG_RE = Regex("(?is)<link\\b[^>]*>")
    private val REL_RE = Regex("(?i)\\brel\\s*=\\s*[\"']?([^\"'>\\s]+)")
    private val HREF_RE = Regex("(?i)\\bhref\\s*=\\s*[\"']?([^\"'>\\s]+)")
    private val TAG_PAIR_CACHE = HashMap<String, Pair<Regex, Regex>>()
    private val VOID_TAGS = setOf("img", "br", "hr", "meta", "link", "input", "source")

    // ——— 0.4.30：跨标签命中高亮（参考 entry.js 的 TreeWalker + chars 位置映射） ———
    private val TAG_NAME_RE = Regex("^</?([a-zA-Z][a-zA-Z0-9]*)")
    private val MARK_TAG_RE = Regex("<mark class=\"__srch\"([^>]*)>")
    private val SRCH_ORD_RE = Regex("data-srch-ord=\"(\\d+)\"")
    private const val MARK_PREFIX = "<mark class=\"__srch\""
    private const val MARK_OPEN = "<mark class=\"__srch\">"
    private const val MARK_CLOSE = "</mark>"
    /** 病态正则（如 `.`）安全阀：单次注入最多生成的 mark 数，与 entry.js 的 MAX_MARKS 同源。 */
    private const val MAX_MARKS = 2000
    /** 命中区间落在某个原始文本段内的一段连续字符；ord 仅赋给该命中的首段。 */
    private data class Piece(val pi: Int, val start: Int, val end: Int, val ord: Int?)

    /**
     * 块内命中词高亮（0.4.30：**跨标签感知**，参考 entry.js 的 `highlightInEntry`）。
     *
     * 旧实现用 [SEG_RE] 把 inner 切成「标签段 / 文本段」，对**每个文本段单独**跑正则替换。
     * 古籍 / MDX 常把词组拆进相邻行内标签，如 `<u>曹</u><u>門</u>`、`南朝</u><u>宋`：
     * 扫描侧 `extractVisibleText` 会把行内标签无缝拼成「曹門」并命中，但注入侧正则在
     * 单段里看不到跨段的词 → 不生成 `<mark>`，跳转后既不高亮、也没有精确锚点。
     *
     * 现按参考文件思路：把所有文本段拼成一份与 `extractVisibleText` 同口径的纯文本
     * （行内标签不占位、块级标签/空白压成单个空格、首尾 trim），同时用 `ownerSeg/ownerOff`
     * 记录纯文本第 k 个字符落在哪个原始片段的哪个偏移；在这份纯文本上跑正则得到命中区间，
     * 再把每个区间按片段切成若干 [Piece]，从后往前在各自文本段内插入 `<mark>`——
     * 标签结构保持合法，跨标签命中被拆成多个 `<mark>` 分别包裹。
     *
     * [withOrdinal] 为 true 时（仅搜索跳转注入使用），给**每处命中的首个** `<mark>` 加
     * `data-srch-ord="N"`，供 [anchorMarkAt] 按「命中序号」而非「mark 序号」定位：
     * 一处跨标签命中会产生多个 mark，若仍按第 N 个 mark 计数就会错位。
     *
     * 默认关闭以保持既有输出（弹注内高亮、旧单测）；HTML 实体解码差异属既有边界，
     * 不影响汉字词被行内标签拆开这一主要场景。
     */
    fun markSrchText(inner: String, re: Regex, withOrdinal: Boolean = false): String {
        val parts = SEG_RE.findAll(inner).toList()
        if (parts.isEmpty()) return inner
        // 1) 拼接与 extractVisibleText 同口径的纯文本，并建立 text[k] → 原始片段位置的映射
        val joined = StringBuilder()
        val ownerSeg = ArrayList<Int>()   // -1 表示合成空格，不落地到真实文本段
        val ownerOff = ArrayList<Int>()
        var pendingSpace = false
        for ((pi, p) in parts.withIndex()) {
            val t = p.value
            if (t.startsWith("<")) {
                val name = TAG_NAME_RE.find(t)?.groupValues?.get(1)?.lowercase() ?: ""
                // 行内标签无缝拼接；块级标签当作一个空白分隔（与 extractVisibleText 的 \n 同效果）
                if (name.isEmpty() || !ReaderHtml.isInlineTag(name)) {
                    if (!pendingSpace && joined.isNotEmpty()) {
                        joined.append(' '); ownerSeg.add(-1); ownerOff.add(-1); pendingSpace = true
                    }
                }
                continue
            }
            for (k in t.indices) {
                val c = t[k]
                if (c.isWhitespace() || c == '\u00A0') {
                    if (!pendingSpace && joined.isNotEmpty()) {
                        joined.append(' '); ownerSeg.add(-1); ownerOff.add(-1); pendingSpace = true
                    }
                } else {
                    joined.append(c); ownerSeg.add(pi); ownerOff.add(k); pendingSpace = false
                }
            }
        }
        // 首尾 trim（与 extractVisibleText.trim() 一致）
        while (joined.isNotEmpty() && joined[0] == ' ') {
            joined.deleteCharAt(0); ownerSeg.removeAt(0); ownerOff.removeAt(0)
        }
        while (joined.isNotEmpty() && joined[joined.length - 1] == ' ') {
            val last = joined.length - 1
            joined.deleteCharAt(last); ownerSeg.removeAt(last); ownerOff.removeAt(last)
        }
        val text = joined.toString()
        if (text.isEmpty()) return inner
        // 2) 在拼接文本上跑正则，跳过空匹配（与 regexRanges 口径一致）
        val hits = re.findAll(text).map { it.range }.filter { !it.isEmpty() }.take(MAX_MARKS).toList()
        if (hits.isEmpty()) return inner
        // 3) 每个命中区间按原始片段切成 Piece；跨标签 → 多个 Piece，首段带命中序号
        val pieces = ArrayList<Piece>()
        for ((ord, r) in hits.withIndex()) {
            var first = true
            var k = r.first
            while (k <= r.last) {
                val pi = ownerSeg[k]
                if (pi < 0) { k++; continue }   // 合成空格不落地
                val start = ownerOff[k]
                var end = start + 1
                var j = k + 1
                while (j <= r.last && ownerSeg[j] == pi && ownerOff[j] == end) { end++; j++ }
                pieces.add(Piece(pi, start, end, if (first) ord else null))
                first = false
                k = j
            }
        }
        if (pieces.isEmpty()) return inner
        // 4) 从后往前插入：先插 end 再插 start，保证前面的插入不影响后面已插入内容的偏移
        val out = parts.map { it.value }.toMutableList()
        for ((pi, ps) in pieces.groupBy { it.pi }) {
            val sb = StringBuilder(out[pi])
            for (pc in ps.sortedByDescending { it.start }) {
                val open = if (withOrdinal && pc.ord != null)
                    "<mark class=\"__srch\" data-srch-ord=\"${pc.ord}\">" else MARK_OPEN
                sb.insert(pc.end, MARK_CLOSE)
                sb.insert(pc.start, open)
            }
            out[pi] = sb.toString()
        }
        return out.joinToString("")
    }

    /**
     * 把第 [ordinal] 处命中的首个 `<mark>` 标成精确锚点 `data-srch-hit`，
     * 供 INIT_JS 的 `__hitRect()` / `__pageOfHit()` 按命中词真实所在列定位。
     *
     * 0.4.24：此前把 `data-srch0` 加在整块标签上用 `getBoundingClientRect()` 换算页码，
     * CSS 多列下跨列块是 fragmented element，该 API 返回所有列碎片**并集**，`left` 恒取
     * 最左碎片（块起始列）——命中词在块中部时落地页大幅提前；改为按命中词内联 mark 的
     * 首个 `getClientRects()` 定位。
     *
     * 0.4.29：一个块可能含多处命中，按结果在块内的序号选对应 mark，点第 N 条跳到第 N 处。
     *
     * 0.4.30：跨标签命中会拆成**多个** `<mark>`，若仍按「第 N 个 mark」计数就会错位。
     * 现优先按 [markSrchText] 打在每处命中首 mark 上的 `data-srch-ord="N"` 精确定位；
     * 无序号（弹注内高亮 / 旧格式）时回退「第 N 个 mark」，序号越界再回退最后一个 mark，
     * 保证始终有锚点。已带 `data-srch-hit` 则原样返回（幂等）。
     */
    fun anchorMarkAt(inner: String, ordinal: Int): String {
        val tags = MARK_TAG_RE.findAll(inner).toList()
        if (tags.isEmpty()) return inner
        val target = tags.firstOrNull { m ->
            SRCH_ORD_RE.find(m.groupValues[1])?.groupValues?.get(1)?.toIntOrNull() == ordinal
        } ?: tags.getOrNull(ordinal)      // 无序号：按第 N 个 mark（兼容旧格式 / 弹注）
          ?: tags.last()                  // 序号越界：回退最后一个 mark
        if (target.groupValues[1].contains("data-srch-hit")) return inner // 幂等
        // 在 class="__srch" 之后、其余属性之前插入 data-srch-hit
        val at = target.range.first + MARK_PREFIX.length
        return inner.substring(0, at) + " data-srch-hit" + inner.substring(at)
    }

    /** 首个命中锚点（等价于 [anchorMarkAt] 的序号 0，保留给既有调用与单测）。 */
    fun anchorFirstMark(inner: String): String = anchorMarkAt(inner, 0)

    /** 无锚点的跨文件链接：取目标文件正文作为弹注内容（仍不跳章） */
    fun extractBodyHtml(html: String): String =
        BODY_RE.find(html)?.groupValues?.get(1)?.replace(SCRIPT_RE, "") ?: html

    /** 提取 id=frag 的元素（同名标签配平到闭合，上限 20KB 防失控） */
    fun extractFragHtml(html: String, frag: String): String? {
        // 锚 id 由用户输入/书内 href 决定，无法预编译为常量；每次点击只编译一次
        val m = Regex(
            "(?is)<([a-zA-Z][a-zA-Z0-9]*)\\b[^>]*\\bid\\s*=\\s*([\"']?)" +
                Regex.escape(frag) + "\\2[^>]*>"
        ).find(html) ?: return null
        val tag = m.groupValues[1].lowercase()
        if (tag in VOID_TAGS) return m.value
        val (openRe, closeRe) = synchronized(TAG_PAIR_CACHE) {
            TAG_PAIR_CACHE.getOrPut(tag) {
                Regex("(?is)<$tag[\\s>]") to Regex("(?is)</$tag\\s*>")
            }
        }
        var depth = 0
        var i = m.range.last + 1
        var end = -1
        while (i < html.length) {
            val no = openRe.find(html, i)
            val nc = closeRe.find(html, i) ?: break
            if (no != null && no.range.first < nc.range.first) {
                depth++
                i = no.range.last + 1
            } else {
                if (depth == 0) {
                    end = nc.range.last + 1 // substring 右开区间，+1 才含闭合 '>'
                    break
                }
                depth--
                i = nc.range.last + 1
            }
        }
        return if (end < 0) html.substring(m.range.first, minOf(html.length, m.range.last + 20000))
        else html.substring(m.range.first, end)
    }

    /**
     * M3.10：后台读取章节外链 CSS 文本（最多 8 个），供 ReaderHtml.bookFontFamilies
     * 提取书内字体名。书籍自带 CSS 由 WebView 经虚拟资源通道加载；这里额外读一次只为
     * 拿到声明过的字体族名，读不到时记录日志并跳过，不影响渲染。
     */
    /** 0.4.23：目录当前项定位所需的轻量条目（避免纯函数依赖 data 层 TocRow）。 */
    data class TocItem(val ord: Int, val spineIndex: Int, val frag: String)

    /**
     * 0.4.23：同一 html 内含多个标题时，选择目录应高亮/滚动到的当前项。
     *
     * 旧实现无条件取 `spineIndex` 匹配的最后一条，导致无论读到哪一节，打开目录
     * 都只定位到该文件的最后一个标题。现按以下优先级：
     * ① 当前页锚点 frag 精确命中 → ② 最近一次目录跳转的 ord → ③ 该章第一条 → ④ -1。
     */
    fun currentTocIndex(
        toc: List<TocItem>,
        spineIndex: Int,
        curFrag: String,
        activeOrd: Int
    ): Int {
        if (toc.isEmpty()) return -1
        if (curFrag.isNotEmpty()) {
            val i = toc.indexOfFirst { it.spineIndex == spineIndex && it.frag == curFrag }
            if (i >= 0) return i
        }
        if (activeOrd >= 0) {
            val i = toc.indexOfFirst { it.ord == activeOrd && it.spineIndex == spineIndex }
            if (i >= 0) return i
        }
        return toc.indexOfFirst { it.spineIndex == spineIndex }
    }

    fun linkedCssTexts(arch: EpubArchive, itemPath: String, html: String): List<String> {
        if (!html.contains("<link", ignoreCase = true)) return emptyList()
        val dir = itemPath.substringBeforeLast('/', "")
        val out = ArrayList<String>()
        for (tag in LINK_TAG_RE.findAll(html)) {
            if (out.size >= 8) break
            val t = tag.value
            if ("stylesheet" !in (REL_RE.find(t)?.groupValues?.get(1)?.lowercase() ?: "")) continue
            val href = HREF_RE.find(t)?.groupValues?.get(1) ?: continue
            if (href.startsWith("http:", true) || href.startsWith("https:", true)) continue
            val path = ZipPaths.join(dir, ZipPaths.decode(href.substringBefore('#')))
            val bytes = try {
                arch.entryBytes(path)
            } catch (e: Exception) {
                Logx.w(TAG, "外链 CSS 读取失败: $path", e)
                null
            } ?: continue
            out += String(bytes, ZipPaths.detectCharset(bytes))
        }
        return out
    }
}
