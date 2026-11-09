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
                    val raw = tp.unicode ?: continue
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
                // 仅在「没有空格字符、间距又明显偏大」时补空格（部分 PDF 不产出空格字符）
                if (gap > (c.w * 0.6f).coerceAtLeast(1.5f) && !last.isWhitespace() &&
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

    private val SENT_BREAK = charArrayOf('。', '！', '？', '；', '…', '.', '!', '?', ';', '\n')

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
