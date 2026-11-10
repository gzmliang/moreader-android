package com.moyue.reader

import android.content.Context
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/**
 * PDF 原版阅读的「点哪读哪」文字层
 *
 * 背景：Android 自带的 android.graphics.pdf.PdfRenderer 只能把页面渲染成 Bitmap，
 * 没有任何文字提取接口 —— 所以原版阅读一直是「看不清字」的图片，无法选词翻译/朗读。
 *
 * 这里用 PdfBox-Android 单独提取每一页的字符与坐标（pt，原点在页面左上），
 * 命中点击位置所在的行与句子后交给上层弹翻译/朗读卡片。渲染链路完全不动。
 *
 * 只对「有文字层」的 PDF 有效：扫描版提取不到字符，返回 null，上层静默降级。
 */
object PdfTextLayer {

    private const val TAG = "PdfTextLayer"

    data class CharBox(
        val text: String,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
    )

    data class Line(val chars: List<CharBox>) {
        val yTop: Float get() = chars.minOf { it.y }
        val yBottom: Float get() = chars.maxOf { it.y + it.h }
        val text: String by lazy { buildLineText(chars) }
    }

    data class PageText(
        val widthPt: Float,
        val heightPt: Float,
        val lines: List<Line>,
    ) {
        val isEmpty: Boolean get() = lines.isEmpty()
    }

    data class Hit(val sentence: String, val lineText: String, val pageIndex: Int)

    @Volatile
    private var inited = false

    private fun initOnce(context: Context?) {
        if (inited) return
        synchronized(this) {
            if (inited) return
            runCatching { PDFBoxResourceLoader.init(context?.applicationContext) }
                .onFailure { logW("PDFBoxResourceLoader.init 失败（JVM 单测下无 Context 属正常）", it) }
            inited = true
        }
    }

    /** android.util.Log 在 JVM 单测里是桩实现（抛异常），这里统一吞掉，保证核心逻辑可离线自测 */
    private fun logD(msg: String) {
        runCatching { Log.d(TAG, msg) }
    }

    private fun logW(msg: String, t: Throwable? = null) {
        runCatching { if (t != null) Log.w(TAG, msg, t) else Log.w(TAG, msg) }
    }

    // ── 缓存：页文字层最多 12 页；PDDocument 单实例复用（避免每次点击都重新解析 xref） ──
    private val pageCache = LinkedHashMap<String, PageText?>(16, 0.75f, true)
    private const val PAGE_CACHE_MAX = 12

    /** 最近一次提取失败原因（便于 JVM 单测/排障定位，正常流程不影响） */
    @Volatile
    var lastError: String? = null
        private set

    private var docKey: String? = null
    private var doc: PDDocument? = null

    private fun docFor(file: File, context: Context?): PDDocument? {
        initOnce(context)
        val key = file.absolutePath + ":" + file.length() + ":" + file.lastModified()
        doc?.let { d ->
            if (docKey == key) {
                val ok = runCatching { d.numberOfPages }.isSuccess
                if (ok) return d
            }
            runCatching { d.close() }
        }
        doc = null
        docKey = null
        return try {
            val d = PDDocument.load(file)
            doc = d
            docKey = key
            d
        } catch (e: InvalidPasswordException) {
            logD("加密 PDF，跳过文字层")
            null
        } catch (e: Throwable) {
            logW("打开 PDF 失败：${e.message}", e)
            null
        }
    }

    fun close() {
        runCatching { doc?.close() }
        doc = null
        docKey = null
        pageCache.clear()
    }

    /** 提取单页文字层。无文字层（扫描版）/ 解析失败返回 null。 */
    fun extract(file: File, pageIndex: Int, context: Context? = null): PageText? {
        val key = file.absolutePath + "#" + pageIndex
        if (pageCache.containsKey(key)) return pageCache[key]

        val result = runCatching { doExtract(file, pageIndex, context) }
            .onFailure {
                lastError = it.toString() + " @ " + it.stackTrace.take(4).joinToString(" | ")
                logW("提取第 $pageIndex 页失败", it)
            }
            .getOrNull()
        if (pageCache.size >= PAGE_CACHE_MAX) {
            val eldest = pageCache.keys.firstOrNull()
            if (eldest != null) pageCache.remove(eldest)
        }
        pageCache[key] = result
        return result
    }

    private fun doExtract(file: File, pageIndex: Int, context: Context?): PageText? {
        val document = docFor(file, context) ?: return null
        val page = document.getPage(pageIndex)
        val heightPt = page.cropBox.height
        val widthPt = page.cropBox.width

        val chars = ArrayList<CharBox>(1024)
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: List<TextPosition>) {
                for (tp in textPositions) {
                    val rawIn = tp.unicode ?: continue
                    if (rawIn.isEmpty()) continue
                    // PDF 文字层常带零宽字符 / 软连字符 / 控制符，直接送 TTS 会让微软
                    // 返回空音频（服务端 500: No audio was received），必须在这里清掉。
                    val raw = rawIn.replace(INVISIBLE_RE, "")
                    if (raw.isEmpty()) continue
                    // 空格字符必须保留：英文词间空白主要靠它表达，丢掉会粘连成 Thisparagraphexists
                    val t = if (raw.isBlank()) " " else raw
                    val w = tp.widthDirAdj
                    val h = tp.heightDir
                    if (w <= 0f && h <= 0f) continue
                    chars.add(CharBox(t, tp.xDirAdj, tp.yDirAdj, w, h))
                }
            }
        }
        stripper.sortByPosition = true
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        stripper.getText(document)

        if (chars.isEmpty()) return PageText(widthPt, heightPt, emptyList())
        return PageText(widthPt, heightPt, groupLines(chars))
    }

    /** 按 y 聚类成行（同一行内字符的 y 通常只差几个 pt），行内按 x 排序。 */
    /**
     * 用现成字符列表构造一页文字层（不触碰 PdfBox）。
     * 供算法单元测试与「外部已有文字层」的复用场景使用。
     */
    fun fromChars(chars: List<CharBox>, widthPt: Float, heightPt: Float): PageText =
        PageText(widthPt, heightPt, groupLines(chars))

    internal fun groupLines(chars: List<CharBox>): List<Line> {
        val sorted = chars.sortedWith(compareBy({ it.y }, { it.x }))
        val lines = ArrayList<Line>(64)
        var current = ArrayList<CharBox>()
        var baseY = Float.NaN
        var tolerance = 2f

        for (c in sorted) {
            if (current.isEmpty()) {
                current.add(c)
                baseY = c.y
                tolerance = (c.h * 0.5f).coerceAtLeast(2f)
                continue
            }
            if (kotlin.math.abs(c.y - baseY) <= tolerance) {
                current.add(c)
            } else {
                lines.add(Line(current.sortedBy { it.x }))
                current = ArrayList()
                current.add(c)
                baseY = c.y
                tolerance = (c.h * 0.5f).coerceAtLeast(2f)
            }
        }
        if (current.isNotEmpty()) lines.add(Line(current.sortedBy { it.x }))
        return lines.filter { it.chars.isNotEmpty() }
    }

    internal fun buildLineText(chars: List<CharBox>): String {
        val sb = StringBuilder()
        var prevEnd = Float.NaN
        for (c in chars) {
            // 真实空格字符：落成单个空格，不再靠间距推断（否则会把词粘在一起）
            if (c.text.isBlank()) {
                if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                prevEnd = c.x + c.w
                continue
            }
            if (!prevEnd.isNaN() && sb.isNotEmpty()) {
                val gap = c.x - prevEnd
                val last = sb.last()
                val first = c.text.first()
                // 部分 PDF 不产出空格字符，只能靠间距补空格。
                // 拉丁字母之间用 0.35 字号（否则会出现 rewardsmay / humanusercould），
                // 其他情况保持保守的 0.6，避免中文被插出多余空格。
                val latinPair = last.isLetterOrDigit() && first.isLetterOrDigit() &&
                    !isCjkChar(last) && !isCjkChar(first)
                val factor = if (latinPair) 0.35f else 0.6f
                if (gap > (c.w * factor).coerceAtLeast(1.0f) && !last.isWhitespace() &&
                    !first.isWhitespace()
                ) {
                    sb.append(' ')
                }
            }
            sb.append(c.text)
            prevEnd = c.x + c.w
        }
        return sb.toString().replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    /**
     * 点击命中：把 PDF 坐标（pt，左上原点）换算成「所在行 + 该行里这一句」。
     * @param xPt 点击点 x（pt）
     * @param yPt 点击点 y（pt，从页面顶部算）
     */
    fun sentenceAt(page: PageText, xPt: Float, yPt: Float, pageIndex: Int = 0): Hit? {
        if (page.isEmpty) return null

        // 1) 先找 y 落在行内的；都没有就取距离最近的一行（点偏了一点也能读）
        val line = page.lines.firstOrNull { yPt >= it.yTop - 3f && yPt <= it.yBottom + 3f }
            ?: page.lines.minByOrNull {
                val cy = (it.yTop + it.yBottom) / 2f
                kotlin.math.abs(cy - yPt)
            } ?: return null

        // 2) 行内找最近的字符
        var bestIdx = 0
        var bestDist = Float.MAX_VALUE
        for ((i, c) in line.chars.withIndex()) {
            val d = when {
                xPt < c.x -> c.x - xPt
                xPt > c.x + c.w -> xPt - (c.x + c.w)
                else -> 0f
            }
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }

        val lineText = line.text
        if (lineText.isBlank()) return null

        // 3) 以命中的字符为中心，按标点向两侧扩成一句（限制长度，避免整页被选中）
        val sentence = expandSentence(line.chars, bestIdx)
        return Hit(
            sentence = sentence.ifBlank { lineText },
            lineText = lineText,
            pageIndex = pageIndex,
        )
    }

    // ── 整页朗读用的句子切分 ───────────────────────────────────

    /** 一句话：text + 在展平字符列表中的区间（用于高亮） */
    data class Sentence(val text: String, val start: Int, val end: Int)

    /**
     * 把整页文字切成句子（按句末标点），跨行的句子会合并成一句。
     * 用于「朗读本页」逐句播报 + 跟随高亮。
     */
    fun sentences(page: PageText): List<Sentence> {
        if (page.isEmpty) return emptyList()
        val out = mutableListOf<Sentence>()
        var curStart = -1
        var curEnd = -1
        var base = 0

        fun emit() {
            if (curStart < 0 || curEnd < 0) return
            val sel = resolveRange(page, curStart, curEnd)
            val text = sel?.text?.trim().orEmpty()
            if (text.isNotEmpty() && text.any { it.isLetterOrDigit() || isCjkChar(it) }) {
                out.add(Sentence(text, curStart, curEnd))
            }
            curStart = -1
            curEnd = -1
        }

        for (line in page.lines) {
            for (i in line.chars.indices) {
                if (curStart < 0) curStart = base + i
                curEnd = base + i
                val ch = line.chars[i].text
                if (ch.any { it in SENT_BREAK }) emit()
            }
            base += line.chars.size
        }
        emit()
        return out
    }

    // ── 划线选区（长按选词 / 拖动扩选） ─────────────────────────

    /** 页内一个高亮矩形（pt 坐标，原点页面左上） */
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** 一次划线选区：文本 + 每行一个高亮矩形 */
    data class Selection(
        val pageIndex: Int,
        val text: String,
        val rects: List<Rect>,
        val pageWidthPt: Float,
        val pageHeightPt: Float,
    )

    /** 按阅读顺序（行优先、行内按 x）展平的字符列表，索引即选区位次 */
    fun flattened(page: PageText): List<CharBox> = page.lines.flatMap { it.chars }

    /** 点击/长按点 → 最近的字符在展平列表中的索引 */
    fun charIndexAt(page: PageText, xPt: Float, yPt: Float): Int? {
        if (page.isEmpty) return null
        var base = 0
        var bestLine = -1
        var bestDist = Float.MAX_VALUE
        for ((li, line) in page.lines.withIndex()) {
            val cy = (line.yTop + line.yBottom) / 2f
            val dy = kotlin.math.abs(cy - yPt)
            if (dy < bestDist) {
                bestDist = dy
                bestLine = li
            }
        }
        if (bestLine < 0) return null
        for (i in 0 until bestLine) base += page.lines[i].chars.size
        val line = page.lines[bestLine]
        var best = 0
        var d = Float.MAX_VALUE
        for ((i, c) in line.chars.withIndex()) {
            val dist = when {
                xPt < c.x -> c.x - xPt
                xPt > c.x + c.w -> xPt - (c.x + c.w)
                else -> 0f
            }
            if (dist < d) {
                d = dist
                best = i
            }
        }
        return base + best
    }

    private fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '\'' || c == '-' || c == '_'

    /** 两个相邻字符之间是否是「词间空白」（很多 PDF 不产出空格字符，只能靠间距判断） */
    private fun isWordGap(prev: CharBox, next: CharBox): Boolean {
        val gap = next.x - (prev.x + prev.w)
        return gap > (maxOf(prev.w, next.w) * 0.35f).coerceAtLeast(1.0f)
    }

    private fun isCjkChar(c: Char): Boolean =
        c in '\u4e00'..'\u9fff' || c in '\u3400'..'\u4dbf'

    /**
     * 长按选中的最小单位：
     * · 拉丁文 → 整个单词
     * · 中文 → 该字所在的分句（按标点切，避免只选一个字没意义）
     */
    fun wordRange(page: PageText, idx: Int): Pair<Int, Int> {
        val chars = flattened(page)
        if (chars.isEmpty()) return 0 to 0
        val i = idx.coerceIn(0, chars.size - 1)
        val cur = chars[i].text.firstOrNull() ?: return i to i

        if (isCjkChar(cur)) {
            // 中文没有天然词边界：长按先给「一个分句」（逗号/顿号也算断点），再用拖动扩选
            var s = i
            while (s > 0 && !chars[s - 1].text.any { it in CJK_BREAK } && i - s < 40) s--
            var e = i
            while (e < chars.size - 1 && !chars[e].text.any { it in CJK_BREAK } && e - i < 40) e++
            return s to e
        }

        if (!isWordChar(cur)) return i to i
        var s = i
        while (
            s > 0 &&
            isWordChar(chars[s - 1].text.first()) &&
            !isWordGap(chars[s - 1], chars[s])
        ) s--
        var e = i
        while (
            e < chars.size - 1 &&
            isWordChar(chars[e + 1].text.first()) &&
            !isWordGap(chars[e], chars[e + 1])
        ) e++
        return s to e
    }

    /** 把展平索引区间 [a, b] 解析成文本 + 每行高亮矩形（跨行自动分段） */
    fun resolveRange(page: PageText, a: Int, b: Int): Selection? {
        val s = minOf(a, b)
        val e = maxOf(a, b)
        val rects = mutableListOf<Rect>()
        val sb = StringBuilder()
        var base = 0
        for (line in page.lines) {
            val lineStart = base
            val lineEnd = base + line.chars.size - 1
            base += line.chars.size
            if (lineEnd < s || lineStart > e) continue
            val cs = (maxOf(s, lineStart) - lineStart).coerceIn(0, line.chars.size - 1)
            val ce = (minOf(e, lineEnd) - lineStart).coerceIn(0, line.chars.size - 1)
            if (ce < cs) continue
            val sel = line.chars.subList(cs, ce + 1)
            if (sel.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(buildLineText(sel))
            rects.add(
                Rect(
                    left = sel.minOf { it.x },
                    top = line.yTop,
                    right = sel.maxOf { it.x + it.w },
                    bottom = line.yBottom,
                )
            )
        }
        val text = sb.toString().trim()
        if (text.isBlank() || rects.isEmpty()) return null
        return Selection(0, text, rects, page.widthPt, page.heightPt)
    }

    private val SENT_BREAK = charArrayOf('。', '！', '？', '；', '…', '.', '!', '?', ';', '\n')

    /** 零宽/软连字符/控制/方向控制等不可见字符（PDF 文字层的常见垃圾） */
    private val INVISIBLE_RE = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u00AD\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]")
    /** 中文分句断点：句读之外，逗号/顿号/冒号也算（长按先选一小句更实用） */
    private val CJK_BREAK = charArrayOf('。', '，', '、', '：', '；', '！', '？', '…', '\n')

    private fun expandSentence(chars: List<CharBox>, idx: Int): String {
        if (idx !in chars.indices) return ""
        var start = idx
        var end = idx
        val maxSpan = 240

        while (start > 0 && idx - start < maxSpan) {
            if (chars[start].text.any { it in SENT_BREAK }) break
            start--
        }
        if (chars[start].text.any { it in SENT_BREAK }) start++

        while (end < chars.size - 1 && end - idx < maxSpan) {
            if (chars[end].text.any { it in SENT_BREAK }) break
            end++
        }
        if (end in chars.indices && chars[end].text.any { it in SENT_BREAK }) end++

        if (start >= end) return ""
        return buildLineText(chars.subList(start, end.coerceAtMost(chars.size)))
    }
}
