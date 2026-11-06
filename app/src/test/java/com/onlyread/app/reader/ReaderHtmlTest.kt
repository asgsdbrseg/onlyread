package com.onlyread.app.reader

import org.junit.Assert.*
import org.junit.Test

/**
 * ReaderHtml 纯函数单元测试（JUnit 4，JVM，无 Android 依赖）。
 *
 * 覆盖三类与本次修复直接相关的逻辑：
 *  1) 可见文本抽取 / 命中片段 —— 全文搜索结果的正确性与展示；
 *  2) 章节注释块标记 —— 必须隐藏脚注、保留 data-srch0（搜索跳转定位的前提）；
 *  3) 页码换算公式 —— 与 JS 侧同公式，验证“搜索跳转页码偏移”修复的几何正确性。
 */
class ReaderHtmlTest {

    // ————— 1. 可见文本抽取 —————

    @Test
    fun extractVisibleText_stripsTagsAndDecodesEntities() {
        val html = "Hello <b>brave</b> &amp; <i>world</i> &nbsp; end"
        val text = ReaderHtml.extractVisibleText(html)
        assertEquals("Hello brave & world end", text)
        assertFalse("不应残留标签", text.contains("<"))
    }

    @Test
    fun extractVisibleText_joinsInlineTagsSeamlessly() {
        // 跨 <u>/<span> 的词组应可连续命中
        val html = "<p>阅<u>读</u>软<span>件</span>测试</p>"
        val text = ReaderHtml.extractVisibleText(html)
        assertTrue(text.contains("阅读软件测试"))
    }

    @Test
    fun extractVisibleText_blockTagsBecomeNewlines() {
        val html = "<div>第一章</div><div>第二章</div>"
        val text = ReaderHtml.extractVisibleText(html)
        // 块级标签折叠为空白，但不拼接成“第一章第二章”
        assertFalse(text.contains("第一章第二章"))
        assertTrue(text.contains("第一章"))
        assertTrue(text.contains("第二章"))
    }

    @Test
    fun extractVisibleText_plainTextReturnsAsIs() {
        assertEquals("纯文本无标签", ReaderHtml.extractVisibleText("纯文本无标签"))
    }

    // ————— 2. 命中片段 —————

    @Test
    fun buildSnippet_noEllipsisWhenInsideBounds() {
        val text = "0123456789" // length 10
        val snip = ReaderHtml.buildSnippet(text, 4..4)
        assertEquals("0123456789", snip)
    }

    @Test
    fun buildSnippet_leadingEllipsisWhenMatchNearEnd() {
        val text = "abcdefghijklmnopqrstuvwxyz" // 26 chars
        // match 在索引 24，context=24 → 起点 = max(0, 24-24)=0? 用更小的 context 触发前导省略号
        val snip = ReaderHtml.buildSnippet(text, 24..24, context = 4)
        assertTrue("应在命中前出现前导省略号", snip.startsWith("…"))
        assertTrue(snip.contains("y"))
    }

    @Test
    fun buildSnippet_trailingEllipsisWhenNearStart() {
        val text = "abcdefghijklmnopqrstuvwxyz"
        val snip = ReaderHtml.buildSnippet(text, 1..1, context = 4)
        assertTrue("应在命中后出现尾部省略号", snip.endsWith("…"))
    }

    // ————— 3. 章节注释块标记 —————

    @Test
    fun markChapter_hidesFootnoteAside() {
        val html = "<html><body><p>正文</p><aside epub:type=\"footnote\"><p>注</p></aside></body></html>"
        val marked = ReaderHtml.markChapterForReader(html, "chap1.xhtml")
        assertTrue("脚注 aside 应被标记隐藏", marked.contains("data-fnhide"))
        assertTrue(marked.contains("<aside"))
    }

    @Test
    fun markChapter_preservesSrch0Marker() {
        // 搜索命中块注入的 data-srch0 必须保留，JS 才能定位命中页（页码偏移修复的前提）
        val html = "<html><body><p data-srch0>命中关键词内容</p></body></html>"
        val marked = ReaderHtml.markChapterForReader(html, "chap1.xhtml")
        assertTrue("data-srch0 必须保留", marked.contains("data-srch0"))
    }

    @Test
    fun markChapter_hidesBlockReferencedById() {
        val html = "<html><body><a href=\"#ref1\">1</a><p id=\"ref1\">被引用的回链目标</p></body></html>"
        val marked = ReaderHtml.markChapterForReader(html, "chap1.xhtml")
        assertTrue("被内部锚引用的块应被标记隐藏", marked.contains("data-fnhide"))
        assertTrue(marked.contains("id=\"ref1\""))
    }

    @Test
    fun markChapter_doesNotHideNoteref() {
        // EPUB3 标准 noteref（引用标记，本身含 "note" 子串）不得被误隐藏
        val html = "<html><body><a epub:type=\"noteref\" id=\"n1\" href=\"#fn1\">1</a></body></html>"
        val marked = ReaderHtml.markChapterForReader(html, "chap1.xhtml")
        assertFalse("noteref 引用标记不得被隐藏", marked.contains("data-fnhide"))
    }

    // ————— 4. 页码换算公式（与 JS 同公式）—————

    @Test
    fun pageFromContentLeft_floorFormula() {
        val M = 16.0
        val W = 400.0
        assertEquals(0, ReaderHtml.pageFromContentLeft(M, W, M))          // 左缘=边距 → 第 0 页
        assertEquals(0, ReaderHtml.pageFromContentLeft(M + W - 1, W, M))  // 仍属第 0 页
        assertEquals(1, ReaderHtml.pageFromContentLeft(M + W, W, M))      // 跨过一页宽 → 第 1 页
        assertEquals(2, ReaderHtml.pageFromContentLeft(M + 2 * W, W, M))  // 第 2 页
    }

    @Test
    fun pageFromContentLeft_clampsNegativeToZero() {
        val M = 16.0
        val W = 400.0
        // 内容坐标小于边距 → 不应出现负页码
        assertEquals(0, ReaderHtml.pageFromContentLeft(M - 5.0, W, M))
    }

    @Test
    fun pageFromContentLeft_guardsZeroWidth() {
        assertEquals(0, ReaderHtml.pageFromContentLeft(100.0, 0.0, 16.0))
    }

    // ————— 5. 块扫描迭代序一致性（搜索 elemIndex 映射）—————

    @Test
    fun blockPattern_indexMatchesIterationOrder() {
        val html = "<p>甲</p><div>乙</div><li>丙</li><blockquote>丁</blockquote>"
        val matches = ReaderHtml.BLOCK_PATTERN.findAll(html).toList()
        assertEquals(4, matches.size)
        // 与 BookActivity 扫描同样的迭代序：第 2 个块（index=1）应为 div
        assertEquals("div", matches[1].groupValues[1])
        assertEquals("乙", matches[1].groupValues[3])
    }
}
