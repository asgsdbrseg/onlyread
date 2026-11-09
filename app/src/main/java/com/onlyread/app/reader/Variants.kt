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

    /** 正则元字符：查询里出现任意一个，视为用户手写正则，原样返回、不做异体字改写。 */
    private const val META_CHARS = "\\.[]{}()*+?^\$|"

    /** 解析 variants.json；格式非法时抛 IllegalArgumentException，由调用方决定如何处理。 */
    fun parse(json: String): Map<String, List<String>> {
        val r = Json(json)
        val map = r.readObject()
        if (!r.atEnd()) throw IllegalArgumentException("JSON 末尾存在多余内容")
        return map
    }

    /**
     * 按异体表把字面查询展开为正则：
     * - 表内有映射的码位 → `[` + 数组原样拼接 + `]`（顺序、重复项完全照抄配置）；
     * - 表内无映射或数组为空 → 原码位原样保留；
     * - 查询含正则元字符 → 原样返回，用户自己写的正则优先。
     *
     * 不对配置做任何额外修改或补充：不自动补键自身、不去重、不改顺序、不退化成分支；
     * 仅在字符类内对 `\ ] ^ -` 做最小转义（M3.10，防止表值破坏字符类结构）。
     * 展开结果同时用于「扫描 / 结果高亮 / 跳转高亮」，三处口径一致。
     */
    fun expandQuery(query: String, variants: Map<String, List<String>>): String {
        if (query.isEmpty() || variants.isEmpty()) return query
        if (query.any { it in META_CHARS }) return query
        val sb = StringBuilder()
        var i = 0
        while (i < query.length) {
            val cp = query.codePointAt(i)
            val unit = String(Character.toChars(cp))
            i += Character.charCount(cp)
            val group = variants[unit]
            if (group.isNullOrEmpty()) {
                sb.append(unit)
            } else {
                sb.append('[')
                // M3.10：最小转义——`\ ] ^ -` 在字符类内有特殊含义，转义避免破坏字符类；
                // 顺序与重复项仍原样保留
                group.forEach { sb.append(escapeInClass(it)) }
                sb.append(']')
            }
        }
        return sb.toString()
    }

    /** 字符类内需转义的元字符：反斜杠、闭括号、否定符、连字符（其余字面字符原样保留） */
    private fun escapeInClass(s: String): String = buildString {
        for (ch in s) when (ch) {
            '\\' -> append("\\\\")
            ']' -> append("\\]")
            '^' -> append("\\^")
            '-' -> append("\\-")
            else -> append(ch)
        }
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
