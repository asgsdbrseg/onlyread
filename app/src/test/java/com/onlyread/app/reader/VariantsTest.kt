package com.onlyread.app.reader

import org.junit.Assert.*
import org.junit.Test

/**
 * 异体字表纯函数单元测试（JUnit 4，JVM，无 Android 依赖）。
 *
 * 覆盖：JSON 解析、字面查询展开、增补平面汉字（𲈛 为 UTF-16 代理对）不被拆坏、
 * 展开后的正则确实能命中各种异体写法、含元字符时元字符保留且字面汉字仍展开、
 * 开关关闭 / 配置顺序与重复项原样保留等边界。
 */
class VariantsTest {

    private val sampleJson = """
        {
          "钟": ["钟", "锺", "𲈛", "鍾", "鐘"],
          "锺": ["钟", "锺", "𲈛", "鍾", "鐘"],
          "𲈛": ["钟", "锺", "𲈛", "鍾", "鐘"],
          "鍾": ["钟", "锺", "𲈛", "鍾", "鐘"],
          "鐘": ["钟", "锺", "𲈛", "鍾", "鐘"],
          "黄": ["黄", "黃"],
          "黃": ["黄", "黃"]
        }
    """.trimIndent()

    @Test
    fun parse_readsAllKeysAndVariants() {
        val map = Variants.parse(sampleJson)
        assertEquals(7, map.size)
        assertEquals(listOf("钟", "锺", "𲈛", "鍾", "鐘"), map["钟"])
        assertEquals(listOf("黄", "黃"), map["黄"])
        assertEquals(listOf("黄", "黃"), map["黃"])
    }

    @Test
    fun expand_huangZhongToCharacterClasses() {
        val map = Variants.parse(sampleJson)
        // 用户给定用例：字面查询按字展开为字符类
        assertEquals("[黄黃][钟锺𲈛鍾鐘]", Variants.expandQuery("黄钟", map))
    }

    @Test
    fun expandedRegex_matchesEveryVariantCombination() {
        val map = Variants.parse(sampleJson)
        val re = Regex(Variants.expandQuery("黄钟", map))
        listOf("黄钟", "黄锺", "黄𲈛", "黃鍾", "黃鐘", "黃𲈛").forEach {
            assertTrue("应命中 $it", re.containsMatchIn("《$it》并称"))
        }
        assertFalse("不应命中无关词", re.containsMatchIn("黄粱一梦"))
    }

    @Test
    fun supplementaryCharacterIsNotSplitIntoSurrogates() {
        val map = Variants.parse(sampleJson)
        // 𲈛 是 1 个 code point / 2 个 UTF-16 char：按 code point 遍历才不会拆坏字符类
        val expanded = Variants.expandQuery("𲈛", map)
        assertEquals("[钟锺𲈛鍾鐘]", expanded)
        assertTrue(Regex(expanded).containsMatchIn("此字为𲈛"))
        assertEquals(1, "𲈛".codePointCount(0, "𲈛".length))
    }

    @Test
    fun nonMappedCharactersArePreservedInPlace() {
        val map = Variants.parse(sampleJson)
        assertEquals("[黄黃]大[钟锺𲈛鍾鐘]吕", Variants.expandQuery("黄大钟吕", map))
    }

    @Test
    fun switchOffOrEmptyTableReturnsRawQuery() {
        val map = Variants.parse(sampleJson)
        assertEquals("黄钟", Variants.expandQuery("黄钟", emptyMap()))
        assertEquals("黄钟", Variants.expandQuery("黄钟", mapOf()))
    }

    @Test
    fun metaCharsArePreservedButLiteralHanStillExpands() {
        val map = Variants.parse(sampleJson)
        // 参考 advanced_search.py：取消「含元字符就退出异体展开」——元字符原样保留，
        // 字面汉字照样展开成字符类。^黄.*钟$ 里的 ^ .* $ 保留，黄/钟 仍展开。
        assertEquals("[黄黃].[钟锺𲈛鍾鐘]", Variants.expandQuery("黄.钟", map))
        assertEquals("^[黄黃]*[钟锺𲈛鍾鐘]$", Variants.expandQuery("^黄*钟$", map))
    }

    @Test
    fun escapeSequencesAndGroupsAreKeptIntact() {
        val map = Variants.parse(sampleJson)
        // \d、\. 等转义序列整段保留；分组、量词、选择分支原样保留
        assertEquals("[黄黃]\\d[钟锺𲈛鍾鐘]", Variants.expandQuery("黄\\d钟", map))
        assertEquals("[黄黃]\\.[钟锺𲈛鍾鐘]", Variants.expandQuery("黄\\.钟", map))
        assertEquals("([黄黃]|[钟锺𲈛鍾鐘])+", Variants.expandQuery("(黄|钟)+", map))
    }

    @Test
    fun characterClassLiteralsAreExpandedInPlace() {
        val map = Variants.parse(sampleJson)
        // 字符类内的字面汉字把异体并入当前类，不再额外套一层 []
        assertEquals("[黄黃钟锺𲈛鍾鐘]", Variants.expandQuery("[黄鐘]", map))
    }

    @Test
    fun expandedRegexWithMetaStillMatchesVariants() {
        val map = Variants.parse(sampleJson)
        val re = Regex(Variants.expandQuery("^黄钟$", map))
        assertTrue("含锚点也应命中异体写法", re.containsMatchIn("黄锺"))
        assertTrue(re.containsMatchIn("黃鐘"))
        assertFalse("不应命中无关词", re.containsMatchIn("黄粱一梦"))
    }

    @Test
    fun variantListIsUsedVerbatimWithoutAddingSelf() {
        val map = mapOf("黄" to listOf("黃"))
        // 原样读取：数组里只有「黃」，不自动补上键自身「黄」
        assertEquals("[黃]", Variants.expandQuery("黄", map))
        assertFalse("不应自动补充配置里没有的字", Regex("[黃]").containsMatchIn("黄"))
    }

    @Test
    fun variantOrderAndDuplicatesAreKeptVerbatim() {
        val map = mapOf("黄" to listOf("黃", "黄", "黃"))
        // 不去重、不改顺序、不退化成分支，按配置原样拼进字符类
        assertEquals("[黃黄黃]", Variants.expandQuery("黄", map))
    }

    @Test
    fun multiCharacterEntryIsAlsoUsedVerbatim() {
        val map = mapOf("黄" to listOf("黄", "黃黄"))
        assertEquals("[黄黃黄]", Variants.expandQuery("黄", map))
    }

    @Test
    fun unicodeEscapeInJsonIsDecoded() {
        val json = "{\"黄\": [\"\\u9EC4\", \"黃\"]}"
        val map = Variants.parse(json)
        assertEquals("[黄黃]", Variants.expandQuery("黄", map))
    }

    @Test
    fun invalidJsonThrowsAndEmptyTableIsUsable() {
        try {
            Variants.parse("{\"黄\": \"不是数组\"}")
            fail("非法结构应抛异常")
        } catch (_: IllegalArgumentException) {
        }
        // 配置不可用时按原词搜索，空表仍可解析
        assertTrue(Variants.parse("{}").isEmpty())
    }

    @Test
    fun characterClassMetaCharsAreEscaped() {
        // 用户字表里混入正则元字符时不能生成非法正则导致整次关联搜索失效
        val map = mapOf("黄" to listOf("黃", "]"))
        val expanded = Variants.expandQuery("黄", map)
        assertEquals("[黃\\]]", expanded)
        assertTrue(Regex(expanded).containsMatchIn("黃"))
        assertTrue(Regex(expanded).containsMatchIn("]"))
    }
}
