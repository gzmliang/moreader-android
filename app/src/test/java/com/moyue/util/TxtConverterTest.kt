package com.moyue.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * TxtConverter（离线 TXT → EPUB）单元测试
 * 覆盖：编码识别 / 章节识别 / 段落还原 / 超长段切分 / 非文本拒绝 / 完整 EPUB 结构
 */
class TxtConverterTest {

    private fun tmpDir(): File {
        val d = File(System.getProperty("java.io.tmpdir"), "txtconv-test-${System.nanoTime()}")
        d.mkdirs()
        return d
    }

    // ── 编码识别 ──────────────────────────────────────────────

    @Test
    fun testDetectUtf8() {
        val text = "第一章 开端\n\n这是一个测试段落，包含中文标点。\n"
        val (decoded, enc) = TxtConverter.detectAndDecode(text.toByteArray(Charsets.UTF_8))
        assertEquals("utf-8", enc)
        assertEquals(text, decoded)
    }

    @Test
    fun testDetectUtf8Bom() {
        val text = "第一章 开端\n正文内容。\n"
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val (decoded, enc) = TxtConverter.detectAndDecode(bom + text.toByteArray(Charsets.UTF_8))
        assertEquals("utf-8-sig", enc)
        assertEquals(text, decoded)
    }

    @Test
    fun testDetectGbk() {
        // 用 GB18030 编码构造典型中文网文内容
        val src = "第一章　少年的黄昏\n他走在回家的路上，心里想着今天发生的事情。\n" +
            "那是一个很好的天气。\n" +
            "第二章　远方来信\n第二天，他收到了一封信。\n"
        val data = src.toByteArray(charset("GB18030"))
        val (decoded, enc) = TxtConverter.detectAndDecode(data)
        assertTrue("应为 gb18030，实际=$enc", enc == "gb18030")
        assertEquals(src, decoded)
    }

    @Test
    fun testDetectBig5() {
        val src = "第一章　少年\n他走在回家的路上，心裡想著今天發生的事情。\n" +
            "那是一個很好的天氣，風很輕。\n" +
            "第二章　遠方\n第二天，他收到了一封信，信上寫著。\n"
        val data = src.toByteArray(charset("Big5"))
        val (decoded, enc) = TxtConverter.detectAndDecode(data)
        assertTrue("应为 big5，实际=$enc", enc == "big5")
        assertEquals(src, decoded)
    }

    @Test
    fun testDetectUtf16Bom() {
        val text = "第一章 开始\n内容是中文。\n"
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val (decoded, enc) = TxtConverter.detectAndDecode(bom + text.toByteArray(Charsets.UTF_16LE))
        assertEquals("utf-16le", enc)
        assertEquals(text, decoded)
    }

    // ── 章节识别 ──────────────────────────────────────────────

    @Test
    fun testChapterTitleRecognition() {
        assertTrue(TxtConverter.isChapterTitle("第一章 开端"))
        assertTrue(TxtConverter.isChapterTitle("第12章"))
        assertTrue(TxtConverter.isChapterTitle("第十二回　王熙鳳毒設相思局"))
        assertTrue(TxtConverter.isChapterTitle("序章"))
        assertTrue(TxtConverter.isChapterTitle("序言"))
        assertTrue(TxtConverter.isChapterTitle("楔子"))
        assertTrue(TxtConverter.isChapterTitle("番外 3"))
        assertTrue(TxtConverter.isChapterTitle("Chapter 12"))
        assertTrue(TxtConverter.isChapterTitle("Chapter IV: The End"))
        // 裸单词 Prologue 与 Python 版行为一致：需跟编号/名称才算标题
        assertTrue(!TxtConverter.isChapterTitle("Prologue"))
    }

    @Test
    fun testChapterTitleRejectsBodyLines() {
        // 这几种是最容易误判成标题的正文行，必须拒绝
        assertTrue(!TxtConverter.isChapterTitle("楔子内容。"))
        assertTrue(!TxtConverter.isChapterTitle("第一章 他走进屋子，看到了她。"))
        assertTrue(!TxtConverter.isChapterTitle("第二天他就走了。"))
        assertTrue(!TxtConverter.isChapterTitle(""))
        assertTrue(!TxtConverter.isChapterTitle("这一段是普通正文，没有任何章节特征。"))
    }

    // ── 段落还原 ──────────────────────────────────────────────

    @Test
    fun testOneLinePerParagraph() {
        val lines = listOf("第一段话。", "第二段话。", "第三段话。")
        val paras = TxtConverter.buildParagraphs(lines, "zh")
        assertEquals(3, paras.size)
        assertEquals("第一段话。", paras[0])
    }

    @Test
    fun testHardWrappedLinesMerged() {
        // 老式 TXT：每行都不是完整句子（无句末标点）→ 应接回一整段
        val lines = listOf(
            "他走在回家的路上心里想着",
            "今天发生的所有事情觉得",
            "还是有些难以接受。",
            "但是",
            "太阳照常升起。"
        )
        val paras = TxtConverter.buildParagraphs(lines, "zh")
        assertEquals("应合并为 2 段，实际=${paras.size} -> $paras", 2, paras.size)
        assertEquals("他走在回家的路上心里想着今天发生的所有事情觉得还是有些难以接受。", paras[0])
        assertEquals("但是太阳照常升起。", paras[1])
    }

    @Test
    fun testEnglishBlankLineBlocks() {
        val lines = listOf(
            "It was a bright cold day in April,",
            "and the clocks were striking thirteen.",
            "",
            "Winston Smith, his chin nuzzled into his breast,",
            "slipped quickly through the glass doors."
        )
        val paras = TxtConverter.buildParagraphs(lines, "en")
        assertEquals(2, paras.size)
        assertEquals("It was a bright cold day in April, and the clocks were striking thirteen.", paras[0])
    }

    @Test
    fun testOverlongParagraphSplit() {
        // 一段 3000 字，必须按句切成多段，每段 ≤ 1200
        val sentence = "这是一句用来测试超长段落切分的中文句子。"
        val long = sentence.repeat(120) // 24 * 120 = 2880 字
        val paras = TxtConverter.splitOverlongParagraphs(listOf(long))
        assertTrue("应切成多段，实际=${paras.size} 长度=${paras.map { it.length }}", paras.size >= 2)
        assertTrue("每段应 ≤1200 字，实际=${paras.map { it.length }}", paras.all { it.length <= 1200 })
        assertEquals("切分不能丢字", long, paras.joinToString(""))
    }

    // ── 分章 ─────────────────────────────────────────────────

    @Test
    fun testSplitByHeadings() {
        val text = """
            第一章 开端

            他是主角。他在走路。

            第二章 转折

            他停下来。他回头看。

            第三章 结局

            他走了。
        """.trimIndent()
        val (chapters, seg) = TxtConverter.splitIntoChapters(text, "zh")
        assertEquals("heading", seg)
        assertEquals(3, chapters.size)
        assertEquals("第一章 开端", chapters[0].title)
        assertEquals("第二章 转折", chapters[1].title)
    }

    @Test
    fun testAutoSplitWithoutHeadings() {
        val text = (1..130).joinToString("\n\n") { "这是第 $it 段普通正文内容。" }
        val (chapters, seg) = TxtConverter.splitIntoChapters(text, "zh")
        assertEquals("auto", seg)
        assertTrue("130 段 / 每章 60 段 → 应至少 3 章，实际=${chapters.size}", chapters.size >= 3)
        assertEquals("正文", chapters[0].title)
        assertEquals("第 2 节", chapters[1].title)
    }

    // ── 完整转换 ──────────────────────────────────────────────

    @Test
    fun testConvertProducesValidEpub() {
        val dir = tmpDir()
        val txt = File(dir, "测试小说.txt")
        val content = buildString {
            appendLine("第一章 开端")
            appendLine()
            for (i in 1..30) appendLine("第 $i 段：他继续向前走，心里什么也没想。")
            appendLine()
            appendLine("第二章 结尾")
            appendLine()
            for (i in 1..30) appendLine("第 $i 段：故事在这里结束了。")
        }
        txt.writeText(content, Charsets.UTF_8)

        val out = File(dir, "out.epub")
        val stats = TxtConverter.convertTxtToEpub(txt, out, title = "测试小说")

        assertTrue(out.exists() && out.length() > 0)
        assertEquals("测试小说", stats.title)
        assertEquals(2, stats.chapters)
        assertEquals(60, stats.paragraphs)
        assertEquals("utf-8", stats.encoding)
        assertEquals("heading", stats.segmentation)
        assertEquals("zh", stats.language)

        ZipFile(out).use { zip ->
            assertNotNull("必须有 mimetype", zip.getEntry("mimetype"))
            assertEquals(
                "application/epub+zip",
                zip.getInputStream(zip.getEntry("mimetype")).readBytes().toString(Charsets.US_ASCII)
            )
            assertNotNull("必须有 container.xml", zip.getEntry("META-INF/container.xml"))
            assertNotNull("必须有 content.opf", zip.getEntry("OEBPS/content.opf"))
            assertNotNull("必须有 toc.ncx", zip.getEntry("OEBPS/toc.ncx"))
            assertNotNull("必须有 chapter_001", zip.getEntry("OEBPS/text/chapter_001.xhtml"))
            assertNotNull("必须有 chapter_002", zip.getEntry("OEBPS/text/chapter_002.xhtml"))

            val opf = zip.getInputStream(zip.getEntry("OEBPS/content.opf")).readBytes().toString(Charsets.UTF_8)
            assertTrue("opf 应含书名", opf.contains("<dc:title>测试小说</dc:title>"))

            val c1 = zip.getInputStream(zip.getEntry("OEBPS/text/chapter_001.xhtml"))
                .readBytes().toString(Charsets.UTF_8)
            assertTrue("章节内应有段落", c1.contains("<p>"))
            assertTrue("章节标题应写入 title", c1.contains("<title>第一章 开端</title>"))
        }
    }

    @Test
    fun testGbkFileEndToEnd() {
        val dir = tmpDir()
        val txt = File(dir, "gbk书.txt")
        val content = "第一章　开始\n\n他走在路上，天已经黑了。\n\n第二章　结束\n\n他到家了，灯亮着。\n"
        txt.writeBytes(content.toByteArray(charset("GB18030")))

        val out = File(dir, "gbk.epub")
        val stats = TxtConverter.convertTxtToEpub(txt, out)
        assertTrue("编码应识别为 gb18030，实际=${stats.encoding}", stats.encoding == "gb18030")
        assertEquals(2, stats.chapters)
        assertTrue(out.exists() && out.length() > 0)
    }

    @Test
    fun testRejectsBinaryFile() {
        val dir = tmpDir()
        val txt = File(dir, "fake.txt")
        // 随机二进制（大量控制字符）
        val data = ByteArray(4096) { (it % 31).toByte() }
        txt.writeBytes(data)
        try {
            TxtConverter.convertTxtToEpub(txt, File(dir, "x.epub"))
            fail("二进制文件应当抛 TxtNotTextException")
        } catch (e: TxtConverter.TxtNotTextException) {
            // 预期
        }
    }

    @Test
    fun testRejectsEmptyFile() {
        val dir = tmpDir()
        val txt = File(dir, "empty.txt")
        txt.writeBytes(ByteArray(0))
        try {
            TxtConverter.convertTxtToEpub(txt, File(dir, "x.epub"))
            fail("空文件应当抛 TxtEmptyException")
        } catch (e: TxtConverter.TxtEmptyException) {
            // 预期
        }
    }

    @Test
    fun testHumanizeTitle() {
        assertEquals("斗破苍穹", TxtConverter.humanizeTitle("斗破苍穹.txt"))
        assertEquals("三体", TxtConverter.humanizeTitle("三体_全本_精校版.txt"))
        assertEquals("The Great Gatsby", TxtConverter.humanizeTitle("The Great Gatsby.txt"))
        // ISBN 与水印词要剔除
        assertEquals("活着", TxtConverter.humanizeTitle("活着_9787506365437_www.com.txt"))
    }

    @Test
    fun testBookTitleFromHeaderLine() {
        val dir = tmpDir()
        val txt = File(dir, "some_random_file.txt")
        val content = "《我的书名》\n\n第一章 开始\n\n正文内容在这里，说完就没了。\n"
        txt.writeText(content, Charsets.UTF_8)
        val out = File(dir, "t.epub")
        val stats = TxtConverter.convertTxtToEpub(txt, out)
        assertEquals("我的书名", stats.title)
        assertEquals(1, stats.chapters)
    }
}

class TxtConverterTitleTest {
    @Test
    fun testStripInternalImportPrefix() {
        // 本地导入时会先复制成 import_<时间戳>_原文件名
        assertEquals("t1 gbk", TxtConverter.titleFromFileName("import_1794173925667_t1_gbk.txt"))
        assertEquals("暗巷", TxtConverter.titleFromFileName("import_1794173925667_暗巷.txt"))
        assertEquals("斗破苍穹", TxtConverter.titleFromFileName("webdav_txt_1794173925667_斗破苍穹.txt"))
        // 没有前缀的真实文件名照常处理
        assertEquals("三体", TxtConverter.titleFromFileName("三体.txt"))
        assertEquals("The Great Gatsby", TxtConverter.titleFromFileName("The Great Gatsby.txt"))
    }
}
