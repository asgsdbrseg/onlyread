package com.onlyread.app.reader

import org.junit.Assert.*
import org.junit.Test

/**
 * ReaderHtml 纯函数单元测试（JUnit 4，JVM，无 Android 依赖）。
 *
 * 覆盖三类与本次修复直接相关的逻辑：
 *  1) 可见文本抽取 / 命中片段 —— 全文搜索结果的正确性与展示；
 *  2) 章节注释块标记 —— 只隐藏标准注释容器（aside / epub:type=footnote 系）、
 *     保留 EPUB2 裸注释可见、保留 data-srch0（搜索跳转定位的前提）；
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
    fun markChapter_keepsPlainEpub2NoteVisible() {
        // 0.4.26：EPUB2 裸注释（标记无 noteref、目标无 epub:type / 非 aside）保持可见，
        // 点击标记由 __goFrag 直接翻页跳转，不再隐藏后弹注。
        val html = "<html><body><a href=\"#fn1\">1</a><p id=\"fn1\">注释内容</p></body></html>"
        val marked = ReaderHtml.markChapterForReader(html, "chap1.xhtml")
        assertFalse("EPUB2 裸注释目标不得被隐藏", marked.contains("data-fnhide"))
        assertTrue("目标 id 必须保留以便直接跳转定位", marked.contains("id=\"fn1\""))
    }

    @Test
    fun markChapter_stillHidesStandardFootnoteContainers() {
        // 标准注释容器（aside / epub:type=footnote 系）仍隐藏并弹注，不受 0.4.26 影响
        val html = "<html><body><p>正文</p>" +
            "<aside id=\"fn2\"><p>注A</p></aside>" +
            "<div epub:type=\"footnote\" id=\"fn3\">注B</div></body></html>"
        val marked = ReaderHtml.markChapterForReader(html, "chap1.xhtml")
        assertEquals(
            "aside 与 epub:type=footnote 容器应各带一个 data-fnhide",
            2, Regex("data-fnhide").findAll(marked).count()
        )
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

    // ————— 6. 书内 CSS 字体提取与字体栈（M3.10 字体缺字回退） —————

    @Test
    fun bookFontFamilies_extractsFromStyleBlockAndAttr() {
        val html = """
            <html><head><style>
              p{font-family:"古籍宋", 'Kaiti SC', serif;}
              .rare{font-family: 異體字體 !important;}
            </style></head>
            <body><p style="font-family:'方正古体', sans-serif">正文</p></body></html>
        """.trimIndent()
        val fonts = ReaderHtml.bookFontFamilies(html, emptyList())
        assertTrue(fonts.contains("古籍宋"))
        assertTrue(fonts.contains("Kaiti SC"))
        assertTrue(fonts.contains("異體字體"))
        assertTrue(fonts.contains("方正古体"))
        assertFalse("通用族不进回退链", fonts.contains("serif"))
        assertFalse(fonts.contains("sans-serif"))
    }

    @Test
    fun bookFontFamilies_readsExternalCssAndCapsAt24() {
        val css = "body{font-family:'外部字体',serif;}"
        val many = (1..30).map { "字$it" }
        val css2 = "p{font-family:${many.joinToString(",")};}"
        val fonts = ReaderHtml.bookFontFamilies("<p></p>", listOf(css, css2))
        assertEquals("外部 CSS 字体名应被提取", "外部字体", fonts.first())
        assertEquals("最多 24 个", 24, fonts.size)
    }

    @Test
    fun bookFontFamilies_skipsCustomAndGeneric() {
        val html = "<style>p{font-family:'__Custom', 正常字体, serif;}</style>"
        assertEquals(listOf("正常字体"), ReaderHtml.bookFontFamilies(html, emptyList()))
    }

    @Test
    fun bookFontFamilies_filtersInjectionCharacters() {
        val html = "<style>p{font-family:'a}body{color:red', 正常字体;}</style>"
        val fonts = ReaderHtml.bookFontFamilies(html, emptyList())
        assertTrue(fonts.none { it.contains(";") || it.contains("}") || it.contains("{") })
    }

    @Test
    fun fontStackCss_ordersCustomBookFontsThenSystem() {
        val stack = ReaderHtml.fontStackCss("'__Custom'", listOf("古籍宋", "Kaiti SC"))
        assertEquals("'__Custom','古籍宋','Kaiti SC','Noto Sans CJK SC',sans-serif", stack)
    }

    @Test
    fun fontStackCss_withoutCustomStillHasFallback() {
        assertEquals("'Noto Sans CJK SC',sans-serif", ReaderHtml.fontStackCss(null, emptyList()))
    }

    // ————— 6b. 链尾通用族按字族区分（M3.13：衬线不得退化成黑体） —————

    @Test
    fun fontStackCss_serifTailUsesSerifGenericFamily() {
        val stack = ReaderHtml.fontStackCss(
            "Georgia,'Noto Serif CJK SC'", listOf("古籍宋"), "serif"
        )
        assertEquals(
            "Georgia,'Noto Serif CJK SC','古籍宋','Noto Serif CJK SC',serif",
            stack
        )
        assertTrue("链尾必须是 serif", stack.endsWith(",serif"))
        assertFalse("衬线栈中间不得出现黑体字体名", stack.contains("Noto Sans CJK SC"))
    }

    @Test
    fun fontStackCss_genericFamilyOnlyAtTail() {
        val stack = ReaderHtml.fontStackCss("'__Custom'", listOf("古籍宋", "Kaiti SC"), "serif")
        // 通用族只能出现在末尾，否则会截断前面书内字体的逐字符回退
        assertEquals(1, stack.split(",").count { it.trim() == "serif" })
        assertTrue(stack.endsWith(",serif"))
        assertTrue("书内字体仍在通用族之前", stack.indexOf("古籍宋") < stack.lastIndexOf("serif"))
    }

    @Test
    fun fontStackCss_defaultTailStaysSansSerif() {
        // 不传尾族时保持 M3.10/M3.12 行为，custom / 黑体不受影响
        assertEquals(
            "'__Custom','古籍宋','Noto Sans CJK SC',sans-serif",
            ReaderHtml.fontStackCss("'__Custom'", listOf("古籍宋"))
        )
    }

    // ————— 7. 书内 CSS 字体链前置（M3.11：自定义字体缺字回退到 CSS） —————

    @Test
    fun prependFontFamily_keepsBookFontChain() {
        val css = "p{font-family:'古籍宋',serif;}"
        assertEquals(
            "p{font-family:'__Custom','古籍宋',serif;}",
            ReaderHtml.prependFontFamily(css)
        )
    }

    @Test
    fun prependFontFamily_skipsFontFaceDefinition() {
        val css = "@font-face{font-family:'古籍宋';src:url('a.ttf');}" +
                "p{font-family:'古籍宋',serif;}"
        val out = ReaderHtml.prependFontFamily(css)
        assertTrue(
            "@font-face 内的字体定义不得被改写",
            out.startsWith("@font-face{font-family:'古籍宋';")
        )
        assertTrue(out.contains("p{font-family:'__Custom','古籍宋',serif;}"))
    }

    @Test
    fun prependFontFamily_skipsAlreadyCustomAndInherit() {
        val css = "p{font-family:'__Custom',serif;}q{font-family:inherit;}"
        assertEquals(css, ReaderHtml.prependFontFamily(css))
    }

    @Test
    fun prependFontFamily_preservesImportantAndOtherDeclarations() {
        val css = "body{color:red;font-family:宋体,serif !important;}"
        assertEquals(
            "body{color:red;font-family:'__Custom',宋体,serif !important;}",
            ReaderHtml.prependFontFamily(css)
        )
    }

    @Test
    fun prependFontFamily_ignoresCommentsAndNoDeclaration() {
        val css = "body{color:red;}/* font-family: 宋体; */"
        assertEquals(css, ReaderHtml.prependFontFamily(css))
    }

    @Test
    fun prependFontFamily_patchesMultipleRulesInOrder() {
        val css = "h1{font-family:KaiTi,serif;}p{font-family:FangSong,serif;}" +
                "@font-face{font-family:'Embed';src:url('e.ttf');}"
        val out = ReaderHtml.prependFontFamily(css)
        assertEquals(
            "h1{font-family:'__Custom',KaiTi,serif;}" +
                    "p{font-family:'__Custom',FangSong,serif;}" +
                    "@font-face{font-family:'Embed';src:url('e.ttf');}",
            out
        )
    }

    // ————— 8. M3.12：字体栈泛化（衬线/黑体与自定义字体同机制）与内联 CSS 改写 —————

    @Test
    fun prependFontFamily_acceptsCompositeStack() {
        val css = "p{font-family:'古籍宋',serif;}"
        val stack = "Georgia,'Noto Serif CJK SC','Source Han Serif SC'"
        assertEquals(
            "p{font-family:Georgia,'Noto Serif CJK SC','Source Han Serif SC','古籍宋',serif;}",
            ReaderHtml.prependFontFamily(css, stack)
        )
    }

    @Test
    fun prependFontFamily_compositeStackSkipsWhenFirstFontAlreadyPresent() {
        val css = "p{font-family:Georgia,宋体,serif;}"
        val stack = "Georgia,'Noto Serif CJK SC'"
        assertEquals(css, ReaderHtml.prependFontFamily(css, stack))
    }

    @Test
    fun prependFontFamilyInHtml_patchesStyleBlockAndAttribute() {
        val html = "<html><head><style>p{font-family:宋体,serif;}</style></head>" +
                "<body><p style=\"font-family:'古籍宋',serif\">正文</p></body></html>"
        val out = ReaderHtml.prependFontFamilyInHtml(html, "'__Custom'")
        assertTrue(out.contains("p{font-family:'__Custom',宋体,serif;}"))
        assertTrue(out.contains("style=\"font-family:'__Custom','古籍宋',serif\""))
        assertTrue("正文不得丢失", out.contains("正文"))
    }

    @Test
    fun prependFontFamilyInHtml_leavesNonFontStylesAndStructure() {
        val html = "<p style=\"color:red;margin:0\">正文</p><img src=\"a.png\">"
        assertEquals(html, ReaderHtml.prependFontFamilyInHtml(html, "'__Custom'"))
    }

    @Test
    fun prependFontFamilyInHtml_blankStackIsNoop() {
        val html = "<style>p{font-family:宋体;}</style>"
        assertEquals(html, ReaderHtml.prependFontFamilyInHtml(html, ""))
    }

    // ——— 0.4.29：单块多命中（古籍常用一个 <div> 承载整章正文）———

    /** 复现用户反馈：《姑妄言》第二十一卷整章一个 div、14 处「曹門」，旧逻辑只报 1 条。 */
    @Test
    fun plainRanges_reportsEveryHitInOneBlock() {
        val para = listOf(
            "有事則登牌守禦。三月二十三日未時。賊七騎飛奔曹門。貼偽告示二張於柵上。",
            "王知縣半夜遣人召李光聖為石所總社。統社兵各照汛地防守曹門至北門。",
            "磚石不能擊者。擊以柴加烘藥下燒之。自曹門至北門。環垣十餘里。",
            "俱縛手斬擲蓮花池。城在曹門北心字樓下掘一巨洞。",
            "每日人給麥一升。自曹門至北門十餘里。城凡鑿三十六處。",
            "闖賊復回聞李蓁老營打糧。惟曹門外只二十里。",
            "城中乏糧。豎大白旗於曹門上。大書。汴梁豪傑願從吾游者立此旗下。",
            "週圍俱步賊。每夜發喊鳴更。曹門將官夜刎賊營。",
            "馬賊俱在大堤上。曹門將官夜刎賊營。被賊斷雙手。",
            "曹門外南北隅有葦坡數十頃。",
            "賊於曹門外築高臺。",
            "守曹門者晝夜不懈。",
            "曹門至北門一帶俱有賊騎。",
            "是夜復攻曹門。"
        )
        val html = "<div class=\"chapter\">" + para.joinToString("") { "<p>$it</p>" } + "</div>"
        // 外层 div 会吞掉内部所有 <p>，整章只算 1 个块——这正是旧版只报 1 条的原因
        assertEquals("整章应被合成单个块", 1, ReaderHtml.BLOCK_PATTERN.findAll(html).count())
        val block = checkNotNull(ReaderHtml.BLOCK_PATTERN.find(html))
        val text = ReaderHtml.extractVisibleText(block.groupValues[3])
        val ranges = ReaderHtml.plainRanges(text, "曹門")
        assertEquals("单块内 14 处命中应全部返回", 14, ranges.size)
        for (i in ranges.indices) {
            if (i > 0) assertTrue("命中位置应递增", ranges[i].first > ranges[i - 1].last)
            assertTrue("每条片段都应包含命中词", ReaderHtml.buildSnippet(text, ranges[i]).contains("曹門"))
        }
        assertNotEquals(
            "第 1 条与第 14 条片段不应相同",
            ReaderHtml.buildSnippet(text, ranges[0]), ReaderHtml.buildSnippet(text, ranges[13])
        )
    }

    @Test
    fun plainRanges_noOverlapAndCaseInsensitive() {
        val ranges = ReaderHtml.plainRanges("aaAaaa", "aa")
        assertEquals(3, ranges.size)
        ranges.forEach { assertEquals(2, it.last - it.first + 1) }
        assertTrue(ranges.zipWithNext().all { (a, b) -> b.first > a.last })
    }

    @Test
    fun regexRanges_expandedVariantsFindsAllHits() {
        val text = (1..14).joinToString("。") { "第${it}處曹門與曹门並見" }
        val ranges = ReaderHtml.regexRanges(text, Regex("[曹曺][門门]", RegexOption.IGNORE_CASE))
        assertEquals("异体字展开后每处两种写法都应命中", 28, ranges.size)
    }

    @Test
    fun regexRanges_skipsEmptyMatches() {
        assertTrue("空匹配不应产生无意义结果", ReaderHtml.regexRanges("甲乙丙", Regex("x*")).isEmpty())
    }

    // ————— 4. 主题 CSS（0.4.34：「夜」不得屏蔽书内文字颜色）—————

    @Test
    fun themeCss_nightKeepsBookTextColors() {
        val night = ReaderHtml.themeCss("#000000", "#cccccc", "#8ab4f8", true)
        assertFalse("夜间不应注入 color:inherit（会把书内朱批/校注抹成主题色）",
            night.contains("color:inherit"))
        assertFalse("链接色不应带 !important，否则书内彩色链接被覆盖",
            Regex("body a\\{color:[^}]*!important").containsMatchIn(night))
        assertFalse("夜间不应保留针对正文元素的颜色覆盖选择器",
            night.contains("body p,") || night.contains("body span"))
    }

    @Test
    fun themeCss_allThemesShareSameColorRules() {
        val palettes = listOf(
            Triple("#ffffff", "#222222", "#1a73e8") to false,   // 白
            Triple("#f5ecd9", "#3b3226", "#8d5a2b") to false,   // 纸
            Triple("#000000", "#cccccc", "#8ab4f8") to true     // 夜
        )
        val bases = palettes.map { (p, night) ->
            ReaderHtml.themeCss(p.first, p.second, p.third, night)
                .substringBefore("mark.__srch")
                .replace(p.first, "BG").replace(p.second, "TEXT").replace(p.third, "LINK")
        }
        assertEquals("三个主题的颜色规则骨架必须完全一致（夜间不得特殊覆盖）", 1, bases.distinct().size)
        assertTrue(bases[0].contains("html,body{background:BG !important;color:TEXT !important;}"))
        assertTrue(bases[0].contains("body a{color:LINK;}"))
    }

    @Test
    fun themeCss_keepsNightSearchHighlight() {
        val night = ReaderHtml.themeCss("#000000", "#cccccc", "#8ab4f8", true)
        val day = ReaderHtml.themeCss("#ffffff", "#222222", "#1a73e8", false)
        assertTrue(night.contains("mark.__srch{background:#6b5900 !important;color:#fff !important;}"))
        assertTrue(day.contains("mark.__srch{background:#ffd54f !important;color:#111 !important;}"))
    }
}
