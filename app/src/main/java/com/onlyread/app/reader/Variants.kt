package com.onlyread.app.reader

/**
 * 异体字表（纯 JVM，无 Android 依赖，可直接跑 JUnit 单元测试）。
 *
 * 只做两件事：
 *  1. [parse] 解析用户的 variants.json：`{"字": ["异体1", "异体2"], ...}`；
 *     为避免引入第三方库（工程为零依赖），用极简递归下降解析器，支持 \uXXXX 转义；
 *  2. [expandQuery] 按表把字面查询展开为字符类正则，例如
 *     `黄钟` → `[黄黃][钟锺𲈛鍾鐘]`。
 *
 * ⚠️ 表内容原样使用：不补字、不去重、不改顺序、不改写字符，数组里写什么就拼什么。
 * ⚠️ 遍历一律按 Unicode code point，不能按 UTF-16 char：`𲈛` 等增补平面汉字
 * 是代理对（2 个 char），逐 char 处理会把它拆成两个孤立代理项，正则将无法命中。
 */
object Variants {

    /**
     * 正则元字符（字符类之外）：这些字符原样保留，不参与异体字展开。
     * 与 advanced_search.py 的 `_META_CHARS` 一致；注意「含元字符」不再意味着整体退出展开——
     * 元字符照常保留，查询里的字面汉字仍按表展开（见 [expandQuery]）。
     */
    private const val META_CHARS = "\\.[]{}()*+?^\$|"

    /** 解析 variants.json；格式非法时抛 IllegalArgumentException，由调用方决定如何处理。 */
    fun parse(json: String): Map<String, List<String>> {
        val r = Json(json)
        val map = r.readObject()
        if (!r.atEnd()) throw IllegalArgumentException("JSON 末尾存在多余内容")
        return map
    }

    /**
     * 按异体表把查询展开为正则。参考 advanced_search.py 的 `expand_pattern_variants`：
     * **逐字符处理，正则元字符与转义序列原样保留，字面汉字照常展开**——不再像旧版那样
     * 「查询里一出现元字符就整体原样返回、放弃异体展开」。`^黄.*钟$` 里的 `^` `.*` `$`
     * 保留，`黄` / `钟` 仍展开；`[黄鐘]` 这类字符类内的字面汉字把异体并入当前类，
     * 不额外套一层 `[]`。
     *
     * 规则：
     * - `\x` 转义序列（`\d`、`\(`、`\u4e00` …）整段保留，不展开；
     * - `[` / `]` 字符类边界原样保留，只切换 `inClass` 状态；
     * - 字符类**外**的正则元字符（量词、分组、锚点等）原样保留；
     * - 表内有映射的码位 → `[` + 数组原样拼接 + `]`（顺序、重复项完全照抄配置）；
     *   在字符类内则只把数组内容并入当前类；
     * - 表内无映射或数组为空 → 原码位原样保留（字符类内对 `\ ] ^ -` 做最小转义）。
     *
     * 不对配置做任何额外修改或补充：不自动补键自身、不去重、不改顺序、不退化成分支。
     * 展开结果同时用于「扫描 / 结果高亮 / 跳转高亮」，三处口径一致。
     * 遍历一律按 Unicode code point，避免把 `𲈛` 等增补平面汉字拆成孤立代理项。
     */
    fun expandQuery(query: String, variants: Map<String, List<String>>): String {
        if (query.isEmpty() || variants.isEmpty()) return query
        val sb = StringBuilder()
        var i = 0
        var inClass = false
        while (i < query.length) {
            val ch = query[i]
            // 转义序列整体保留：\ 后的一个完整 code point 不参与异体展开
            if (ch == '\\' && i + 1 < query.length) {
                val cp = query.codePointAt(i + 1)
                sb.append('\\').append(String(Character.toChars(cp)))
                i += 1 + Character.charCount(cp)
                continue
            }
            // 字符类边界原样保留，只切换状态（字符类内的字面汉字仍需展开）
            if (ch == '[' && !inClass) { inClass = true; sb.append(ch); i++; continue }
            if (ch == ']' && inClass) { inClass = false; sb.append(ch); i++; continue }
            // 字符类外的正则元字符：原样保留，用户手写正则语义优先
            if (!inClass && ch in META_CHARS) { sb.append(ch); i++; continue }
            // 字面字符：按 code point，支持增补平面汉字
            val cp = query.codePointAt(i)
            val unit = String(Character.toChars(cp))
            i += Character.charCount(cp)
            val group = variants[unit]
            if (group.isNullOrEmpty()) {
                // 无映射：字符类内的 \ ] ^ - 需转义，避免生成非法正则
                if (inClass && (ch == '\\' || ch == ']' || ch == '^' || ch == '-')) sb.append('\\')
                sb.append(unit)
            } else {
                val cls = StringBuilder()
                // 顺序、重复项、多字符条目照抄配置；仅对字符类内的正则元字符做最小转义，
                // 避免用户字表里出现 \ ] ^ - 时生成非法正则，导致整次「关联搜索」静默失效。
                group.forEach { s ->
                    s.forEach { c ->
                        if (c == '\\' || c == ']' || c == '^' || c == '-') cls.append('\\')
                        cls.append(c)
                    }
                }
                if (inClass) sb.append(cls) else sb.append('[').append(cls).append(']')
            }
        }
        return sb.toString()
    }

    /** 极简 JSON 读取器：只认 `{ "键": ["值", …], … }` 这一种结构。 */
    private class Json(private val s: String) {
        private var i = 0

        private fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun peek(): Char? {
            ws()
            return s.getOrNull(i)
        }

        fun atEnd(): Boolean {
            ws()
            return i >= s.length
        }

        private fun expect(c: Char) {
            if (peek() != c) throw IllegalArgumentException("位置 $i：期望 '$c'")
            i++
        }

        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c != '\\') {
                    sb.append(c)
                    continue
                }
                if (i >= s.length) throw IllegalArgumentException("位置 $i：转义未闭合")
                when (val e = s[i++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 > s.length) throw IllegalArgumentException("位置 $i：\\u 转义不完整")
                        val hex = s.substring(i, i + 4)
                        i += 4
                        sb.append(hex.toInt(16).toChar())
                    }
                    else -> throw IllegalArgumentException("位置 $i：非法转义 \\$e")
                }
            }
            throw IllegalArgumentException("字符串未闭合")
        }

        fun readObject(): Map<String, List<String>> {
            expect('{')
            val out = LinkedHashMap<String, List<String>>()
            if (peek() == '}') {
                i++
                return out
            }
            while (true) {
                val key = string()
                expect(':')
                expect('[')
                val arr = ArrayList<String>()
                if (peek() != ']') {
                    while (true) {
                        arr.add(string())
                        val c = peek()
                        if (c == ',') {
                            i++
                            continue
                        }
                        if (c == ']') break
                        throw IllegalArgumentException("位置 $i：数组内期望 ',' 或 ']'")
                    }
                }
                expect(']')
                out[key] = arr
                val c = peek()
                if (c == ',') {
                    i++
                    continue
                }
                if (c == '}') {
                    i++
                    break
                }
                throw IllegalArgumentException("位置 $i：对象内期望 ',' 或 '}'")
            }
            return out
        }
    }
}
