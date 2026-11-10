package com.moyue.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF「点哪读哪」的纯算法层：组行 / 空格 / 句子命中 / 坐标容错。
 *
 * 说明：PdfBox-Android 的字形资源只认 Android AssetManager（getStream 里没有
 * classpath 兜底），所以真正的 PDF 解析只能在设备上跑；这里用手工构造的
 * CharBox 把最容易出错的那部分逻辑（分块、排序、断句、容错）全覆盖。
 */
class PdfTextLayerTest {

    /** 造一行：给定 y、起始 x、每个字符宽 w，把字符串摊成字符盒 */
    private fun lineChars(text: String, x: Float, y: Float, w: Float = 8f, h: Float = 12f): List<PdfTextLayer.CharBox> =
        text.mapIndexed { i, c ->
            PdfTextLayer.CharBox(c.toString(), x + i * w, y, w, h)
        }

    private fun page(vararg lines: List<PdfTextLayer.CharBox>): PdfTextLayer.PageText =
        PdfTextLayer.fromChars(lines.toList().flatten(), 595f, 842f)

    // ── 组行 ──────────────────────────────────────────────────

    @Test
    fun testGroupsScrambledCharsIntoLines() {
        val l1 = lineChars("Hello world", 60f, 100f)
        val l2 = lineChars("Second line", 60f, 130f)
        val l3 = lineChars("Third line", 60f, 160f)
        // 故意打乱顺序（PDF 里字符流未必按阅读顺序给出）
        val scrambled = (l1 + l2 + l3).shuffled(java.util.Random(7))

        val page = page(scrambled)
        assertEquals("乱序输入也应分出 3 行，实际=${page.lines.map { it.text }}", 3, page.lines.size)
        assertEquals("Hello world", page.lines[0].text)
        assertEquals("Second line", page.lines[1].text)
        assertEquals("Third line", page.lines[2].text)
    }

    @Test
    fun testSameLineCharsSortedByX() {
        val chars = listOf(
            PdfTextLayer.CharBox("C", 76f, 100f, 8f, 12f),
            PdfTextLayer.CharBox("A", 60f, 100f, 8f, 12f),
            PdfTextLayer.CharBox("B", 68f, 100f, 8f, 12f),
        )
        val page = page(chars)
        assertEquals(1, page.lines.size)
        assertEquals("ABC", page.lines[0].text)
    }

    @Test
    fun testSlightYJitterStaysInSameLine() {
        // 同一行里字符基线常有几个 pt 抖动，不能因此拆成多行
        val chars = lineChars("abcdefg", 60f, 100f) .mapIndexed { i, c ->
            if (i % 2 == 0) c else c.copy(y = c.y + 2.5f)
        }
        val page = page(chars)
        assertEquals("轻微 y 抖动不应拆行，实际=${page.lines.size} 行", 1, page.lines.size)
    }

    // ── 空格 ──────────────────────────────────────────────────

    @Test
    fun testChineseLineHasNoSpuriousSpaces() {
        val chars = lineChars("清晨的光从窗子里慢慢透进来", 60f, 100f, w = 12f)
        val page = page(chars)
        assertEquals("清晨的光从窗子里慢慢透进来", page.lines[0].text)
        assertTrue(!page.lines[0].text.contains("  "))
    }

    @Test
    fun testEnglishGapInsertsSpace() {
        // 模拟 PDF 里没有空格字符、只靠间距表达词间空白
        val a = lineChars("Reading", 60f, 100f, w = 8f)
        val b = lineChars("books", 60f + 7 * 8f + 12f, 100f, w = 8f)
        val page = page(a + b)
        assertEquals("Reading books", page.lines[0].text)
    }

    // ── 句子命中 ──────────────────────────────────────────────

    @Test
    fun testHitReturnsSentenceContainingClickPoint() {
        val chars = lineChars("第一句话。第二句话在这里。第三句话。", 60f, 100f, w = 12f)
        val page = page(chars)
        // 点在「第二句话在这里」中间
        val hit = PdfTextLayer.sentenceAt(page, 60f + 12f * 8, 100f + 6f, 0)
        assertNotNull(hit)
        assertEquals("第二句话在这里。", hit!!.sentence)
        assertEquals(0, hit.pageIndex)
    }

    @Test
    fun testHitFirstSentenceAtLineStart() {
        val chars = lineChars("开始。然后结束。", 60f, 100f, w = 12f)
        val page = page(chars)
        val hit = PdfTextLayer.sentenceAt(page, 62f, 106f, 2)!!
        assertEquals("开始。", hit.sentence)
        assertEquals(2, hit.pageIndex)
    }

    @Test
    fun testHitFallsBackToNearestLineWhenYIsOff() {
        val top = lineChars("上面这一行。", 60f, 100f, w = 12f)
        val bottom = lineChars("下面这一行。", 60f, 160f, w = 12f)
        val page = page(top, bottom)
        // 点在两行之间、更靠近下面一行
        val hit = PdfTextLayer.sentenceAt(page, 70f, 150f, 0)
        assertNotNull("偏离行范围仍应命中最近行", hit)
        assertTrue("应命中下面那行，实际=${hit!!.sentence}", hit.sentence.contains("下面这一行"))
    }

    @Test
    fun testHitOnEmptyPageReturnsNull() {
        val page = PdfTextLayer.fromChars(emptyList(), 595f, 842f)
        assertNull("空页应返回 null（上层提示没有文字层）", PdfTextLayer.sentenceAt(page, 100f, 100f, 0))
    }

    @Test
    fun testHitSentenceIsBounded() {
        // 一整行 600 字没有句号：必须被截断，不能把整页塞进卡片
        val longText = "这是一段没有句号的超长文字".repeat(50)
        val chars = lineChars(longText, 60f, 100f, w = 12f)
        val page = page(chars)
        val hit = PdfTextLayer.sentenceAt(page, 100f, 106f, 0)!!
        assertTrue("无标点时句子长度应有上限，实际=${hit.sentence.length}", hit.sentence.length <= 300)
        assertTrue(hit.sentence.isNotBlank())
    }

    @Test
    fun testEnglishSentenceSplitsOnPeriod() {
        val text = "First sentence. Second sentence here. Third one."
        val chars = lineChars(text, 60f, 100f, w = 7f)
        val page = page(chars)
        val hit = PdfTextLayer.sentenceAt(page, 60f + 7f * 20, 106f, 0)!!
        assertTrue("应定位到第二句，实际=${hit.sentence}", hit.sentence.contains("Second sentence here"))
        assertTrue("不应混入第一句，实际=${hit.sentence}", !hit.sentence.contains("First sentence"))
    }

    @Test
    fun testExplicitSpaceCharIsNotDuplicated() {
        // 真实 PDF 会带空格字符；不能因为间距又补一个空格
        val chars = lineChars("Reading", 60f, 100f, w = 8f) +
            listOf(PdfTextLayer.CharBox(" ", 116f, 100f, 4f, 12f)) +
            lineChars("books", 120f, 100f, w = 8f)
        val page = page(chars)
        assertEquals("Reading books", page.lines[0].text)
        assertTrue("不应出现双空格：${page.lines[0].text}", !page.lines[0].text.contains("  "))
    }

    @Test
    fun testWordsGluedWithoutSpaceCharStillSeparated() {
        // 有些 PDF 不产出空格字符，只能靠间距补空格
        val a = lineChars("Thisparagraphexists", 60f, 100f, w = 8f)
        val b = lineChars("so", 60f + 19 * 8f + 11f, 100f, w = 8f)
        val page = page(a + b)
        assertEquals("Thisparagraphexists so", page.lines[0].text)
    }

    // ── 划线选区（长按选词 / 拖动扩选） ────────────────────────

    @Test
    fun testWordRangeSelectsWholeEnglishWord() {
        val chars = lineChars("The quick brown fox", 60f, 100f, w = 8f)
        val page = page(chars)
        val flat = PdfTextLayer.flattened(page)
        val idx = flat.indexOfFirst { it.text == "q" }
        val (a, b) = PdfTextLayer.wordRange(page, idx)
        assertEquals("quick", flat.subList(a, b + 1).joinToString("") { it.text })
    }

    @Test
    fun testWordRangeForChineseSelectsClause() {
        val chars = lineChars("他走了，她没有说话。", 60f, 100f, w = 12f)
        val page = page(chars)
        val flat = PdfTextLayer.flattened(page)
        val idx = flat.indexOfFirst { it.text == "她" }
        val (a, b) = PdfTextLayer.wordRange(page, idx)
        val text = flat.subList(a, b + 1).joinToString("") { it.text }
        assertTrue("中文应选中该分句，实际=$text", text.contains("她没有说话"))
        assertTrue("不应跨过句号，实际=$text", !text.contains("他走了"))
    }

    @Test
    fun testCharIndexAtMapsToNearestChar() {
        val chars = lineChars("abcdef", 60f, 100f, w = 10f)
        val page = page(chars)
        val idx = PdfTextLayer.charIndexAt(page, 62f, 106f)
        assertNotNull(idx)
        assertEquals("a", PdfTextLayer.flattened(page)[idx!!].text)
    }

    @Test
    fun testResolveRangeAcrossTwoLines() {
        val l1 = lineChars("First line here", 60f, 100f, w = 8f)
        val l2 = lineChars("second line", 60f, 130f, w = 8f)
        val page = page(l1, l2)
        val flat = PdfTextLayer.flattened(page)
        val startIdx = flat.indexOfFirst { it.text == "h" }
        val endIdx = flat.indexOfFirst { it.text == "c" } // second 的 c
        val sel = PdfTextLayer.resolveRange(page, startIdx, endIdx)
        assertNotNull(sel)
        assertTrue("应跨行选中，实际=${sel!!.text}", sel.text.contains("here") && sel.text.contains("sec"))
        assertEquals("跨两行应产生 2 个高亮矩形", 2, sel.rects.size)
    }

    @Test
    fun testResolveRangeHandlesReversedIndices() {
        val chars = lineChars("alpha beta gamma", 60f, 100f, w = 8f)
        val page = page(chars)
        val flat = PdfTextLayer.flattened(page)
        val a = flat.indexOfFirst { it.text == "g" }
        val b = flat.indexOfFirst { it.text == "a" }
        val forward = PdfTextLayer.resolveRange(page, a, b)
        val backward = PdfTextLayer.resolveRange(page, b, a)
        assertEquals("反向索引应与正向一致", forward!!.text, backward!!.text)
    }

    @Test
    fun testResolveRangeEmptyWhenNoOverlap() {
        val chars = lineChars("abc", 60f, 100f, w = 8f)
        val page = page(chars)
        assertNull("超出范围的索引区间应返回 null", PdfTextLayer.resolveRange(page, 50, 60))
    }

    @Test
    fun testWordRangeStopsAtSpacingWhenNoSpaceChar() {
        // 真实论文 PDF 常见：没有空格字符，词间只有间距。
        // 必须靠间距切词，否则会把整句选出来（线上实测踩到的坑）。
        val w1 = lineChars("Grounded", 60f, 100f, w = 8f)
        val w2 = lineChars("rewards", 60f + 8 * 8f + 4f, 100f, w = 8f)
        val page = page(w1 + w2)
        val flat = PdfTextLayer.flattened(page)
        val idx = flat.indexOfLast { it.text == "r" }  // rewards 开头的 r（Grounded 里也有 r，用 last）
        val (a, b) = PdfTextLayer.wordRange(page, idx)
        assertEquals(
            "应只选中 rewards，而不是整句",
            "rewards",
            flat.subList(a, b + 1).joinToString("") { it.text },
        )
    }

    @Test
    fun testWordRangeKeepsWholeWordWhenCharsAreTightlyPacked() {
        // 同一单词内部字符紧挨（gap=0），不能被误切成单个字母
        val chars = lineChars("rewards", 60f, 100f, w = 8f)
        val page = page(chars)
        val flat = PdfTextLayer.flattened(page)
        val idx = flat.indexOfFirst { it.text == "a" }
        val (a, b) = PdfTextLayer.wordRange(page, idx)
        assertEquals("rewards", flat.subList(a, b + 1).joinToString("") { it.text })
    }

    // ── 整页朗读：句子切分 ─────────────────────────────────────

    @Test
    fun testSentencesSplitByPunctuation() {
        val chars = lineChars("第一句。第二句！第三句？", 60f, 100f, w = 12f)
        val page = page(chars)
        val sents = PdfTextLayer.sentences(page)
        assertEquals(3, sents.size)
        assertEquals("第一句。", sents[0].text)
        assertEquals("第二句！", sents[1].text)
        assertEquals("第三句？", sents[2].text)
    }

    @Test
    fun testSentencesMergeAcrossLines() {
        val l1 = lineChars("这是一句很长的话", 60f, 100f, w = 12f)
        val l2 = lineChars("话接着说完。", 60f, 130f, w = 12f)
        val page = page(l1, l2)
        val sents = PdfTextLayer.sentences(page)
        assertEquals(1, sents.size)
        assertTrue("跨行应合并为一句：${sents[0].text}", sents[0].text.contains("接着说完"))
    }

    @Test
    fun testSentencesSkipPurePunctuation() {
        val chars = lineChars("。。。Hi there. 。。。", 60f, 100f, w = 10f)
        val page = page(chars)
        val sents = PdfTextLayer.sentences(page)
        assertTrue("不应产出只有标点的句子", sents.all { it.text.any { c -> c.isLetterOrDigit() } })
    }
}
