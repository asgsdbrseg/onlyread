package com.onlyread.app.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * OPF 解析单测：验证 NAV 文档（<itemref idref="nav" linear="no"/> 等形式）在解析阶段
 * 即从 spine 剔除，不进入正文阅读流；同时确认非 NAV 的 linear="no" 条目不被误删。
 *
 * 仅依赖 JDK + kxml2（提供 org.xmlpull.v1 实现），可在 JVM 直接运行；
 * Android 工程下由 ./gradlew test 执行同样有效。
 */
class EpubArchiveTest {

    private fun buildEpub(opf: String): File {
        val f = File.createTempFile("onlyread-test-", ".epub")
        f.deleteOnExit()
        ZipOutputStream(f.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(CONTAINER.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(opf.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return f
    }

    @Test
    fun parseBook_removesNavFromSpine() {
        val book = EpubArchive(buildEpub(OPF_WITH_NAV)).use { it.parseBook() }!!
        val ids = book.spine.map { it.idref }
        // NAV 项被剔除，仅剩真实章节与注释文件
        assertEquals(listOf("chap1", "notes"), ids)
        assertFalse("NAV 不应出现在正文 spine", ids.contains("nav"))
        // navPath 仍被识别（目录解析 / loadSpine 兜底仍可用）
        assertTrue("navPath 应指向 nav.xhtml", book.navPath?.endsWith("nav.xhtml") == true)
    }

    @Test
    fun parseBook_keepsNonNavLinearNo() {
        // linear="no" 但非 NAV 的条目仍保留在 spine（不能按 linear 误删）
        val book = EpubArchive(buildEpub(OPF_LINEAR_NO_NONNAV)).use { it.parseBook() }!!
        val ids = book.spine.map { it.idref }
        assertTrue("非 NAV 的 linear=no 条目应保留", ids.contains("appendix"))
        assertFalse("NAV 仍应被剔除", ids.contains("nav"))
    }

    /** 0.4.25：EPUB2 NCX 父 navPoint 必须先于子 navPoint 输出（旧后序解析把父排到子后）。 */
    private fun buildEpubWithNcx(ncx: String): File {
        val f = File.createTempFile("onlyread-ncx-", ".epub")
        f.deleteOnExit()
        ZipOutputStream(f.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(CONTAINER.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(OPF_WITH_NCX.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("OEBPS/toc.ncx"))
            zos.write(ncx.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return f
    }

    @Test
    fun parseBook_ncxParentComesBeforeChildren() {
        val book = EpubArchive(buildEpubWithNcx(NCX_NESTED)).use { it.parseBook() }!!
        assertEquals(
            "父 navPoint 必须排在它的子 navPoint 之前",
            listOf("出版说明", "前言", "第一编 绪论", "第一章 甲", "第二章 乙", "第二编 基本情况"),
            book.toc.map { it.title }
        )
        assertEquals(
            listOf(0, 0, 0, 1, 1, 0),
            book.toc.map { it.level }
        )
        assertEquals(
            listOf(null, null, null, "c1", "c2", null),
            book.toc.map { it.frag }
        )
        assertTrue("所有条目都应映射到 spine", book.toc.all { it.spineIndex >= 0 })
    }

    @Test
    fun parseBook_ncxThreeLevelsKeepOrderAndDepth() {
        val ncx = """<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
<navPoint id="a"><navLabel><text>A</text></navLabel><content src="Text/chap.xhtml"/>
  <navPoint id="b"><navLabel><text>B</text></navLabel><content src="Text/chap.xhtml#b"/>
    <navPoint id="c"><navLabel><text>C</text></navLabel><content src="Text/chap.xhtml#c"/></navPoint>
  </navPoint>
</navPoint>
<navPoint id="d"><navLabel><text>D</text></navLabel><content src="Text/chap.xhtml"/>
</navPoint></navMap></ncx>"""
        val book = EpubArchive(buildEpubWithNcx(ncx)).use { it.parseBook() }!!
        assertEquals(listOf("A", "B", "C", "D"), book.toc.map { it.title })
        assertEquals(listOf(0, 1, 2, 0), book.toc.map { it.level })
    }

    companion object {
        private const val OPF_WITH_NCX = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0">
  <manifest>
    <item id="chap1" href="Text/chap.xhtml" media-type="application/xhtml+xml"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
  </manifest>
  <spine toc="ncx"><itemref idref="chap1"/></spine>
</package>"""

        private const val NCX_NESTED = """<?xml version="1.0"?>
<ncx version="2005-1" xmlns="http://www.daisy.org/z3986/2005/ncx/">
<navMap>
  <navPoint id="n1" playOrder="1"><navLabel><text>出版说明</text></navLabel><content src="Text/chap.xhtml"/></navPoint>
  <navPoint id="n2" playOrder="2"><navLabel><text>前言</text></navLabel><content src="Text/chap.xhtml"/></navPoint>
  <navPoint id="n3" playOrder="3"><navLabel><text>第一编 绪论</text></navLabel><content src="Text/chap.xhtml"/>
    <navPoint id="n4" playOrder="4"><navLabel><text>第一章 甲</text></navLabel><content src="Text/chap.xhtml#c1"/></navPoint>
    <navPoint id="n5" playOrder="5"><navLabel><text>第二章 乙</text></navLabel><content src="Text/chap.xhtml#c2"/></navPoint>
  </navPoint>
  <navPoint id="n6" playOrder="6"><navLabel><text>第二编 基本情况</text></navLabel><content src="Text/chap.xhtml"/></navPoint>
</navMap>
</ncx>"""

        private const val CONTAINER = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

        private const val OPF_WITH_NAV = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Test Book</dc:title>
    <dc:creator>Author</dc:creator>
  </metadata>
  <manifest>
    <item id="chap1" href="chap1.xhtml" media-type="application/xhtml+xml"/>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="notes" href="notes.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="chap1"/>
    <itemref idref="nav" linear="no"/>
    <itemref idref="notes"/>
  </spine>
</package>"""

        private const val OPF_LINEAR_NO_NONNAV = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Test Book</dc:title>
  </metadata>
  <manifest>
    <item id="chap1" href="chap1.xhtml" media-type="application/xhtml+xml"/>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="appendix" href="appendix.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="chap1"/>
    <itemref idref="nav" linear="no"/>
    <itemref idref="appendix" linear="no"/>
  </spine>
</package>"""
    }
}
