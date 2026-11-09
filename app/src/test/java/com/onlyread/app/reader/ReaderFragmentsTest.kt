package com.onlyread.app.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReaderFragments 纯函数单测（0.4.22 从 BookActivity 抽出的无状态工具）。
 * 验证抽出后行为与原先一致：高亮只作用于文本段、弹注提取去脚本、锚点标签配平。
 */
class ReaderFragmentsTest {

    @Test
    fun markSrchText_highlightsOnlyTextSegments() {
        val out = ReaderFragments.markSrchText(
            "甲<b>乙</b>丙", Regex("乙")
        )
        assertEquals("甲<b><mark class=\"__srch\">乙</mark></b>丙", out)
        assertFalse("属性/标签名不得被替换", out.contains("<mark class=\"__srch\">b"))
    }

    @Test
    fun markSrchText_noMatchLeavesHtmlUntouched() {
        val html = "<p class=\"x\">正文</p>"
        assertEquals(html, ReaderFragments.markSrchText(html, Regex("不存在")))
    }

    @Test
    fun extractBodyHtml_stripsScripts() {
        val html = "<html><head><title>T</title></head><body><p>正文</p>" +
            "<script>bad()</script></body></html>"
        assertEquals("<p>正文</p>", ReaderFragments.extractBodyHtml(html))
    }

    @Test
    fun extractBodyHtml_fallsBackToWholeHtml() {
        val html = "<p>无 body 标签</p>"
        assertEquals(html, ReaderFragments.extractBodyHtml(html))
    }

    @Test
    fun extractFragHtml_balancesNestedSameTag() {
        val html = "<div>前</div><div id=\"fn1\">注<div>嵌套</div>尾</div><div>后</div>"
        val out = ReaderFragments.extractFragHtml(html, "fn1")
        assertTrue(out?.startsWith("<div id=\"fn1\">") == true)
        assertTrue("应包含嵌套闭合", out?.contains("<div>嵌套</div>尾</div>") == true)
        assertFalse("不得包含后续兄弟节点", out?.contains("<div>后</div>") == true)
    }

    @Test
    fun extractFragHtml_voidTagReturnsSelf() {
        val html = "<img id=\"pic\" src=\"a.png\">"
        assertEquals("<img id=\"pic\" src=\"a.png\">", ReaderFragments.extractFragHtml(html, "pic"))
    }

    @Test
    fun extractFragHtml_missingIdReturnsNull() {
        assertNull(ReaderFragments.extractFragHtml("<p id=\"a\">x</p>", "b"))
    }

    // ————— 0.4.23：同文件多标题的目录当前项定位 —————

    private fun toc(): List<ReaderFragments.TocItem> = listOf(
        ReaderFragments.TocItem(0, 3, "h1"),
        ReaderFragments.TocItem(1, 3, "h2"),
        ReaderFragments.TocItem(2, 3, "h3"),
        ReaderFragments.TocItem(3, 4, "")
    )

    @Test
    fun currentTocIndex_fragHitWinsOverLastRow() {
        // 旧实现恒返回 2（最后一条）；现应按当前页锚点命中第二条
        assertEquals(1, ReaderFragments.currentTocIndex(toc(), 3, "h2", 2))
    }

    @Test
    fun currentTocIndex_activeOrdWinsWhenNoFrag() {
        assertEquals(0, ReaderFragments.currentTocIndex(toc(), 3, "", 0))
    }

    @Test
    fun currentTocIndex_fallsBackToFirstRowNotLast() {
        assertEquals(0, ReaderFragments.currentTocIndex(toc(), 3, "", -1))
    }

    @Test
    fun currentTocIndex_staleOrdFromOtherSpineIgnored() {
        assertEquals(3, ReaderFragments.currentTocIndex(toc(), 4, "", 0))
    }

    @Test
    fun currentTocIndex_unknownFragFallsThroughToActiveOrd() {
        assertEquals(2, ReaderFragments.currentTocIndex(toc(), 3, "不存在", 2))
    }

    @Test
    fun currentTocIndex_unknownFragAndNoActiveFallsToFirst() {
        assertEquals(0, ReaderFragments.currentTocIndex(toc(), 3, "不存在", -1))
    }

    @Test
    fun currentTocIndex_emptyTocReturnsMinusOne() {
        assertEquals(-1, ReaderFragments.currentTocIndex(emptyList(), 3, "h1", 0))
    }

    // ——— 0.4.24：搜索命中精确锚点 ———

    @Test
    fun anchorFirstMark_marksOnlyTheFirstHit() {
        val html = "甲<mark class=\"__srch\">春</mark>乙<mark class=\"__srch\">秋</mark>"
        val out = ReaderFragments.anchorFirstMark(html)
        assertEquals(
            "甲<mark class=\"__srch\" data-srch-hit>春</mark>乙<mark class=\"__srch\">秋</mark>",
            out
        )
        assertEquals(1, Regex("data-srch-hit").findAll(out).count())
    }

    @Test
    fun anchorFirstMark_noMarkKeepsHtmlIntact() {
        val html = "<p>没有命中词的正文</p>"
        assertEquals(html, ReaderFragments.anchorFirstMark(html))
    }

    @Test
    fun anchorFirstMark_alreadyAnchoredIsIdempotent() {
        val html = "<mark class=\"__srch\" data-srch-hit>春</mark>"
        assertEquals(html, ReaderFragments.anchorFirstMark(html))
    }

    @Test
    fun anchorFirstMark_worksOnOutputOfMarkSrchText() {
        val marked = ReaderFragments.markSrchText("<p>春秋春秋</p>", Regex("春"))
        val out = ReaderFragments.anchorFirstMark(marked)
        assertTrue(out.contains("<mark class=\"__srch\" data-srch-hit>春</mark>"))
        assertEquals(2, Regex("<mark").findAll(out).count())
    }
}
