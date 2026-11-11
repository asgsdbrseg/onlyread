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

    // ——— 0.4.29：按块内序号选精确锚点 ———

    @Test
    fun anchorMarkAt_marksNthHitOnly() {
        val html = "甲<mark class=\"__srch\">一</mark>乙<mark class=\"__srch\">二</mark>" +
                "丙<mark class=\"__srch\">三</mark>"
        val out = ReaderFragments.anchorMarkAt(html, 2)
        assertEquals(
            "甲<mark class=\"__srch\">一</mark>乙<mark class=\"__srch\">二</mark>" +
                "丙<mark class=\"__srch\" data-srch-hit>三</mark>", out
        )
        assertEquals(1, Regex("data-srch-hit").findAll(out).count())
    }

    @Test
    fun anchorMarkAt_ordinalBeyondMarksFallsBackToLast() {
        val html = "<mark class=\"__srch\">一</mark><mark class=\"__srch\">二</mark>"
        val out = ReaderFragments.anchorMarkAt(html, 9)
        assertTrue(
            "序号越界应回退到最后一个 mark",
            out.endsWith("<mark class=\"__srch\" data-srch-hit>二</mark>")
        )
        assertEquals(1, Regex("data-srch-hit").findAll(out).count())
    }

    @Test
    fun anchorMarkAt_zeroEquivalentToAnchorFirstMark() {
        val html = "<mark class=\"__srch\">一</mark><mark class=\"__srch\">二</mark>"
        assertEquals(ReaderFragments.anchorFirstMark(html), ReaderFragments.anchorMarkAt(html, 0))
    }

    @Test
    fun anchorMarkAt_noMarkKeepsHtmlIntact() {
        val html = "<p>没有命中词的正文</p>"
        assertEquals(html, ReaderFragments.anchorMarkAt(html, 5))
    }

    @Test
    fun anchorMarkAt_worksOnOutputOfMarkSrchText() {
        val marked = ReaderFragments.markSrchText("<p>曹門曹門曹門</p>", Regex("曹門"))
        val out = ReaderFragments.anchorMarkAt(marked, 1)
        assertEquals(3, Regex("<mark").findAll(out).count())
        assertEquals(1, Regex("data-srch-hit").findAll(out).count())
        assertTrue(out.contains("曹門</mark><mark class=\"__srch\" data-srch-hit>曹門"))
    }

    // ——— 0.4.30：跨标签命中高亮（参考 entry.js） ———

    @Test
    fun markSrchText_highlightsAcrossInlineTags() {
        // 旧实现按文本段单独跑正则，跨 <u> 的「曹門」无法命中 → 跳转后不高亮
        val out = ReaderFragments.markSrchText("甲<u>曹</u><u>門</u>乙", Regex("曹門"))
        assertEquals(
            "甲<u><mark class=\"__srch\">曹</mark></u><u><mark class=\"__srch\">門</mark></u>乙",
            out
        )
    }

    @Test
    fun markSrchText_crossesMultipleInlineTagsInOneHit() {
        val out = ReaderFragments.markSrchText("<u>南</u><u>朝</u><u>宋</u>", Regex("南朝宋"))
        assertEquals(3, Regex("<mark").findAll(out).count())
        assertTrue(out.contains("<mark class=\"__srch\">南</mark>"))
        assertTrue(out.contains("<mark class=\"__srch\">朝</mark>"))
        assertTrue(out.contains("<mark class=\"__srch\">宋</mark>"))
    }

    @Test
    fun markSrchText_doesNotCrossBlockLevelTags() {
        // 块级标签按空白分隔，与 extractVisibleText 同口径，不误把跨段文字拼成命中
        val out = ReaderFragments.markSrchText("<p>曹</p><p>門</p>", Regex("曹門"))
        assertEquals(0, Regex("<mark").findAll(out).count())
    }

    @Test
    fun markSrchText_ordinalCountsHitsNotMarks() {
        // 一处跨标签命中产生 2 个 mark，但只对应 1 个 data-srch-ord
        val out = ReaderFragments.markSrchText(
            "<u>曹</u><u>門</u>", Regex("曹門"), withOrdinal = true
        )
        assertEquals(2, Regex("<mark").findAll(out).count())
        assertEquals(1, Regex("data-srch-ord=\"0\"").findAll(out).count())
        assertFalse(out.contains("data-srch-ord=\"1\""))
    }

    @Test
    fun markSrchText_ordinalNumbersEachHit() {
        // 前一处连续命中(1 mark) + 后一处跨标签命中(2 mark)：ord 按命中数递增
        val out = ReaderFragments.markSrchText(
            "曹門<u>曹</u><u>門</u>", Regex("曹門"), withOrdinal = true
        )
        assertEquals(3, Regex("<mark").findAll(out).count())
        assertEquals(1, Regex("data-srch-ord=\"0\"").findAll(out).count())
        assertEquals(1, Regex("data-srch-ord=\"1\"").findAll(out).count())
    }

    @Test
    fun anchorMarkAt_usesOrdinalAcrossSplitMarks() {
        // 第 0 处命中连续(1 mark, ord=0)，第 1 处命中跨标签(2 mark, ord=1)。
        // 应标在 ord=1 的首 mark，而不是「第 1 个 mark」或「最后一个 mark」
        val inner = "<mark class=\"__srch\" data-srch-ord=\"0\">曹門</mark>" +
                "<mark class=\"__srch\" data-srch-ord=\"1\">曹</mark>" +
                "<b><mark class=\"__srch\">門</mark></b>"
        val out = ReaderFragments.anchorMarkAt(inner, 1)
        assertEquals(1, Regex("data-srch-hit").findAll(out).count())
        assertTrue(out.contains("<mark class=\"__srch\" data-srch-hit data-srch-ord=\"1\">曹</mark>"))
        assertFalse(out.contains("data-srch-hit data-srch-ord=\"0\""))
        assertFalse(out.contains("<mark class=\"__srch\" data-srch-hit>門"))
    }

    @Test
    fun anchorMarkAt_endToEndOnCrossTagMarkSrchText() {
        val inner = "甲曹門乙<u>曹</u><u>門</u>丙"
        val marked = ReaderFragments.markSrchText(inner, Regex("曹門"), withOrdinal = true)
        val out = ReaderFragments.anchorMarkAt(marked, 1)
        assertEquals(1, Regex("data-srch-hit").findAll(out).count())
        assertTrue(out.contains("data-srch-hit data-srch-ord=\"1\">曹</mark>"))
    }
}
