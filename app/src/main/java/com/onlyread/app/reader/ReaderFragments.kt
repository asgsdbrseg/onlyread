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

    /** 块内文本段高亮：仅在标签间文本里替换，避免命中串落在属性/标签内造成结构破坏 */
    fun markSrchText(inner: String, re: Regex): String =
        SEG_RE.replace(inner) { seg ->
            val t = seg.value
            if (t.startsWith("<")) t
            else re.replace(t) { mm -> "<mark class=\"__srch\">${mm.value}</mark>" }
        }

    private const val SRCH_MARK = "<mark class=\"__srch\">"
    private const val SRCH_MARK_HIT = "<mark class=\"__srch\" data-srch-hit>"

    /**
     * 0.4.24：把首个命中 `<mark>` 标成精确锚点，供 INIT_JS 的 `__pageOfHit()` 定位。
     *
     * 搜索注入此前把 `data-srch0` 加在整块标签上，再用 `getBoundingClientRect()` 换算页码。
     * CSS 多列下跨列块是 fragmented element，该 API 返回所有列碎片的**并集**，`left` 恒取
     * 最左碎片（块起始列）——命中词在块中部时落地页大幅提前，且字体就绪后的 `__reanchor`
     * 用同一锚反复把页面拉回块起始列。长章节（>50 页）里不分段长块跨列多，偏移最明显。
     * 改为按命中词内联 mark 的首个 `getClientRects()` 定位，才是命中词真实所在列。
     */
    fun anchorFirstMark(inner: String): String = inner.replaceFirst(SRCH_MARK, SRCH_MARK_HIT)

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
