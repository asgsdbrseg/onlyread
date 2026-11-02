package com.onlyread.app.epub

import com.onlyread.app.util.ZipPaths
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * EPUB 归档访问：ZipFile 封装 + container.xml/OPF/目录(NAV/NCX) 解析。
 * 生命周期 = 阅读会话；保持打开状态以支撑 BookActivity 的请求拦截。
 */
class EpubArchive(file: File) : Closeable {

    private val zip = ZipFile(file)

    /** 小写路径 → 条目 索引，兜底大小写不一致的畸形书 */
    private val lowerIndex: Map<String, ZipEntry> by lazy {
        val map = HashMap<String, ZipEntry>()
        val en = zip.entries()
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            map[e.name.lowercase()] = e
        }
        map
    }

    override fun close() {
        zip.close()
    }

    fun entryBytes(entryPath: String): ByteArray? {
        val entry = findEntry(ZipPaths.normalize(entryPath)) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    private fun findEntry(path: String): ZipEntry? {
        zip.getEntry(path)?.let { return it }
        return lowerIndex[path.lowercase()]
    }

    fun opfPath(): String? {
        val container = findEntry("META-INF/container.xml") ?: return null
        val parser = newParser(zip.getInputStream(container)) ?: return null
        var result: String? = null
        while (moveTo(parser)) {
            if (parser.eventType == XmlPullParser.START_TAG && localName(parser) == "rootfile") {
                val full = attr(parser, "full-path")
                if (!full.isNullOrBlank()) {
                    result = ZipPaths.decode(full)
                    break
                }
            }
        }
        return result
    }

    fun parseBook(): EpubBook? {
        val opf = opfPath() ?: return null
        val opfDir = if (opf.contains('/')) opf.substringBeforeLast('/') else ""
        val parser = newParser(findEntry(opf)?.let { zip.getInputStream(it) }) ?: return null

        var title = ""
        var author: String? = null
        var language: String? = null
        var publisher: String? = null
        var coverMetaId: String? = null
        var inMetadata = false
        var inManifest = false
        var inSpine = false
        var capture: String? = null
        val manifest = HashMap<String, EpubItem>()
        val spineRefs = ArrayList<String>()

        while (moveTo(parser)) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (localName(parser)) {
                    "metadata" -> inMetadata = true
                    "manifest" -> inManifest = true
                    "spine" -> inSpine = true
                    "title", "creator", "language", "publisher" -> if (inMetadata) capture = localName(parser)
                    "meta" -> if (inMetadata && attr(parser, "name")?.equals("cover", true) == true) {
                        coverMetaId = attr(parser, "content")
                    }
                    "item" -> if (inManifest) {
                        val id = attr(parser, "id")
                        if (id != null) {
                            manifest[id] = EpubItem(
                                id,
                                attr(parser, "href") ?: "",
                                attr(parser, "media-type") ?: "",
                                attr(parser, "properties")
                            )
                        }
                    }
                    "itemref" -> if (inSpine) attr(parser, "idref")?.let { spineRefs.add(it) }
                }
                XmlPullParser.TEXT -> {
                    val cap = capture
                    if (cap != null) {
                        val t = parser.text?.trim().orEmpty()
                        if (t.isNotEmpty()) when (cap) {
                            "title" -> title = t
                            "creator" -> author = t
                            "language" -> language = t
                            "publisher" -> publisher = t
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (localName(parser)) {
                    "metadata" -> inMetadata = false
                    "manifest" -> inManifest = false
                    "spine" -> inSpine = false
                    "title", "creator", "language", "publisher" -> capture = null
                }
            }
        }

        val spine = spineRefs.mapNotNull { idref ->
            manifest[idref]?.let { item ->
                if (item.mediaType.contains("xhtml") || item.mediaType == "text/html") {
                    EpubSpineItem(idref, item.href, ZipPaths.join(opfDir, ZipPaths.decode(item.href)))
                } else null
            }
        }

        val cover =
            manifest.values.firstOrNull {
                it.properties?.split(' ')?.contains("cover-image") == true && it.mediaType.startsWith("image/")
            }
                ?: coverMetaId?.let { manifest[it] }?.takeIf { it.mediaType.startsWith("image/") }
                ?: manifest.values.firstOrNull {
                    it.mediaType.startsWith("image/") &&
                        (it.id.equals("cover-image", true) || it.id.equals("cover", true) || it.href.contains("cover", true))
                }

        val toc = parseToc(manifest, opfDir, spine)
        // M3：EPUB3 NAV（目录文件）路径——正文加载时跳过，不在阅读流中显示目录内容
        val navPath = manifest.values.firstOrNull {
            (it.properties?.split(' ')?.contains("nav") == true) &&
                (it.mediaType.contains("xhtml") || it.mediaType == "text/html")
        }?.let { ZipPaths.join(opfDir, ZipPaths.decode(it.href)) }

        return EpubBook(
            title = title,
            author = author,
            language = language,
            publisher = publisher,
            opfDir = opfDir,
            coverPath = cover?.let { ZipPaths.join(opfDir, ZipPaths.decode(it.href)) },
            spine = spine,
            toc = toc,
            navPath = navPath
        )
    }

    // ————— 目录解析（M1） —————

    /** 优先 EPUB3 NAV，其次 EPUB2 NCX */
    private fun parseToc(manifest: Map<String, EpubItem>, opfDir: String, spine: List<EpubSpineItem>): List<TocEntry> {
        val navItem = manifest.values.firstOrNull {
            (it.properties?.split(' ')?.contains("nav") == true) &&
                (it.mediaType.contains("xhtml") || it.mediaType == "text/html")
        }
        val ncxItem = manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }

        val raw: List<RawToc> = when {
            navItem != null -> {
                val navPath = ZipPaths.join(opfDir, ZipPaths.decode(navItem.href))
                val dir = navPath.substringBeforeLast('/', "")
                parseNav(findEntry(navPath)?.let { zip.getInputStream(it) }, dir)
            }
            ncxItem != null -> {
                val ncxPath = ZipPaths.join(opfDir, ZipPaths.decode(ncxItem.href))
                val dir = ncxPath.substringBeforeLast('/', "")
                parseNcx(findEntry(ncxPath)?.let { zip.getInputStream(it) }, dir)
            }
            else -> emptyList()
        }

        return raw.mapNotNull { r ->
            val target = ZipPaths.join(r.baseDir, ZipPaths.decode(r.path))
            val si = spine.indexOfFirst { it.path == target }
            if (si < 0 || r.title.isBlank()) null
            else TocEntry(r.title, target, r.frag, r.level, si)
        }
    }

    private data class RawToc(val title: String, val path: String, val frag: String?, val level: Int, val baseDir: String)

    /** EPUB2 NCX navMap：navPoint 栈式解析 */
    private fun parseNcx(input: InputStream?, baseDir: String): List<RawToc> {
        val parser = newParser(input) ?: return emptyList()
        val out = ArrayList<RawToc>()
        val stack = ArrayDeque<Node>()
        var inNavLabel = false
        try {
            while (moveTo(parser)) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (localName(parser)) {
                        "navpoint" -> stack.addLast(Node())
                        "navlabel" -> inNavLabel = true
                        "text" -> if (inNavLabel && stack.isNotEmpty() && stack.last().title == null) {
                            val t = readText(parser)
                            if (stack.last().title == null) stack.last().title = t
                        }
                        "content" -> if (stack.isNotEmpty()) {
                            val src = attr(parser, "src")
                            if (src != null && stack.last().src == null) {
                                stack.last().src = ZipPaths.decode(src.substringBefore('#'))
                                stack.last().frag = src.substringAfter('#', "").ifEmpty { null }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> when (localName(parser)) {
                        "navlabel" -> inNavLabel = false
                        "navpoint" -> {
                            if (stack.isNotEmpty()) {
                                val n = stack.removeLast()
                                val level = stack.size.coerceAtMost(5)
                                if (n.title != null && n.src != null && out.size < 3000) {
                                    out.add(RawToc(n.title!!, n.src!!, n.frag, level, baseDir))
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // 畸形 NCX：返回已解析部分
        }
        return out
    }

    /** EPUB3 NAV：epub:type="toc" 的 nav 下的 ol/li/a（嵌套 ol 计层级） */
    private fun parseNav(input: InputStream?, baseDir: String): List<RawToc> {
        val parser = newParser(input) ?: return emptyList()
        val out = ArrayList<RawToc>()
        try {
            var inTocNav = false
            var sawTypedNav = false
            var inAnyNav = false
            var navDepth = 0
            var olDepth = 0
            var pendingHref: String? = null
            var pendingLevel = 0

            while (moveTo(parser)) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (localName(parser)) {
                        "nav" -> {
                            navDepth++
                            val type = attr(parser, "epub:type") ?: attr(parser, "type")
                            if (!inTocNav) {
                                if (type?.split(Regex("\\s+"))?.contains("toc") == true) {
                                    inTocNav = true
                                    sawTypedNav = true
                                    inAnyNav = true
                                    olDepth = 0
                                } else if (navDepth == 1) {
                                    // 记住第一个 nav 作为兜底
                                    inAnyNav = true
                                    olDepth = 0
                                }
                            }
                        }
                        "ol" -> if (inAnyNav && (inTocNav || !sawTypedNav)) olDepth++
                        "a" -> if (inAnyNav && (inTocNav || !sawTypedNav) && olDepth > 0) {
                            val href = attr(parser, "href")
                            if (href != null) {
                                pendingHref = href
                                pendingLevel = (olDepth - 1).coerceAtMost(5)
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (pendingHref != null) {
                        val t = parser.text?.trim().orEmpty()
                        if (t.isNotEmpty() && out.size < 3000) {
                            val path = ZipPaths.decode(pendingHref!!.substringBefore('#'))
                            val frag = pendingHref!!.substringAfter('#', "").ifEmpty { null }
                            out.add(RawToc(t, path, frag, pendingLevel, baseDir))
                            pendingHref = null
                        }
                    }
                    XmlPullParser.END_TAG -> when (localName(parser)) {
                        "ol" -> if (inAnyNav) olDepth--
                        "nav" -> {
                            if (inTocNav && navDepth == 1) inTocNav = false
                            navDepth--
                            if (navDepth == 0) inAnyNav = false
                        }
                        "a" -> pendingHref = null
                    }
                }
            }
        } catch (_: Exception) {
            // 畸形 NAV：返回已解析部分
        }
        return out
    }

    private class Node {
        var title: String? = null
        var src: String? = null
        var frag: String? = null
    }

    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        try {
            while (depth > 0) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> depth++
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.TEXT -> sb.append(parser.text ?: "")
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
        } catch (_: Exception) {
        }
        return sb.toString().trim()
    }

    companion object {
        private fun newParser(input: InputStream?): XmlPullParser? {
            if (input == null) return null
            return try {
                val p = XmlPullParserFactory.newInstance().newPullParser()
                p.setInput(input, null)
                p
            } catch (_: Exception) {
                null
            }
        }

        private fun moveTo(parser: XmlPullParser): Boolean =
            try {
                parser.next() != XmlPullParser.END_DOCUMENT
            } catch (_: Exception) {
                false
            }

        private fun localName(parser: XmlPullParser): String =
            parser.name?.substringAfter(':')?.lowercase() ?: ""

        private fun attr(parser: XmlPullParser, name: String): String? {
            for (i in 0 until parser.attributeCount) {
                if (parser.getAttributeName(i).equals(name, ignoreCase = true)) {
                    return parser.getAttributeValue(i)
                }
            }
            return null
        }
    }
}
