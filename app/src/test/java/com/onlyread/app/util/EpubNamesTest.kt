package com.onlyread.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 外部「打开方式」入口的 EPUB 识别（纯 JVM）。 */
class EpubNamesTest {

    @Test
    fun looksLikeEpubAcceptsExtensionAndPath() {
        assertTrue(EpubNames.looksLikeEpub("book.epub"))
        assertTrue(EpubNames.looksLikeEpub("BOOK.EPUB"))
        assertTrue(EpubNames.looksLikeEpub("/sdcard/Download/古籍.epub"))
        assertTrue(EpubNames.looksLikeEpub("content://provider/document/42:book.epub"))
    }

    @Test
    fun looksLikeEpubRejectsNonEpub() {
        assertFalse(EpubNames.looksLikeEpub("book.zip"))
        assertFalse(EpubNames.looksLikeEpub("book.epub.txt"))
        assertFalse(EpubNames.looksLikeEpub("epub"))
        assertFalse(EpubNames.looksLikeEpub(""))
        assertFalse(EpubNames.looksLikeEpub("   "))
        assertFalse(EpubNames.looksLikeEpub(null))
    }

    @Test
    fun isEpubMimeAcceptsStandardType() {
        assertTrue(EpubNames.isEpubMime("application/epub+zip"))
        assertTrue(EpubNames.isEpubMime("APPLICATION/EPUB+ZIP"))
    }

    @Test
    fun isEpubMimeRejectsGenericBinary() {
        assertFalse(EpubNames.isEpubMime("application/octet-stream"))
        assertFalse(EpubNames.isEpubMime("*/*"))
        assertFalse(EpubNames.isEpubMime(""))
        assertFalse(EpubNames.isEpubMime(null))
    }
}
