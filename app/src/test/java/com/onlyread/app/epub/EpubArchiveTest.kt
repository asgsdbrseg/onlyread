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

    companion object {
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
