package com.moyue.util

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MobiConverterTest {

    @Test
    fun testPalmDocDecompress() {
        // 构建简单的 PalmDOC 压缩数据：
        // 0x05 + "Hello" (literal count 5)
        // 0x20 (literal ' ')
        // 0xC0 + ('W' ^ 0x80) (space + 'W') -> " W"
        // 0x04 + "orld"
        // 0x80..0xBF: distance length pair (copy "Hello")
        val data = byteArrayOf(
            5, 'H'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte(),
            0x20, // ' '
            (0xC0 or ('W'.code xor 0x80)).toByte(), // " W"
            4, 'o'.code.toByte(), 'r'.code.toByte(), 'l'.code.toByte(), 'd'.code.toByte()
        )
        val decompressed = MobiConverter.decompressPalmDoc(data)
        val text = String(decompressed, Charsets.UTF_8)
        assertEquals("Hello  World", text)
    }

    @Test
    fun testMultiChapterMobiSplit() {
        val sampleMobi = File("/tmp/test_multi_chapter.mobi")
        // 夹具是手工投放的样本，机器清理 /tmp 后不存在；缺失时跳过而不是判失败
        org.junit.Assume.assumeTrue("需要手工夹具 /tmp/test_multi_chapter.mobi", sampleMobi.exists())

        val outEpub = File("/tmp/converted_multi_chapter.epub")
        if (outEpub.exists()) outEpub.delete()

        MobiConverter.convertMobiToEpub(sampleMobi, outEpub)
        assertTrue(outEpub.exists())

        // 验证生成的 EPUB 内部是否切出了多个 chapter_xxx.xhtml，且 NCX 具有相应章节
        val zip = java.util.zip.ZipFile(outEpub)
        assertNotNull(zip.getEntry("OEBPS/text/chapter_001.xhtml"))
        assertNotNull(zip.getEntry("OEBPS/text/chapter_002.xhtml"))
        assertNotNull(zip.getEntry("OEBPS/text/chapter_003.xhtml"))

        val ncx = zip.getInputStream(zip.getEntry("OEBPS/toc.ncx")).bufferedReader().readText()
        assertTrue(ncx.contains("Chapter 1"))
        assertTrue(ncx.contains("Chapter 2"))
        assertTrue(ncx.contains("Chapter 3"))
        zip.close()
    }
}
