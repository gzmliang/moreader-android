package com.moyue.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.regex.Pattern
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 纯 Kotlin 编写的高性能离线 MOBI / AZW / PRC 解析器与转换器
 *
 * 功能：
 * 1. 解析 Palm Database (PDB) 头和数据记录
 * 2. 解码 PalmDOC (LZ77) 压缩正文及未压缩正文
 * 3. 提取 EXTH 元数据（标题、作者、封面索引）与图片记录
 * 4. 替换 recindex 图片引用，生成符合标准 EPUB 2.0 规范的流式精读本
 */
object MobiConverter {

    class MobiParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class MobiParsed(
        val title: String,
        val author: String,
        val html: String,
        val images: List<MobiImage>,
        val coverImageIndex: Int = -1
    )

    data class MobiImage(
        val index: Int, // 1-based index (对应 recindex 1, 2, ...)
        val data: ByteArray,
        val ext: String, // jpg, png, gif, or bin
        val mimeType: String
    )

    /**
     * 将 .mobi 文件转换为标准的 .epub 文件
     * @param mobiFile 输入的 .mobi 文件
     * @param outputEpub 输出的 .epub 文件
     */
    fun convertMobiToEpub(mobiFile: File, outputEpub: File) {
        val parsed = parseMobi(mobiFile)
        createEpub(parsed, outputEpub)
    }

    /**
     * 校验文件是否为 MOBI / PalmDOC 格式
     */
    fun isMobiFile(file: File): Boolean {
        if (!file.exists() || file.length() < 78) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(60)
                val typeCreator = ByteArray(8)
                raf.readFully(typeCreator)
                val str = String(typeCreator, Charsets.US_ASCII)
                str.startsWith("BOOKMOBI") || str.startsWith("TEXtREAd") || str.contains("MOBI")
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 解析 MOBI 文件
     */
    fun parseMobi(file: File): MobiParsed {
        RandomAccessFile(file, "r").use { raf ->
            val fileLength = raf.length()
            if (fileLength < 78) throw MobiParseException("File is too small to be a MOBI database")

            val headerBuf = ByteArray(78)
            raf.readFully(headerBuf)
            val bb = ByteBuffer.wrap(headerBuf).order(ByteOrder.BIG_ENDIAN)

            // PDB 头部解析
            val rawPdbName = ByteArray(32)
            bb.get(rawPdbName)
            val pdbTitle = String(rawPdbName, Charsets.ISO_8859_1).trimEnd('\u0000', ' ').ifBlank {
                file.nameWithoutExtension
            }

            bb.position(76)
            val numRecords = bb.short.toInt() and 0xFFFF
            if (numRecords == 0) throw MobiParseException("MOBI database contains no records")

            // 读取 Record List (每个记录 8 字节: 4 字节偏移 + 1 字节属性 + 3 字节唯一ID)
            val recordEntries = ArrayList<Long>(numRecords)
            val recListBuf = ByteArray(numRecords * 8)
            raf.readFully(recListBuf)
            val recListBb = ByteBuffer.wrap(recListBuf).order(ByteOrder.BIG_ENDIAN)
            for (i in 0 until numRecords) {
                val offset = recListBb.int.toLong() and 0xFFFFFFFFL
                recListBb.int // skip attr + unique ID
                recordEntries.add(offset)
            }

            // 读取单个 Record
            fun readRecord(index: Int): ByteArray {
                if (index < 0 || index >= numRecords) return ByteArray(0)
                val start = recordEntries[index]
                val end = if (index + 1 < numRecords) recordEntries[index + 1] else fileLength
                val size = (end - start).toInt()
                if (size <= 0) return ByteArray(0)
                val buf = ByteArray(size)
                raf.seek(start)
                raf.readFully(buf)
                return buf
            }

            // Record 0: Header
            val rec0 = readRecord(0)
            if (rec0.size < 16) throw MobiParseException("Corrupted record 0 header")

            val rec0Bb = ByteBuffer.wrap(rec0).order(ByteOrder.BIG_ENDIAN)
            val compression = rec0Bb.short.toInt() and 0xFFFF
            rec0Bb.short // unused
            val textLength = rec0Bb.int.toLong() and 0xFFFFFFFFL
            val textRecordCount = rec0Bb.short.toInt() and 0xFFFF
            val maxSectionSize = rec0Bb.short.toInt() and 0xFFFF
            val cryptoType = rec0Bb.short.toInt() and 0xFFFF

            if (cryptoType != 0) {
                throw MobiParseException("This MOBI book is DRM protected / encrypted")
            }

            // 字符编码与元数据
            var encodingCharset = Charsets.UTF_8
            var bookTitle = pdbTitle
            var bookAuthor = "Unknown"
            var firstImageIndex = textRecordCount + 1
            var extraDataFlags = 0
            var coverOffset = -1

            if (rec0.size >= 40) {
                val magic = String(rec0, 16, 4, Charsets.US_ASCII)
                if (magic == "MOBI") {
                    val mobiHeaderLength = rec0Bb.getInt(20).toLong() and 0xFFFFFFFFL
                    val codepage = rec0Bb.getInt(28)
                    encodingCharset = when (codepage) {
                        65001 -> Charsets.UTF_8
                        1252 -> Charset.forName("windows-1252")
                        else -> runCatching { Charset.forName("CP$codepage") }.getOrDefault(Charsets.UTF_8)
                    }

                    // Full title
                    if (rec0.size >= 0x58) {
                        val toff = rec0Bb.getInt(0x54)
                        val tlen = rec0Bb.getInt(0x58)
                        if (toff > 0 && tlen > 0 && toff + tlen <= rec0.size) {
                            val parsedTitle = String(rec0, toff, tlen, encodingCharset).trim()
                            if (parsedTitle.isNotBlank()) bookTitle = parsedTitle
                        }
                    }

                    // First image index
                    if (rec0.size >= 0x70) {
                        firstImageIndex = rec0Bb.getInt(0x6C)
                    }

                    // Extra data flags (trailing entries)
                    if (rec0.size >= 0xF4) {
                        extraDataFlags = rec0Bb.getShort(0xF2).toInt() and 0xFFFF
                    }

                    // EXTH Header
                    if (rec0.size >= 0x84) {
                        val exthFlag = rec0Bb.getInt(0x80)
                        if ((exthFlag and 0x40) != 0) {
                            val exthOffset = 16 + mobiHeaderLength.toInt()
                            if (rec0.size >= exthOffset + 12) {
                                val exthMagic = String(rec0, exthOffset, 4, Charsets.US_ASCII)
                                if (exthMagic == "EXTH") {
                                    val exthCount = rec0Bb.getInt(exthOffset + 8)
                                    var curPos = exthOffset + 12
                                    for (i in 0 until exthCount) {
                                        if (curPos + 8 > rec0.size) break
                                        val exthId = rec0Bb.getInt(curPos)
                                        val exthSize = rec0Bb.getInt(curPos + 4)
                                        if (exthSize < 8 || curPos + exthSize > rec0.size) break
                                        val dataSize = exthSize - 8
                                        val dataOffset = curPos + 8
                                        when (exthId) {
                                            100 -> { // Creator / Author
                                                val a = String(rec0, dataOffset, dataSize, encodingCharset).trim()
                                                if (a.isNotBlank()) bookAuthor = a
                                            }
                                            503 -> { // Updated Title
                                                val t = String(rec0, dataOffset, dataSize, encodingCharset).trim()
                                                if (t.isNotBlank()) bookTitle = t
                                            }
                                            201 -> { // CoverOffset
                                                coverOffset = when (dataSize) {
                                                    1 -> rec0[dataOffset].toInt() and 0xFF
                                                    2 -> rec0Bb.getShort(dataOffset).toInt() and 0xFFFF
                                                    4 -> rec0Bb.getInt(dataOffset)
                                                    else -> -1
                                                }
                                            }
                                        }
                                        curPos += exthSize
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 计算末尾附属字节 (Trailing Entries)
            var multibyte = extraDataFlags and 1
            var trailers = 0
            var flags = extraDataFlags shr 1
            while (flags > 0) {
                if ((flags and 1) != 0) trailers++
                flags = flags shr 1
            }

            // 解压/提取正文 Text Records (1 .. textRecordCount)
            val fullHtml = ByteArrayOutputStream()
            val recLimit = minOf(textRecordCount, numRecords - 1)
            for (i in 1..recLimit) {
                var rawRec = readRecord(i)
                if (rawRec.isEmpty()) continue

                // 剔除 trailing entries
                rawRec = trimTrailingEntries(rawRec, trailers, multibyte)

                when (compression) {
                    1 -> { // No compression
                        fullHtml.write(rawRec)
                    }
                    2 -> { // PalmDOC LZ77
                        val decomp = decompressPalmDoc(rawRec)
                        fullHtml.write(decomp)
                    }
                    else -> {
                        throw MobiParseException("Unsupported compression type: $compression")
                    }
                }
            }

            var htmlText = String(fullHtml.toByteArray(), encodingCharset)

            // 读取 Images (从 firstImageIndex 开始)
            val images = ArrayList<MobiImage>()
            if (firstImageIndex in 1 until numRecords) {
                var imgNum = 1
                for (i in firstImageIndex until numRecords) {
                    val imgData = readRecord(i)
                    if (imgData.size < 4) continue
                    val magic4 = String(imgData, 0, 4, Charsets.US_ASCII)
                    // 忽略控制记录
                    if (magic4 in listOf("FLIS", "FCIS", "FDST", "DATP", "SRCS", "BOVI")) {
                        continue
                    }
                    val (ext, mime) = detectImageType(imgData)
                    images.add(MobiImage(index = imgNum, data = imgData, ext = ext, mimeType = mime))
                    imgNum++
                }
            }

            // 替换 recindex="00001" 为标准 EPUB 图片路径 src="images/image_00001.ext"
            htmlText = replaceImageReferences(htmlText, images)

            return MobiParsed(
                title = bookTitle,
                author = bookAuthor,
                html = htmlText,
                images = images,
                coverImageIndex = if (coverOffset in 0 until images.size) coverOffset + 1 else if (images.isNotEmpty()) 1 else -1
            )
        }
    }

    /**
     * 剔除记录末尾的可变长附属数据条目
     */
    private fun trimTrailingEntries(data: ByteArray, trailers: Int, multibyte: Int): ByteArray {
        var current = data
        for (t in 0 until trailers) {
            val num = getSizeOfTrailingDataEntry(current)
            if (num > 0 && num <= current.size) {
                current = current.copyOfRange(0, current.size - num)
            }
        }
        if (multibyte != 0 && current.isNotEmpty()) {
            val num = (current.last().toInt() and 3) + 1
            if (num in 1..current.size) {
                current = current.copyOfRange(0, current.size - num)
            }
        }
        return current
    }

    private fun getSizeOfTrailingDataEntry(data: ByteArray): Int {
        if (data.isEmpty()) return 0
        var num = 0
        val start = maxOf(0, data.size - 4)
        for (i in start until data.size) {
            val b = data[i].toInt() and 0xFF
            if ((b and 0x80) != 0) num = 0
            num = (num shl 7) or (b and 0x7F)
        }
        return num
    }

    /**
     * PalmDOC LZ77 纯内存极速解压缩
     */
    fun decompressPalmDoc(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size * 2)
        var i = 0
        val len = data.size
        while (i < len) {
            val b = data[i++].toInt() and 0xFF
            when {
                b == 0x00 -> {
                    out.write(0)
                }
                b in 0x01..0x08 -> {
                    // 复制随后的 1..8 个字节
                    val count = b
                    val end = minOf(i + count, len)
                    if (i < end) {
                        out.write(data, i, end - i)
                        i = end
                    }
                }
                b in 0x09..0x7F -> {
                    // 单个文字
                    out.write(b)
                }
                b in 0x80..0xBF -> {
                    // LZ77 距离/长度对
                    if (i < len) {
                        val b2 = data[i++].toInt() and 0xFF
                        val distanceLength = ((b and 0x3F) shl 8) or b2
                        val distance = distanceLength shr 3
                        val copyLen = (distanceLength and 0x07) + 3

                        val outBuf = out.toByteArray()
                        val currentPos = outBuf.size
                        val srcPos = currentPos - distance
                        if (srcPos >= 0) {
                            for (k in 0 until copyLen) {
                                val pos = srcPos + k
                                if (pos in 0 until currentPos) {
                                    out.write(outBuf[pos].toInt() and 0xFF)
                                }
                            }
                        }
                    }
                }
                b in 0xC0..0xFF -> {
                    // 空格 + 字符
                    out.write(' '.code)
                    out.write(b xor 0x80)
                }
            }
        }
        return out.toByteArray()
    }

    private fun detectImageType(data: ByteArray): Pair<String, String> {
        if (data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()) {
            return Pair("jpg", "image/jpeg")
        }
        if (data.size >= 8 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()) {
            return Pair("png", "image/png")
        }
        if (data.size >= 4 && data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() && data[2] == 'F'.code.toByte()) {
            return Pair("gif", "image/gif")
        }
        return Pair("jpg", "image/jpeg")
    }

    /**
     * 将 HTML 中的 recindex 转换为标准本地图片路径
     */
    private fun replaceImageReferences(html: String, images: List<MobiImage>): String {
        val imageMap = images.associateBy { it.index }
        val pattern = Pattern.compile("""<img([^>]*?)(?:recindex=['"]?(\d+)['"]?)([^>]*?)>""", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(html)
        val sb = StringBuffer()
        while (matcher.find()) {
            val before = matcher.group(1) ?: ""
            val idx = matcher.group(2)?.toIntOrNull() ?: 1
            val after = matcher.group(3) ?: ""
            val img = imageMap[idx]
            val replacement = if (img != null) {
                "<img${before}src=\"images/image_${String.format("%05d", idx)}.${img.ext}\"${after}/>"
            } else {
                "<img${before}src=\"\"${after}/>"
            }
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement))
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    /**
     * 将提取的网页与图片组装为标准 EPUB 2.0 归档文件
     */
    private fun createEpub(parsed: MobiParsed, outputEpub: File) {
        val parent = outputEpub.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()

        ZipOutputStream(outputEpub.outputStream()).use { zip ->
            // 1. mimetype (必须首个写入、STORED 无压缩、无额外头字段)
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

            // 2. META-INF/container.xml
            val containerXml = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>""".trimIndent()
            writeZipEntry(zip, "META-INF/container.xml", containerXml.toByteArray(Charsets.UTF_8))

            // 3. OEBPS/images/...
            for (img in parsed.images) {
                val path = "OEBPS/images/image_${String.format("%05d", img.index)}.${img.ext}"
                writeZipEntry(zip, path, img.data)
            }

            // 4. 清洗 HTML 并智能切分为章节（优先按 mbp:pagebreak 切片）
            val cleanBody = if (parsed.html.contains("<body", ignoreCase = true)) {
                val bStart = parsed.html.indexOf("<body", ignoreCase = true)
                val bodyOpenEnd = parsed.html.indexOf(">", bStart)
                val bEnd = parsed.html.lastIndexOf("</body", ignoreCase = true)
                if (bodyOpenEnd != -1 && bEnd > bodyOpenEnd) {
                    parsed.html.substring(bodyOpenEnd + 1, bEnd)
                } else parsed.html
            } else {
                parsed.html
            }

            // 提取所有 filepos 标题候选（常用于 MOBI 内置目录反向解析）
            val posLinks = Pattern.compile("""<a\s+[^>]*filepos=['"]?(\d+)['"]?[^>]*>(.*?)</a>""", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
            val posMatcher = posLinks.matcher(cleanBody)
            val fileposTitles = LinkedHashSet<String>()
            while (posMatcher.find()) {
                val tHtml = posMatcher.group(2) ?: ""
                val t = tHtml.replace(Regex("<[^>]+>"), "").trim()
                if (t.isNotBlank() && t.length in 2..35) {
                    fileposTitles.add(t)
                }
            }

            // 优先检查是否有 mbp:pagebreak 进行分页
            val pageBreakPattern = Pattern.compile("""<mbp:pagebreak/?>""", Pattern.CASE_INSENSITIVE)
            val rawSections = cleanBody.split(pageBreakPattern).map { it.trim() }.filter { it.isNotEmpty() }

            val sections = if (rawSections.size >= 2) {
                rawSections
            } else {
                listOf(cleanBody)
            }

            data class ConvertedChapter(
                val id: String,
                val filename: String,
                val title: String,
                val html: String
            )

            val chapterList = mutableListOf<ConvertedChapter>()
            for ((index, sectionHtml) in sections.withIndex()) {
                val chapIdx = index + 1
                val fname = "chapter_${String.format("%03d", chapIdx)}.xhtml"
                val chapId = "chap_$chapIdx"

                // 提取章节标题
                var chapTitle = ""
                for (ft in fileposTitles) {
                    val searchSpan = sectionHtml.take(400)
                    if (searchSpan.contains(ft)) {
                        chapTitle = ft
                        break
                    }
                }
                if (chapTitle.isBlank()) {
                    val hPattern = Pattern.compile("""<(?:h[1-6]|b|strong)[^>]*>(?:<font[^>]*>)?([^<]+)(?:</font>)?</(?:h[1-6]|b|strong)>""", Pattern.CASE_INSENSITIVE)
                    val hMatcher = hPattern.matcher(sectionHtml.take(400))
                    if (hMatcher.find()) {
                        val cand = (hMatcher.group(1) ?: "").trim()
                        if (cand.length in 2..30 && !cand.startsWith("http")) {
                            chapTitle = cand
                        }
                    }
                }
                if (chapTitle.isBlank()) {
                    val plain = sectionHtml.take(300).replace(Regex("<[^>]+>"), " ").trim()
                    val fl = plain.lines().firstOrNull()?.trim() ?: ""
                    if (fl.length in 2..25 && !fl.startsWith("http")) {
                        chapTitle = fl
                    }
                }
                if (chapTitle.isBlank()) {
                    chapTitle = if (sections.size == 1) parsed.title else "第 $chapIdx 节"
                }

                val xhtml = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN">
<head>
  <meta http-equiv="Content-Type" content="text/html; charset=utf-8" />
  <title>${escapeXml(chapTitle)}</title>
  <style type="text/css">
    body { font-family: sans-serif; line-height: 1.6; margin: 5%; }
    p { text-indent: 2em; margin-bottom: 0.8em; }
    img { max-width: 100%; height: auto; display: block; margin: 1em auto; }
  </style>
</head>
<body>
$sectionHtml
</body>
</html>"""
                writeZipEntry(zip, "OEBPS/text/$fname", xhtml.toByteArray(Charsets.UTF_8))
                chapterList.add(ConvertedChapter(chapId, fname, chapTitle, xhtml))
            }

            // 5. OEBPS/toc.ncx
            val navMapSb = StringBuilder()
            var playOrder = 1
            for ((idx, chap) in chapterList.withIndex()) {
                // 仅对有效标题或首章创建 NCX 节点
                val isPlaceholder = chap.title.startsWith("第 ") && chap.title.endsWith(" 节")
                if (!isPlaceholder || idx == 0) {
                    navMapSb.append("""    <navPoint id="np_$playOrder" playOrder="$playOrder">
      <navLabel><text>${escapeXml(chap.title)}</text></navLabel>
      <content src="text/${chap.filename}"/>
    </navPoint>""" + "\n")
                    playOrder++
                }
            }

            val ncx = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="urn:uuid:mobi-convert-${System.currentTimeMillis()}"/>
    <meta name="dtb:depth" content="1"/>
    <meta name="dtb:totalPageCount" content="0"/>
    <meta name="dtb:maxPageNumber" content="0"/>
  </head>
  <docTitle><text>${escapeXml(parsed.title)}</text></docTitle>
  <docAuthor><text>${escapeXml(parsed.author)}</text></docAuthor>
  <navMap>
$navMapSb  </navMap>
</ncx>"""
            writeZipEntry(zip, "OEBPS/toc.ncx", ncx.toByteArray(Charsets.UTF_8))

            // 6. OEBPS/content.opf
            val opfManifest = StringBuilder()
            opfManifest.append("""    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""" + "\n")
            val opfSpine = StringBuilder()

            for (chap in chapterList) {
                opfManifest.append("""    <item id="${chap.id}" href="text/${chap.filename}" media-type="application/xhtml+xml"/>""" + "\n")
                opfSpine.append("""    <itemref idref="${chap.id}"/>""" + "\n")
            }

            for (img in parsed.images) {
                val imgId = "img_${img.index}"
                val href = "images/image_${String.format("%05d", img.index)}.${img.ext}"
                opfManifest.append("""    <item id="$imgId" href="$href" media-type="${img.mimeType}"/>""" + "\n")
            }

            var coverMeta = ""
            if (parsed.coverImageIndex > 0 && parsed.images.any { it.index == parsed.coverImageIndex }) {
                coverMeta = """<meta name="cover" content="img_${parsed.coverImageIndex}"/>"""
            }

            val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookID" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>${escapeXml(parsed.title)}</dc:title>
    <dc:creator opf:role="aut">${escapeXml(parsed.author)}</dc:creator>
    <dc:language>zh-CN</dc:language>
    <dc:identifier id="BookID">urn:uuid:mobi-convert-${System.currentTimeMillis()}</dc:identifier>
    $coverMeta
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

    private fun escapeXml(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
