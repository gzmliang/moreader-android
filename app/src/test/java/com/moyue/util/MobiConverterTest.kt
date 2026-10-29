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
    fun testParseMobiAndConvertToEpub() {
        val sampleMobi = File("/tmp/test_sample.mobi")
        assertTrue("Test mobi must exist", sampleMobi.exists())

        assertTrue("isMobiFile check", MobiConverter.isMobiFile(sampleMobi))

        val parsed = MobiConverter.parseMobi(sampleMobi)
        assertEquals("My Test Mobi Book", parsed.title)
        assertEquals("Moyue Author", parsed.author)
        assertEquals(1, parsed.images.size)
        assertTrue(parsed.html.contains("images/image_00001.gif"))

        val outEpub = File("/tmp/converted_test.epub")
        if (outEpub.exists()) outEpub.delete()

        MobiConverter.convertMobiToEpub(sampleMobi, outEpub)
        assertTrue(outEpub.exists())
        assertTrue(outEpub.length() > 500)
    }
}
