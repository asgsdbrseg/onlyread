package com.onlyread.app.epub

import com.onlyread.app.util.Logx
import com.onlyread.app.util.ZipPaths
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * EPUB 归档访问：ZipFile 封装 + container.xml/OPF/目录(NAV/NCX) 解析。
 * 生命周期 = 阅读会话；保持打开状态以支撑 BookActivity 的请求拦截。
 *
 * 0.4.22 修正：
 * - 所有 ZipFile 访问与关闭收在同一把锁内，加 [closed] 标志。此前 onDestroy 关闭
 *   ZipFile 的同时，资源拦截/后台章节加载线程仍可能正在读取，会抛
 *   `IllegalStateException: zip file closed` 甚至导致 WebView 渲染异常；
 * - 新增 [entryStream]：图片/字体等二进制资源经 WebResourceResponse 直接流式交给
 *   WebView，不再整块读进 byte[]；小文本资源走带容量上限的 LRU 缓存，避免同一
 *   CSS/章节被反复解压；
 * - 所有 XML 解析用的 InputStream 明确关闭（或改用内存流），消除泄漏；
 * - 空 catch 全部改为带日志的明确回退，消除 `!!`。
 */
class EpubArchive(file: File) : Closeable {

    companion object {
        private const val TAG = "EpubArchive"
        private const val MAX_CACHE_BYTES = 4L * 1024 * 1024
        private const val MAX_SINGLE_ENTRY = 512 * 1024
        private val NAV_NAME_RE = Regex("(?i)(toc|nav|contents|catalog)")
        private val NAV_CONTENT_RE = Regex(
            "(?i)epub:type\\s*=\\s*[\"']toc[\"']" +
                "|<nav[^>]*id\\s*=\\s*[\"'](toc|nav)[\"']" +
                "|<nav[^>]*class\\s*=\\s*[\"'][^\"']*(toc|nav)"
        )
        private val WS_SPLIT_RE = Regex("\\s+")
    }

    private val zip = ZipFile(file)

    /** 保护 ZipFile 的生命周期与缓存；close 与所有读取都在锁内，杜绝并发关闭。 */
    private val lock = Any()

    @Volatile
    private var closed = false

    /** 小写路径 → 条目索引，兜底大小写不一致的畸形书（仅在 [lock] 内惰性初始化）。 */
    private val lowerIndex: Map<String, ZipEntry> by lazy {
        val map = HashMap<String, ZipEntry>()
        val en = zip.entries()
        while (en.hasMoreElements()) {
            val e = en.nextElement()
            map[e.name.lowercase()] = e
        }
        map
    }

    /** 小资源 LRU（access-order），容量按字节计，避免大书把内存吃满。 */
    private val bytesCache = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var cacheBytes = 0L

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            bytesCache.clear()
            cacheBytes = 0
            try {
                zip.close()
            } catch (e: Exception) {
                Logx.w(TAG, "关闭 ZipFile 失败", e)
            }
        }
    }

    /** 文本资源（HTML/CSS/NCX/OPF）→ 字节数组；命中缓存则不重复解压。 */
    fun entryBytes(entryPath: String): ByteArray? {
        val key = ZipPaths.normalize(entryPath)
        return synchronized(lock) {
            if (closed) return@synchronized null
            val e = findEntryLocked(key) ?: return@synchronized null
            bytesLocked(key, e)
        }
    }

    /**
     * 二进制资源 → InputStream，交给 WebResourceResponse 流式消费，避免整块读入内存。
     * 小条目（≤512KB）直接返回缓存字节的内存流，兼顾「重复请求不重复解压」。
     * 调用方负责关闭返回的流。
     */
    fun entryStream(entryPath: String): InputStream? {
        val key = ZipPaths.normalize(entryPath)
        return synchronized(lock) {
            if (closed) return@synchronized null
            val e = findEntryLocked(key) ?: return@synchronized null
            val size = e.size
            if (size >= 0 && size <= MAX_SINGLE_ENTRY) {
                val b = bytesLocked(key, e) ?: return@synchronized null
                return@synchronized ByteArrayInputStream(b)
            }
            try {
                zip.getInputStream(e)
            } catch (ex: Exception) {
                Logx.w(TAG, "打开条目流失败: $key", ex)
                null
            }
        }
    }

    /** 调用方必须已持有 [lock]。 */
    private fun findEntryLocked(path: String): ZipEntry? {
        zip.getEntry(path)?.let { return it }
        return lowerIndex[path.lowercase()]
    }

    /** 调用方必须已持有 [lock]。 */
    private fun bytesLocked(key: String, e: ZipEntry): ByteArray? {
        bytesCache[key]?.let { return it }
        val data = try {
            zip.getInputStream(e).use { it.readBytes() }
        } catch (ex: Exception) {
            Logx.w(TAG, "读取条目失败: $key", ex)
            return null
        }
        if (data.size <= MAX_SINGLE_ENTRY) {
            bytesCache[key] = data
            cacheBytes += data.size
            while (cacheBytes > MAX_CACHE_BYTES && bytesCache.isNotEmpty()) {
                val it = bytesCache.entries.iterator()
                val oldest = it.next()
                cacheBytes -= oldest.value.size
                it.remove()
            }
        }
        return data
    }

    fun opfPath(): String? {
        // container.xml 走缓存读取；用 ByteArrayInputStream 解析，流在 use 中关闭。
        val container = entryBytes("META-INF/container.xml") ?: return null
        return ByteArrayInputStream(container).use { ins ->
            val parser = newParser(ins) ?: return null
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
            result
        }
    }

    fun parseBook(): EpubBook? {
        val opf = opfPath() ?: return null
        val opfDir = if (opf.contains('/')) opf.substringBeforeLast('/') else ""
        val opfBytes = entryBytes(opf) ?: return null
        return ByteArrayInputStream(opfBytes).use { ins ->
            val parser = newParser(ins) ?: return null
            parseOpf(parser, opfDir)
        }
    }

    /** OPF 元数据/manifest/spine 解析（输入流由调用方 use 关闭）。 */
    private fun parseOpf(parser: XmlPullParser, opfDir: String): EpubBook? {
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

        // M3.5：Reasily 式——全部目录页集合。
        val navMarked = manifest.values.filter {
            (it.properties?.split(' ')?.contains("nav") == true) &&
                (it.mediaType.contains("xhtml") || it.mediaType == "text/html")
        }
        val navPath = navMarked.firstOrNull()?.let { ZipPaths.join(opfDir, ZipPaths.decode(it.href)) }
        val navPaths = LinkedHashSet<String>().apply {
            navMarked.forEach { add(ZipPaths.join(opfDir, ZipPaths.decode(it.href))) }
        }
        if (navPaths.isEmpty()) {
            // 启发式：仅在文件名命中时才读内容确认（每书至多几次 8KB 读取）
            spine.forEach { s ->
                val name = s.href.substringAfterLast('/')
                if (NAV_NAME_RE.containsMatchIn(name) &&
                    (name.endsWith(".xhtml", true) || name.endsWith(".html", true) || name.endsWith(".htm", true)) &&
                    s.path !in navPaths
                ) {
                    val confirmed = entryStream(s.path)?.use { ins ->
                        try {
                            val buf = ByteArray(8192)
                            var n = 0
                            while (n < buf.size) { // read 可能分次返回，读满/读完为止
                                val r = ins.read(buf, n, buf.size - n)
                                if (r <= 0) break
                                n += r
                            }
                            if (n > 0) NAV_CONTENT_RE.containsMatchIn(String(buf, 0, n, Charsets.UTF_8)) else false
                        } catch (e: Exception) {
                            Logx.w(TAG, "目录页启发式探测失败: ${s.path}", e)
                            false
                        }
                    } ?: false
                    if (confirmed) navPaths.add(s.path)
                }
            }
        }
        // M3.6：解析期即从 spine 剔除全部目录页（Reasily 式）——目录页永不进入正文流；
        // ⚠️ 必须在 parseToc 之前剔除，使 TOC 条目的 spineIndex 与剔除后的 spine 对齐
        val spineReading = spine.filter { it.path !in navPaths }
        val toc = parseToc(manifest, opfDir, spineReading)

        return EpubBook(
            title = title,
            author = author,
            language = language,
            publisher = publisher,
            opfDir = opfDir,
            coverPath = cover?.let { ZipPaths.join(opfDir, ZipPaths.decode(it.href)) },
            spine = spineReading,
            toc = toc,
            navPath = navPath,
            navPaths = navPaths
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
                entryBytes(navPath)?.let { parseNav(ByteArrayInputStream(it), dir) } ?: emptyList()
            }
            ncxItem != null -> {
                val ncxPath = ZipPaths.join(opfDir, ZipPaths.decode(ncxItem.href))
                val dir = ncxPath.substringBeforeLast('/', "")
                entryBytes(ncxPath)?.let { parseNcx(ByteArrayInputStream(it), dir) } ?: emptyList()
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

    /** EPUB2 NCX navMap：navPoint 栈式解析（流由 use 关闭） */
    private fun parseNcx(input: InputStream?, baseDir: String): List<RawToc> {
        if (input == null) return emptyList()
        return input.use { ins ->
            val parser = newParser(ins) ?: return@use emptyList()
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
                                    val t = n.title
                                    val s = n.src
                                    if (t != null && s != null && out.size < 3000) {
                                        out.add(RawToc(t, s, n.frag, level, baseDir))
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // 畸形 NCX：返回已解析部分，但留下可排查痕迹
                Logx.w(TAG, "NCX 解析中断，使用已解析部分", e)
            }
            out
        }
    }

    /** EPUB3 NAV：epub:type="toc" 的 nav 下的 ol/li/a（嵌套 ol 计层级，流由 use 关闭） */
    private fun parseNav(input: InputStream?, baseDir: String): List<RawToc> {
        if (input == null) return emptyList()
        return input.use { ins ->
            val parser = newParser(ins) ?: return@use emptyList()
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
                                    if (type?.split(WS_SPLIT_RE)?.contains("toc") == true) {
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
                        XmlPullParser.TEXT -> {
                            val href = pendingHref
                            if (href != null) {
                                val t = parser.text?.trim().orEmpty()
                                if (t.isNotEmpty() && out.size < 3000) {
                                    val path = ZipPaths.decode(href.substringBefore('#'))
                                    val frag = href.substringAfter('#', "").ifEmpty { null }
                                    out.add(RawToc(t, path, frag, pendingLevel, baseDir))
                                    pendingHref = null
                                }
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
            } catch (e: Exception) {
                // 畸形 NAV：返回已解析部分，但留下可排查痕迹
                Logx.w(TAG, "NAV 解析中断，使用已解析部分", e)
            }
            out
        }
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
        } catch (e: Exception) {
            // 畸形嵌套：返回已收集文本
            Logx.w(TAG, "读取 navLabel 文本中断", e)
        }
        return sb.toString().trim()
    }

    private fun newParser(input: InputStream?): XmlPullParser? {
        if (input == null) return null
        return try {
            val p = XmlPullParserFactory.newInstance().newPullParser()
            p.setInput(input, null)
            p
        } catch (e: Exception) {
            Logx.w(TAG, "XML 解析器初始化失败", e)
            null
        }
    }

    private fun moveTo(parser: XmlPullParser): Boolean =
        try {
            parser.next() != XmlPullParser.END_DOCUMENT
        } catch (e: Exception) {
            Logx.w(TAG, "XML 解析中断，按文档结束处理", e)
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
