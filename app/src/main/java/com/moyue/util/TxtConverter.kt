package com.moyue.util

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 纯 Kotlin 编写的离线 TXT → EPUB 转换器（服务端 txt2epub.py 的 1:1 对齐移植）
 *
 * 为什么要本地做：
 *  原来 TXT 走服务端 /sync/txt/convert，必须登录 + 联网才能导入一本书，
 *  既让商店文案无法写 "no account"，也带来隐私与可用性上的异议。
 *  TXT 转 EPUB 本质只是「编码识别 + 分章 + 拼 XML」，手机 CPU 完全可以离线完成。
 *
 * 能力对齐（与 Python 版一致）：
 *  1. 编码识别：BOM → 严格 UTF-8 → GB18030 / Big5 打分择优 → 兜底
 *  2. 章节识别：第X章/回/节、序章、楔子、番外、Chapter N …（宁可漏判退回自动分节，也不错判）
 *  3. 段落还原：中文区分「一行一段」与「硬折行」；西文按空行分块并接回硬折行
 *  4. 超长段落按句切分（>1200 字），避免 epub.js 渲染成一坨、TTS 卡顿
 *  5. 认不出章节标题时按每 60 段自动分节，保证任何 TXT 都有一份可用目录
 *
 * 不依赖 android.* 任何类，可直接在 JVM 单元测试里跑。
 */
object TxtConverter {

    private const val MAX_PARA_CHARS = 1200
    private const val PARAS_PER_CHAPTER = 60
    private const val MAX_FILE_BYTES = 40L * 1024 * 1024

    class TxtNotTextException(message: String) : Exception(message)
    class TxtEmptyException(message: String) : Exception(message)

    data class Chapter(val title: String, val paragraphs: List<String>)

    data class Stats(
        val title: String,
        val author: String,
        val chapters: Int,
        val paragraphs: Int,
        val chars: Int,
        val encoding: String,
        val segmentation: String,
        val language: String,
    )

    // ── 正则（与 txt2epub.py 保持一致） ──────────────────────────
    private const val CN_NUM = "[0-9０-９〇零一二三四五六七八九十百千万两]{1,12}"

    private val CHAPTER_EN_RE = Regex(
        "^\\s*(?:chapter|part|book|prologue|epilogue|appendix|section)\\s+" +
            "(?:[0-9]{1,4}|[IVXLCDM]{1,8}|[A-Z][a-z]+)\\b\\s*[:.\\-—]?\\s*.{0,50}$",
        RegexOption.IGNORE_CASE
    )

    private val CN_KEY_RE = Regex(
        "^\\s*(?:第\\s*$CN_NUM\\s*[章节節回卷幕篇部集話话]" +
            "|序章|序言|楔子|引子|前言|后记|後記|尾声|尾聲|终章|終章|大结局|大結局" +
            "|完结感言|番外篇|番外|外传|外傳|附录|附錄|卷\\s*$CN_NUM)"
    )

    private val TRAILING_PUNCT_RE = Regex("[。！？…；;]$")
    private val BODY_PUNCT_RE = Regex("[，。；！？,;!?]")
    private val SEPARATOR_RE = Regex("[\\s\\u3000:：.、—\\-·|/]")
    private val TAIL_PUNCT_RE = Regex("[。！？…；：!?;:”’\"'》』」】）)\\.]$")
    private val SENT_END_RE = Regex("[。！？!?…:：”\"』」]")
    private val SENT_SPLIT_RE = Regex("(?<=[。！？…!?；;])")
    private val TITLE_IN_BOOK_RE = Regex("《([^》]{1,40})》")
    /** 文件名里的站点水印（www.com 之类）不算词 */
    private val DOMAIN_TOKEN_RE = Regex(
        "^(?:www\\.)?[A-Za-z0-9-]+\\.(?:com|net|org|cn|cc|info|me|top|xyz|io|tv)$",
        RegexOption.IGNORE_CASE
    )

    /** 高频汉字，用于编码打分：GBK 文本用 Big5 解会大量落到低频字上 */
    private const val COMMON_HANZI =
        "的一是不了人我在有他这为之大来以个中上们到说国和地也子时道出而要于就下得可你年生" +
            "自会那后能对着事其里所去行过家十用发天如然作方成者多日都三小军二无同么经法当起" +
            "与好看学进种将还分此心前面又定见只主没公从情想点意活真明问力手很最话打知道给"

    // ── 对外主入口 ────────────────────────────────────────────

    /**
     * 把 TXT 文件离线转换成 EPUB 并写入 [outputEpub]。
     * @throws TxtEmptyException 空文件 / 无正文
     * @throws TxtNotTextException 二进制或加密文件
     */
    fun convertTxtToEpub(
        txtFile: File,
        outputEpub: File,
        title: String? = null,
        author: String? = null,
    ): Stats {
        if (!txtFile.exists()) throw TxtEmptyException("TXT 文件不存在")
        if (txtFile.length() == 0L) throw TxtEmptyException("TXT 文件是空的")
        if (txtFile.length() > MAX_FILE_BYTES) {
            throw TxtEmptyException("TXT 超过 40MB，建议拆分后再试")
        }

        val data = txtFile.readBytes()
        val (decoded, encoding) = detectAndDecode(data)
        val text = decoded.trimStart('\uFEFF')
        if (text.isBlank()) throw TxtEmptyException("TXT 内容为空或全部无法识别")
        if (looksLikeBinary(text)) {
            throw TxtNotTextException("这不是文本文件（可能是二进制/加密文件，或文件已损坏）")
        }

        val lang = guessLang(text.take(5000))
        val split = splitIntoChapters(text, lang)
        var chapters = split.first.filter { it.paragraphs.isNotEmpty() }
        val segmentation = split.second
        if (chapters.isEmpty()) throw TxtEmptyException("没有解析出任何正文段落")

        // 书首「《书名》/ 作者：xx」独立行 → 当作书名，且不占正文章节
        var finalTitle = title?.trim().orEmpty()
        if (chapters.size > 1) {
            val first = chapters[0]
            if (first.paragraphs.size == 1 &&
                (first.title == "正文" || first.title == "Section 1") &&
                first.paragraphs[0].length <= 60
            ) {
                val m = TITLE_IN_BOOK_RE.find(first.paragraphs[0])
                if (m != null) {
                    if (finalTitle.isEmpty()) finalTitle = m.groupValues[1].trim()
                    chapters = chapters.drop(1)
                }
            }
        }

        if (finalTitle.isEmpty()) finalTitle = humanizeTitle(txtFile.name)
        if (finalTitle.isEmpty()) finalTitle = "TXT Book"
        val finalAuthor = author?.trim().takeUnless { it.isNullOrBlank() } ?: "Unknown Author"

        createEpub(chapters, finalTitle, finalAuthor, outputEpub)

        return Stats(
            title = finalTitle,
            author = finalAuthor,
            chapters = chapters.size,
            paragraphs = chapters.sumOf { it.paragraphs.size },
            chars = chapters.sumOf { ch -> ch.paragraphs.sumOf { it.length } },
            encoding = encoding,
            segmentation = segmentation,
            language = lang,
        )
    }

    // ── 编码识别 ──────────────────────────────────────────────

    /** 返回 (文本, 编码名)。顺序：BOM → 严格 UTF-8 → GB18030/Big5 打分 → 兜底。 */
    internal fun detectAndDecode(data: ByteArray): Pair<String, String> {
        if (data.size >= 3 && data[0] == 0xEF.toByte() &&
            data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()
        ) {
            return String(data, 3, data.size - 3, Charsets.UTF_8) to "utf-8-sig"
        }
        if (data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) {
            return strictDecode(data.copyOfRange(2, data.size), Charsets.UTF_16LE)
                ?.let { it to "utf-16le" } ?: (String(data, Charsets.UTF_16) to "utf-16")
        }
        if (data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()) {
            return strictDecode(data.copyOfRange(2, data.size), Charsets.UTF_16BE)
                ?.let { it to "utf-16be" } ?: (String(data, Charsets.UTF_16) to "utf-16")
        }

        strictDecode(data, Charsets.UTF_8)?.let { return it to "utf-8" }

        var bestText: String? = null
        var bestEnc = "utf-8"
        var bestScore = Double.NEGATIVE_INFINITY
        for (enc in listOf("GB18030", "Big5")) {
            val cs = runCatching { Charset.forName(enc) }.getOrNull() ?: continue
            val text = strictDecode(data, cs) ?: continue
            val score = scoreText(text)
            if (score > bestScore) {
                bestScore = score
                bestText = text
                bestEnc = enc.lowercase()
            }
        }
        if (bestText != null) return bestText to bestEnc

        return String(data, Charsets.UTF_8) to "utf-8"
    }

    private fun strictDecode(data: ByteArray, cs: Charset): String? = runCatching {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data))
            .toString()
    }.getOrNull()

    /** 编码可信度打分：常用汉字/中文标点命中加分，替换符与控制字符重罚 */
    private fun scoreText(text: String): Double {
        val sample = if (text.length > 20_000) text.substring(0, 20_000) else text
        if (sample.isEmpty()) return 0.0
        var score = 0.0
        for (c in sample) {
            score += when {
                c == '\uFFFD' -> -30.0
                c.code < 32 && c != '\n' && c != '\r' && c != '\t' -> -20.0
                COMMON_HANZI.indexOf(c) >= 0 -> 2.0
                isCjk(c) -> 1.0
                "，。！？；：、“”‘’（）《》【】…—".indexOf(c) >= 0 -> 3.0
                c in 'a'..'z' || c in 'A'..'Z' -> 0.6
                c in '0'..'9' -> 0.3
                c == ' ' || c == '\n' || c == '\t' -> 0.2
                else -> 0.0
            }
        }
        return score / sample.length
    }

    // ── 章节识别 ──────────────────────────────────────────────

    /**
     * 一行到底是不是章节标题。宁可漏判（退回自动分节）也不要错判。
     * 坑：只写「关键词 + 任意短文本」会把 `楔子内容。` 这种正文行当标题，
     * 所以再加上：不以句末标点收尾；副标题带分隔符才允许长一点，直贴关键词的只能 ≤6 字。
     */
    internal fun isChapterTitle(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 46) return false
        if (TRAILING_PUNCT_RE.containsMatchIn(t)) return false

        val m = CN_KEY_RE.find(t)
        if (m != null && m.range.first == 0) {
            val rawRest = t.substring(m.range.last + 1)
            val rest = rawRest.trim()
            if (rest.isNotEmpty()) {
                if (BODY_PUNCT_RE.containsMatchIn(rest)) return false
                if (SEPARATOR_RE.containsMatchIn(rawRest)) {
                    if (rest.length > 34) return false
                } else if (rest.length > 6) {
                    return false
                }
            }
            return true
        }
        if (CHAPTER_EN_RE.matches(t)) return true
        return false
    }

    internal fun guessLang(sample: String): String {
        if (sample.isEmpty()) return "en"
        var cjk = 0
        for (c in sample) if (isCjk(c)) cjk++
        return if (cjk * 10 > sample.length) "zh" else "en"
    }

    internal fun defaultSectionTitle(index: Int, lang: String): String =
        if (lang == "zh") (if (index == 0) "正文" else "第 ${index + 1} 节")
        else "Section ${index + 1}"

    // ── 段落还原 ──────────────────────────────────────────────

    /**
     * 中文网文有两种形态，必须分开对待：
     *  · 「一行一段」（现代网文主流，行尾基本都有句读）→ 按行成段；
     *  · 「硬折行」（老式 TXT 每 40 字换行，行尾多为半个句子）→ 接线还原。
     *  用行尾句读比例判断，比看空行准得多。
     */
    internal fun buildParagraphs(lines: List<String>, lang: String): List<String> {
        val real = lines.filter { it.isNotBlank() }
        if (real.isEmpty()) return emptyList()
        val blanks = lines.size - real.size

        val result: List<String>
        if (lang == "zh") {
            val noEnd = real.count { !TAIL_PUNCT_RE.containsMatchIn(it.takeLast(1)) }
            result = if (noEnd * 3 >= real.size) {
                mergeBrokenLines(lines.joinToString("\n"))
            } else {
                real
            }
        } else {
            result = if (blanks * 20 >= lines.size) {
                val paras = mutableListOf<String>()
                val buf = mutableListOf<String>()
                for (line in lines) {
                    if (line.isNotBlank()) {
                        buf.add(line)
                    } else if (buf.isNotEmpty()) {
                        paras.add(joinBlockLines(buf, lang))
                        buf.clear()
                    }
                }
                if (buf.isNotEmpty()) paras.add(joinBlockLines(buf, lang))
                paras
            } else {
                mergeBrokenLines(lines.joinToString("\n"))
            }
        }
        return splitOverlongParagraphs(result)
    }

    private fun joinBlockLines(lines: List<String>, lang: String): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder(lines[0])
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (lang == "zh" && sb.isNotEmpty() && isCjk(sb.last()) && line.isNotEmpty() && isCjk(line[0])) {
                sb.append(line)
            } else {
                sb.append(' ').append(line)
            }
        }
        return sb.toString()
    }

    /** 硬折行接回自然段：行尾没有句末标点就接下一行（中文直拼，西文补空格）。 */
    internal fun mergeBrokenLines(text: String): List<String> {
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val paras = mutableListOf<String>()
        val cur = StringBuilder()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) {
                if (cur.isNotEmpty()) {
                    paras.add(cur.toString().trim())
                    cur.setLength(0)
                }
                continue
            }
            if (cur.isEmpty()) {
                cur.append(line)
                continue
            }
            val last = cur.last()
            if (SENT_END_RE.containsMatchIn(last.toString())) {
                paras.add(cur.toString().trim())
                cur.setLength(0)
                cur.append(line)
            } else {
                if (isCjk(last) && isCjk(line[0])) cur.append(line)
                else cur.append(' ').append(line)
            }
        }
        if (cur.isNotEmpty()) paras.add(cur.toString().trim())
        return paras.filter { it.isNotBlank() }
    }

    /** 整本书没有换行、或被合成器写成超长段时，按句切开（>1200 字一段 epub.js 与 TTS 都会卡）。 */
    internal fun splitOverlongParagraphs(paras: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (p in paras) {
            if (p.length <= MAX_PARA_CHARS) {
                out.add(p)
                continue
            }
            val cur = StringBuilder()
            for (s in SENT_SPLIT_RE.split(p)) {
                if (s.isEmpty()) continue
                if (cur.isNotEmpty() && cur.length + s.length > MAX_PARA_CHARS) {
                    out.add(cur.toString())
                    cur.setLength(0)
                }
                cur.append(s)
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
        }
        return out.filter { it.isNotBlank() }
    }

    // ── 分章 ─────────────────────────────────────────────────

    /** 先按标题行分章，认不出标题就按段落数自动分节。返回 (章节, 分章方式)。 */
    internal fun splitIntoChapters(text: String, lang: String): Pair<List<Chapter>, String> {
        val rawLines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val chapters = mutableListOf<Chapter>()
        var curTitle: String? = null
        val curLines = mutableListOf<String>()
        var headingHits = 0

        fun flush() {
            if (curLines.isNotEmpty() || curTitle != null) {
                val paras = buildParagraphs(curLines, lang)
                if (paras.isNotEmpty() || curTitle != null) {
                    chapters.add(
                        Chapter(
                            curTitle ?: defaultSectionTitle(chapters.size, lang),
                            paras
                        )
                    )
                }
            }
            curLines.clear()
            curTitle = null
        }

        for (raw in rawLines) {
            val t = raw.trim()
            if (t.isEmpty()) {
                if (curLines.isNotEmpty() && curLines.last() != "") curLines.add("")
                continue
            }
            if (isChapterTitle(t)) {
                headingHits++
                flush()
                curTitle = t
            } else {
                curLines.add(t)
            }
        }
        flush()

        if (headingHits == 0) {
            val all = buildParagraphs(rawLines.map { it.trim() }, lang)
            val out = mutableListOf<Chapter>()
            var i = 0
            while (i < all.size) {
                val end = minOf(i + PARAS_PER_CHAPTER, all.size)
                out.add(Chapter(defaultSectionTitle(out.size, lang), all.subList(i, end).toList()))
                i += PARAS_PER_CHAPTER
            }
            return out to "auto"
        }

        val filtered = chapters.filter { it.paragraphs.isNotEmpty() || it.title.isNotBlank() }
        return filtered to (if (headingHits >= 2) "heading" else "mixed")
    }

    // ── 杂项 ─────────────────────────────────────────────────

    private fun looksLikeBinary(text: String): Boolean {
        val sample = if (text.length > 20_000) text.substring(0, 20_000) else text
        if (sample.isBlank()) return false
        var bad = 0
        for (c in sample) {
            if ((c.code < 32 && c != '\n' && c != '\r' && c != '\t') || c == '\uFFFD') bad++
        }
        // 允许 2% 容差（有些 TXT 夹带零星坏字节），超过判定为二进制
        return bad * 50 > sample.length
    }

    /**
     * 从 App 内部缓存文件名还原书名。
     * 本地导入的临时文件叫 import_1794173925667_书名.txt，
     * 若不剥掉前缀，书架上就会出现 “import_1794173925667_t1_gbk” 这种丑名字。
     */
    fun titleFromFileName(fileName: String): String {
        val stripped = Regex("^(?:import|webdav|moyue|tmp)(?:_[a-z]+)*_\\d{6,}_")
            .replace(fileName.trim(), "")
        val stripped2 = Regex("^(?:import|webdav|moyue|tmp)(?:_[a-z]+)*_")
            .replace(stripped, "")
        val name = stripped2.ifBlank { stripped }.ifBlank { fileName }
        val t = humanizeTitle(name)
        return t.ifBlank { name.substringBeforeLast('.') }
    }

    /** 文件名 → 像样的书名（剔水印词 / ISBN / 扩展名）。 */
    internal fun humanizeTitle(rawName: String): String {
        val base = Regex("\\.(txt|TXT|text)$").replace(rawName, "").trim()
        val junk = setOf(
            "txt", "text", "download", "www", "com", "net", "org", "cn",
            "z", "library", "zlib", "zlibrary", "1lib", "libgen", "epub", "pdf",
            "全本", "完结", "未删节", "精校版", "校对版", "下载", "电子书", "小说"
        )
        val kept = base.split(Regex("[_\\-\\s,，。、\\[\\]()（）]+"))
            .filter { tk ->
                val low = tk.lowercase()
                low.isNotEmpty() && low !in junk && !Regex("\\d{9,13}").matches(low) &&
                    !DOMAIN_TOKEN_RE.matches(low)
            }
        val joined = kept.joinToString(" ").trim().trim('-')
        return if (joined.length >= 2) joined else base
    }

    private fun isCjk(c: Char): Boolean =
        c in '\u4e00'..'\u9fff' || c in '\u3400'..'\u4dbf' || c in '\uf900'..'\ufaff'

    // ── EPUB 组装（结构与 MobiConverter 完全一致，已被 epub.js 0.3.93 验证） ──

    private fun createEpub(
        chapters: List<Chapter>,
        title: String,
        author: String,
        outputEpub: File,
    ) {
        val parent = outputEpub.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()

        ZipOutputStream(outputEpub.outputStream()).use { zip ->
            val mimetypeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val entryMime = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetypeBytes.size.toLong()
                compressedSize = mimetypeBytes.size.toLong()
                val crc = CRC32()
                crc.update(mimetypeBytes)
                this.crc = crc.value
            }
            zip.putNextEntry(entryMime)
            zip.write(mimetypeBytes)
            zip.closeEntry()

            val containerXml = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>""".trimIndent()
            writeZipEntry(zip, "META-INF/container.xml", containerXml.toByteArray(Charsets.UTF_8))

            data class ConvertedChapter(val id: String, val filename: String, val title: String)

            val chapterList = mutableListOf<ConvertedChapter>()
            chapters.forEachIndexed { index, chap ->
                val chapIdx = index + 1
                val fname = "chapter_${String.format("%03d", chapIdx)}.xhtml"
                val chapId = "chap_$chapIdx"
                val body = chap.paragraphs.joinToString("\n") { "<p>${escapeXml(it)}</p>" }
                val xhtml = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN">
<head>
  <meta http-equiv="Content-Type" content="text/html; charset=utf-8" />
  <title>${escapeXml(chap.title)}</title>
  <style type="text/css">
    body { font-family: sans-serif; line-height: 1.6; margin: 5%; }
    p { text-indent: 2em; margin-bottom: 0.8em; }
  </style>
</head>
<body>
$body
</body>
</html>"""
                writeZipEntry(zip, "OEBPS/text/$fname", xhtml.toByteArray(Charsets.UTF_8))
                chapterList.add(ConvertedChapter(chapId, fname, chap.title))
            }

            val navMapSb = StringBuilder()
            var playOrder = 1
            for (chap in chapterList) {
                navMapSb.append("""    <navPoint id="np_$playOrder" playOrder="$playOrder">
      <navLabel><text>${escapeXml(chap.title)}</text></navLabel>
      <content src="text/${chap.filename}"/>
    </navPoint>""" + "\n")
                playOrder++
            }

            val ncx = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="urn:uuid:txt-convert-${System.currentTimeMillis()}"/>
    <meta name="dtb:depth" content="1"/>
    <meta name="dtb:totalPageCount" content="0"/>
    <meta name="dtb:maxPageNumber" content="0"/>
  </head>
  <docTitle><text>${escapeXml(title)}</text></docTitle>
  <docAuthor><text>${escapeXml(author)}</text></docAuthor>
  <navMap>
$navMapSb  </navMap>
</ncx>"""
            writeZipEntry(zip, "OEBPS/toc.ncx", ncx.toByteArray(Charsets.UTF_8))

            val opfManifest = StringBuilder()
            opfManifest.append("""    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""" + "\n")
            val opfSpine = StringBuilder()
            for (chap in chapterList) {
                opfManifest.append("""    <item id="${chap.id}" href="text/${chap.filename}" media-type="application/xhtml+xml"/>""" + "\n")
                opfSpine.append("""    <itemref idref="${chap.id}"/>""" + "\n")
            }

            val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookID" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>${escapeXml(title)}</dc:title>
    <dc:creator opf:role="aut">${escapeXml(author)}</dc:creator>
    <dc:language>zh-CN</dc:language>
    <dc:identifier id="BookID">urn:uuid:txt-convert-${System.currentTimeMillis()}</dc:identifier>
  </metadata>
  <manifest>
$opfManifest  </manifest>
  <spine toc="ncx">
$opfSpine  </spine>
</package>"""
            writeZipEntry(zip, "OEBPS/content.opf", opf.toByteArray(Charsets.UTF_8))
        }
    }

    private fun writeZipEntry(zip: ZipOutputStream, path: String, data: ByteArray) {
        val entry = ZipEntry(path)
        zip.putNextEntry(entry)
        zip.write(data)
        zip.closeEntry()
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
