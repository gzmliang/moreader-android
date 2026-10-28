package com.moyue.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.moyue.app.data.models.Book
import com.moyue.app.data.models.Bookmark
import com.moyue.app.data.models.Chapter
import com.moyue.app.data.models.Highlight
import com.moyue.app.data.models.TocEntry
import com.moyue.app.data.models.Vocabulary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class BookRepository(private val context: Context) {

    companion object {
        private const val TAG = "BookRepository"
    }

    private val db = BookDatabase.getInstance(context)
    val dao = db.bookDao()
    private val bookDir = File(context.filesDir, "epubs").also { it.mkdirs() }

    /** importBook 返回已存在的书时为 true（用于 UI 显示跳过提示） */
    var isRecentDuplicate: Boolean = false
        private set

    fun getAllBooks(): Flow<List<Book>> = dao.getAllBooks()

    suspend fun getAllBooksOnce(): List<Book> = withContext(Dispatchers.IO) {
        dao.getAllBooksOnce()
    }

    suspend fun getBook(id: String): Book? = dao.getBook(id)

    suspend fun importBook(uri: Uri): Book = withContext(Dispatchers.IO) {
        val fileName = uri.lastPathSegment ?: "unknown.epub"

        // 先读取元数据用于查重（代价小：不复制文件）
        val tempFile = File(context.cacheDir, "temp_import_${System.currentTimeMillis()}.epub")
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw Exception("Cannot open file")
        inputStream.use { input ->
            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
        }
        val (title, author) = parseEpubMetadata(tempFile)

        // 查重：已存在同名书则跳过，但更新最后阅读/访问时间，让它置顶到最前
        val existing = dao.getBookByTitle(title)
        if (existing != null) {
            Log.i(TAG, "Skip duplicate import: '$title' already exists (id=${existing.id}), touch lastReadAt")
            isRecentDuplicate = true
            tempFile.delete()
            dao.updateLastReadAt(existing.id, System.currentTimeMillis())
            return@withContext dao.getBook(existing.id) ?: existing
        }

        isRecentDuplicate = false

        val id = UUID.randomUUID().toString()
        val destFileName = "book_$id.epub"
        val file = File(bookDir, destFileName)
        tempFile.renameTo(file)

        Log.d(TAG, "File saved: ${file.absolutePath} (${file.length()} bytes)")
        Log.d(TAG, "Parsed metadata: title='$title', author='$author'")

        val now = System.currentTimeMillis()
        val book = Book(
            id = id,
            title = title,
            author = author,
            filePath = file.absolutePath,
            addedAt = now,
            lastReadAt = now,
        )

        dao.upsert(book)
        book
    }

    /** 从已下载的 EPUB 文件直接导入本地书库（去重） */
    suspend fun importEpubFile(epubFile: File): Book = withContext(Dispatchers.IO) {
        val (title, author) = parseEpubMetadata(epubFile)

        // 查重：已存在同名书则跳过，但更新最后阅读时间使其置顶
        val existing = dao.getBookByTitle(title)
        if (existing != null) {
            Log.i(TAG, "Skip duplicate importEpub: '$title' already exists (id=${existing.id}), touch lastReadAt")
            isRecentDuplicate = true
            epubFile.delete()
            dao.updateLastReadAt(existing.id, System.currentTimeMillis())
            return@withContext dao.getBook(existing.id) ?: existing
        }

        isRecentDuplicate = false

        val id = UUID.randomUUID().toString()
        val fileName = "book_$id.epub"
        val destFile = File(bookDir, fileName)
        epubFile.renameTo(destFile)
        Log.d(TAG, "File imported: ${destFile.absolutePath} (${destFile.length()} bytes)")
        val now = System.currentTimeMillis()
        val book = Book(
            id = id,
            title = title,
            author = author,
            filePath = destFile.absolutePath,
            addedAt = now,
            lastReadAt = now,
        )
        dao.upsert(book)
        book
    }

    private val KNOWN_ISBN_MAP = mapOf(
        "9787547718728" to "西游记（注音彩图版）",
        "9787547718704" to "三国演义（注音彩图版）",
        "9787547718711" to "水浒传（注音彩图版）",
        "9787547718698" to "红楼梦（注音彩图版）",
    )

    private fun cleanPdfTitle(rawName: String): String {
        var base = rawName.removeSuffix(".pdf").removeSuffix(".PDF").trim()

        // 1. 检查是否匹配已知 ISBN 字典
        val isbnMatch = Regex("""(?:isbn[_\\-\\s]*)?([0-9]{13}|[0-9]{10})""", RegexOption.IGNORE_CASE).find(base)
        val isbn = isbnMatch?.groupValues?.get(1)
        if (isbn != null && KNOWN_ISBN_MAP.containsKey(isbn)) {
            return KNOWN_ISBN_MAP[isbn]!!
        }

        // 2. 剥离 (z-library.*)、(Author Unknown) 等括号块
        base = base.replace(Regex("""\((?:Author\s+Unknown|Unknown\s+Author|z-library[^)]*|1lib[^)]*|z-lib[^)]*|oceanofpdf[^)]*)\)""", RegexOption.IGNORE_CASE), "")
        base = base.replace(Regex("""(?:Author\s+Unknown|Unknown\s+Author)""", RegexOption.IGNORE_CASE), "")

        // 3. 去掉尾部的 _22, _1 这类版本号
        base = base.replace(Regex("""_\d{1,3}$"""), "")

        // 4. 清理残余垃圾词
        val tokens = base.split(Regex("[_\\-\\s,]+")).filter { tk ->
            val low = tk.lowercase().trim()
            low.isNotBlank() &&
            !low.contains("z-library") &&
            !low.contains("1lib") &&
            !low.contains("z-lib") &&
            !low.contains("oceanofpdf") &&
            !low.contains("watermark") &&
            low != "isbn" &&
            !Regex("^\\d{9,13}$").matches(low)
        }
        val joined = tokens.joinToString(" ").trim().trim(',', '_', '-').trim()

        if (joined.length >= 2) return joined
        if (isbn != null) return "ISBN $isbn"
        return rawName.removeSuffix(".pdf").removeSuffix(".PDF").trim()
    }

    suspend fun updateBookTitle(id: String, newTitle: String) = withContext(Dispatchers.IO) {
        val clean = newTitle.trim()
        if (clean.isNotBlank()) {
            dao.updateTitle(id, clean)
        }
    }

    /** 从已下载或选择的 PDF 文件直接导入本地书库 */
    suspend fun importPdfFile(pdfFile: File, rawTitle: String? = null): Book = withContext(Dispatchers.IO) {
        val title = cleanPdfTitle(rawTitle ?: pdfFile.name)

        val existing = dao.getBookByTitle(title)
        if (existing != null && File(existing.filePath).exists()) {
            Log.i(TAG, "Skip duplicate importPdf: '$title' already exists (id=${existing.id}), touch lastReadAt")
            isRecentDuplicate = true
            pdfFile.delete()
            dao.updateLastReadAt(existing.id, System.currentTimeMillis())
            return@withContext dao.getBook(existing.id) ?: existing
        }

        isRecentDuplicate = false

        val id = UUID.randomUUID().toString()
        val fileName = "book_$id.pdf"
        val destFile = File(bookDir, fileName)
        if (!pdfFile.renameTo(destFile)) {
            pdfFile.copyTo(destFile, overwrite = true)
            pdfFile.delete()
        }
        Log.d(TAG, "PDF imported: ${destFile.absolutePath} (${destFile.length()} bytes)")
        val now = System.currentTimeMillis()
        val book = Book(
            id = id,
            title = title,
            author = "PDF",
            filePath = destFile.absolutePath,
            addedAt = now,
            lastReadAt = now,
        )
        dao.upsert(book)
        extractCover(book.id)?.let { updateBookCover(book.id, it) }
        book
    }

    /** 更新 PDF 阅读进度（页码与百分比） */
    suspend fun updatePdfProgress(bookId: String, pageIndex: Int, progress: Float) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.updateProgress(
            id = bookId,
            timestamp = now,
            href = "page_$pageIndex",
            index = pageIndex,
            progress = progress,
            cfi = null,
            paraIdx = 0,
            theme = "default",
            fSize = 18
        )
    }

    /** Parse EPUB metadata from package.opf — handles namespaced XML */
    private fun parseEpubMetadata(file: File): Pair<String, String> {
        try {
            val zip = ZipFile(file)
            val containerEntry = zip.getEntry("META-INF/container.xml")
            if (containerEntry == null) {
                Log.w(TAG, "No META-INF/container.xml found")
                zip.close()
                return Pair(file.name.removeSuffix(".epub"), "Unknown")
            }

            val containerBytes = zip.getInputStream(containerEntry).readBytes()
            val containerStr = String(containerBytes, Charsets.UTF_8)
            Log.d(TAG, "container.xml: $containerStr")

            val dbf = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }
            val db = dbf.newDocumentBuilder()
            val doc = db.parse(containerBytes.inputStream())

            // Use namespace-aware search: urn:oasis:names:tc:opendocument:xmlns:container
            val rootfileElements = doc.getElementsByTagNameNS("*", "rootfile")
            if (rootfileElements.length == 0) {
                // Fallback: try without namespace
                val fallback = doc.getElementsByTagName("rootfile")
                if (fallback.length == 0) {
                    Log.w(TAG, "No rootfile element found at all")
                    zip.close()
                    return Pair(file.name.removeSuffix(".epub"), "Unknown")
                }
                // In this case, use fallback
                val opfPath = fallback.item(0).attributes.getNamedItem("full-path")?.textContent
                if (opfPath == null) { zip.close(); return Pair(file.name.removeSuffix(".epub"), "Unknown") }
                zip.close()
                return parseOpfMetadata(file, opfPath)
            }

            val opfPath = rootfileElements.item(0).attributes.getNamedItem("full-path")?.textContent
            if (opfPath == null) {
                Log.w(TAG, "No full-path attribute in rootfile")
                zip.close()
                return Pair(file.name.removeSuffix(".epub"), "Unknown")
            }

            Log.d(TAG, "OPF path: $opfPath")
            zip.close()
            return parseOpfMetadata(file, opfPath)

        } catch (e: Exception) {
            Log.e(TAG, "parseEpubMetadata error", e)
            return Pair(file.name.removeSuffix(".epub"), "Unknown")
        }
    }

    private fun parseOpfMetadata(file: File, opfRelativePath: String): Pair<String, String> {
        try {
            val zip = ZipFile(file)
            val entry = zip.getEntry(opfRelativePath)
            if (entry == null) {
                Log.w(TAG, "OPF entry not found: $opfRelativePath")
                zip.close()
                return Pair(file.name.removeSuffix(".epub"), "Unknown")
            }

            val opfStr = zip.getInputStream(entry).bufferedReader().readText()
            Log.d(TAG, "OPF content (first 500 chars): ${opfStr.take(500)}")

            var title = file.name.removeSuffix(".epub")
            var author = "Unknown"

            // Strategy 1: Parse with JSoup (ignores XML namespaces, works for most EPUBs)
            try {
                val jDoc = Jsoup.parse(opfStr, "", org.jsoup.parser.Parser.xmlParser())
                val metadataTag = jDoc.select("metadata").first()
                if (metadataTag != null) {
                    val titleTag = metadataTag.select("title").first()
                    if (titleTag != null) {
                        val t = titleTag.text().trim()
                        if (t.isNotEmpty()) title = t
                    }
                    val creatorTag = metadataTag.select("creator").first()
                    if (creatorTag != null) {
                        val a = creatorTag.text().trim()
                        if (a.isNotEmpty()) author = a
                    }
                    Log.d(TAG, "JSoup parsed: title='$title', author='$author'")
                } else {
                    Log.w(TAG, "JSoup: No <metadata> found")
                }
            } catch (e: Exception) {
                Log.w(TAG, "JSoup parse failed", e)
            }

            // Strategy 2: If JSoup didn't find title, fall back to DOM with namespace awareness
            if (title == file.name.removeSuffix(".epub")) {
                try {
                    val dbf = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }
                    val db = dbf.newDocumentBuilder()
                    val doc = db.parse(opfStr.byteInputStream())

                    // Try namespace-aware search
                    val metadataElements = doc.getElementsByTagNameNS("*", "metadata")
                    Log.d(TAG, "DOM namespace-aware: metadata count = ${metadataElements.length}")

                    if (metadataElements.length > 0) {
                        val metadata = metadataElements.item(0)
                        val children = metadata.childNodes
                        for (i in 0 until children.length) {
                            val node = children.item(i)
                            if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                            val nodeName = node.nodeName
                            Log.d(TAG, "  metadata child: $nodeName = ${node.textContent?.take(50)}")
                            if (nodeName == "title" || nodeName.endsWith(":title")) {
                                val t = node.textContent?.trim()
                                if (!t.isNullOrBlank()) title = t
                            }
                            if (nodeName == "creator" || nodeName.endsWith(":creator")) {
                                val a = node.textContent?.trim()
                                if (!a.isNullOrBlank()) author = a
                            }
                        }
                    } else {
                        // Strategy 3: DOM without namespace awareness (for EPUBs without namespace declarations)
                        val fallbackMetadata = doc.getElementsByTagName("metadata")
                        Log.d(TAG, "DOM no-namespace: metadata count = ${fallbackMetadata.length}")
                        if (fallbackMetadata.length > 0) {
                            val metadata = fallbackMetadata.item(0)
                            val children = metadata.childNodes
                            for (i in 0 until children.length) {
                                val node = children.item(i)
                                if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                                val nodeName = node.nodeName
                                if (nodeName == "title" || nodeName.endsWith(":title")) {
                                    val t = node.textContent?.trim()
                                    if (!t.isNullOrBlank()) title = t
                                }
                                if (nodeName == "creator" || nodeName.endsWith(":creator")) {
                                    val a = node.textContent?.trim()
                                    if (!a.isNullOrBlank()) author = a
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "DOM parse failed", e)
                }
            }

            zip.close()
            Log.d(TAG, "Final parsed: title='$title', author='$author'")
            return Pair(title, author)

        } catch (e: Exception) {
            Log.e(TAG, "parseOpfMetadata error", e)
            return Pair(file.name.removeSuffix(".epub"), "Unknown")
        }
    }

    /** Extract cover image from EPUB / PDF */
    suspend fun extractCover(bookId: String): String? = withContext(Dispatchers.IO) {
        val book = dao.getBook(bookId) ?: return@withContext null
        val file = File(book.filePath)
        if (!file.exists()) return@withContext null

        if (file.name.endsWith(".pdf", ignoreCase = true)) {
            return@withContext extractPdfCover(bookId, file)
        }

        try {
            val zip = ZipFile(file)
            val containerBytes = zip.getEntry("META-INF/container.xml")?.let { zip.getInputStream(it).readBytes() }
                ?: run { zip.close(); return@withContext null }
            val dbf = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }
            val db = dbf.newDocumentBuilder()
            val doc = db.parse(containerBytes.inputStream())
            val rootfiles = doc.getElementsByTagNameNS("*", "rootfile")
            if (rootfiles.length == 0) { zip.close(); return@withContext null }

            val opfPath = rootfiles.item(0).attributes.getNamedItem("full-path")?.textContent
                ?: run { zip.close(); return@withContext null }
            val baseDir = File(opfPath).parent?.replace('\\', '/') ?: ""
            val opfEntry = zip.getEntry(opfPath) ?: run { zip.close(); return@withContext null }

            val opfBytes = zip.getInputStream(opfEntry).readBytes()
            val opfDoc = db.parse(opfBytes.inputStream())

            var coverHref: String? = null

            // 1) EPUB 2: Find <meta name="cover" content="..."/>
            var coverId: String? = null
            val metas = opfDoc.getElementsByTagNameNS("*", "meta")
            for (i in 0 until metas.length) {
                val meta = metas.item(i)
                if (meta.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                    val nameVal = meta.attributes?.getNamedItem("name")?.textContent
                    if ("cover".equals(nameVal, ignoreCase = true)) {
                        coverId = meta.attributes?.getNamedItem("content")?.textContent
                        break
                    }
                }
            }

            val manifest = opfDoc.getElementsByTagNameNS("*", "manifest").item(0)
            if (manifest != null) {
                val items = manifest.childNodes
                val manifestItems = mutableListOf<org.w3c.dom.Node>()
                for (i in 0 until items.length) {
                    val item = items.item(i)
                    if (item.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                        manifestItems.add(item)
                    }
                }

                // 2) 如果有 coverId，优先在 manifest 里找对应 id 的 item
                if (!coverId.isNullOrBlank()) {
                    val matched = manifestItems.firstOrNull {
                        coverId == it.attributes?.getNamedItem("id")?.textContent
                    }
                    coverHref = matched?.attributes?.getNamedItem("href")?.textContent
                }

                // 3) EPUB 3 标准: properties="cover-image"
                if (coverHref == null) {
                    val matched = manifestItems.firstOrNull {
                        val props = it.attributes?.getNamedItem("properties")?.textContent ?: ""
                        props.contains("cover-image", ignoreCase = true)
                    }
                    coverHref = matched?.attributes?.getNamedItem("href")?.textContent
                }

                // 4) 常见规范: id 命名为 cover / cover-image 且为图片类型
                if (coverHref == null) {
                    val matched = manifestItems.firstOrNull {
                        val id = it.attributes?.getNamedItem("id")?.textContent?.lowercase() ?: ""
                        val media = it.attributes?.getNamedItem("media-type")?.textContent?.lowercase() ?: ""
                        (id == "cover" || id == "cover-image" || id == "cover_image") && media.startsWith("image/")
                    }
                    coverHref = matched?.attributes?.getNamedItem("href")?.textContent
                }

                // 5) 常见规范: href 包含 cover 且为图片类型
                if (coverHref == null) {
                    val matched = manifestItems.firstOrNull {
                        val href = it.attributes?.getNamedItem("href")?.textContent?.lowercase() ?: ""
                        val media = it.attributes?.getNamedItem("media-type")?.textContent?.lowercase() ?: ""
                        media.startsWith("image/") && (href.contains("cover") || href.contains("fengmian"))
                    }
                    coverHref = matched?.attributes?.getNamedItem("href")?.textContent
                }
            }

            // 6) 从 zip entry 尝试获取对应图片
            var coverEntry: java.util.zip.ZipEntry? = null
            if (!coverHref.isNullOrBlank()) {
                val fullPath = if (baseDir.isNotEmpty()) "$baseDir/$coverHref" else coverHref
                coverEntry = zip.getEntry(fullPath) ?: zip.getEntry(coverHref)
            }

            // 7) 启发式回退：直接在 zip 中找常见的封面文件名
            if (coverEntry == null) {
                val candidateNames = listOf(
                    "cover.jpg", "cover.jpeg", "cover.png",
                    "images/cover.jpg", "images/cover.jpeg", "images/cover.png",
                    "OEBPS/cover.jpg", "OEBPS/cover.jpeg", "OEBPS/cover.png", "OEBPS/images/cover.jpg"
                )
                for (cName in candidateNames) {
                    val path = if (baseDir.isNotEmpty()) "$baseDir/$cName" else cName
                    val entry = zip.getEntry(path) ?: zip.getEntry(cName)
                    if (entry != null) {
                        coverEntry = entry
                        break
                    }
                }
            }

            if (coverEntry == null) { zip.close(); return@withContext null }

            val coverFile = File(bookDir, "cover_$bookId.jpg")
            zip.getInputStream(coverEntry).use { input ->
                FileOutputStream(coverFile).use { output -> input.copyTo(output) }
            }

            zip.close()
            return@withContext coverFile.absolutePath

        } catch (e: Exception) {
            Log.e(TAG, "extractCover error", e)
            return@withContext null
        }
    }

    private fun extractPdfCover(bookId: String, file: File): String? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount <= 0) return null
            val page = renderer.openPage(0)
            val targetWidth = 480
            val targetHeight = (targetWidth.toFloat() * page.height / page.width).toInt().coerceAtLeast(100)
            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val coverFile = File(bookDir, "cover_$bookId.jpg")
            FileOutputStream(coverFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()
            coverFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "extractPdfCover error", e)
            null
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    /** Parse TOC from EPUB */
    suspend fun parseToc(bookId: String): List<TocEntry> = withContext(Dispatchers.IO) {
        val book = dao.getBook(bookId) ?: return@withContext emptyList()
        val file = File(book.filePath)
        if (!file.exists()) return@withContext emptyList()

        try {
            val zip = ZipFile(file)
            val tocEntries = mutableListOf<TocEntry>()

            // Step 1: Try OPF-based NCX resolution (EPUB spec — most reliable)
            val containerBytes = zip.getEntry("META-INF/container.xml")?.let { zip.getInputStream(it).readBytes() }
            if (containerBytes != null) {
                val dbf = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }
                val db = dbf.newDocumentBuilder()
                val containerDoc = db.parse(containerBytes.inputStream())
                val rootfiles = containerDoc.getElementsByTagNameNS("*", "rootfile")
                if (rootfiles.length > 0) {
                    val opfPath = rootfiles.item(0).attributes.getNamedItem("full-path")?.textContent
                    if (opfPath != null) {
                        val baseDir = File(opfPath).parent?.replace('\\', '/') ?: ""
                        val opfEntry = zip.getEntry(opfPath)
                        if (opfEntry != null) {
                            val opfDoc = db.parse(zip.getInputStream(opfEntry))
                            // Find NCX id from spine's toc attribute
                            val spine = opfDoc.getElementsByTagNameNS("*", "spine").item(0)
                            val ncxId = spine?.attributes?.getNamedItem("toc")?.textContent

                            // Build manifest map to resolve NCX href
                            var ncxHref: String? = null
                            if (ncxId != null) {
                                val manifest = opfDoc.getElementsByTagNameNS("*", "manifest").item(0)
                                if (manifest != null) {
                                    val items = manifest.childNodes
                                    for (i in 0 until items.length) {
                                        val item = items.item(i)
                                        if (item.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                                            val attrs = item.attributes
                                            val id = attrs.getNamedItem("id")?.textContent
                                            if (id == ncxId) {
                                                ncxHref = attrs.getNamedItem("href")?.textContent
                                                break
                                            }
                                        }
                                    }
                                }
                            }

                            // Parse NCX if found
                            if (ncxHref != null) {
                                val ncxFullPath = if (baseDir.isNotEmpty()) "$baseDir/$ncxHref" else ncxHref
                                val ncxEntry = zip.getEntry(ncxFullPath)
                                if (ncxEntry != null) {
                                    val ncxDoc = db.parse(zip.getInputStream(ncxEntry))
                                    val navPoints = ncxDoc.getElementsByTagNameNS("*", "navPoint")
                                    for (i in 0 until navPoints.length) {
                                        val np = navPoints.item(i) as? org.w3c.dom.Element ?: continue
                                        val textNode = np.getElementsByTagNameNS("*", "text")?.item(0) ?: continue
                                        val text = textNode.textContent
                                        val contentNode = np.getElementsByTagNameNS("*", "content")?.item(0) ?: continue
                                        val src = contentNode.attributes.getNamedItem("src")?.textContent ?: continue
                                        val depth = countDepth(np)
                                        tocEntries.add(TocEntry(text, src, depth))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Step 2: Fallback — hardcoded paths for malformed EPUBs
            if (tocEntries.isEmpty()) {
                val fallbackPaths = listOf(
                    "OEBPS/toc.ncx", "toc.ncx", "EPUB/toc.ncx",
                    "OPS/toc.ncx", "OPS/ncx.ncx",
                )
                val tocNcx = fallbackPaths.firstNotNullOfOrNull { zip.getEntry(it) }
                if (tocNcx != null) {
                    val dbf = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }
                    val db = dbf.newDocumentBuilder()
                    val ncxDoc = db.parse(zip.getInputStream(tocNcx))
                    val navPoints = ncxDoc.getElementsByTagNameNS("*", "navPoint")
                    for (i in 0 until navPoints.length) {
                        val np = navPoints.item(i) as? org.w3c.dom.Element ?: continue
                        val textNode = np.getElementsByTagNameNS("*", "text")?.item(0) ?: continue
                        val text = textNode.textContent
                        val contentNode = np.getElementsByTagNameNS("*", "content")?.item(0) ?: continue
                        val src = contentNode.attributes.getNamedItem("src")?.textContent ?: continue
                        val depth = countDepth(np)
                        tocEntries.add(TocEntry(text, src, depth))
                    }
                }
            }

            // Step 3: EPUB 3 nav.xhtml fallback
            if (tocEntries.isEmpty()) {
                val navXhtml = zip.getEntry("OEBPS/nav.xhtml")
                    ?: zip.getEntry("EPUB/nav.xhtml")
                    ?: zip.getEntry("OPS/nav.xhtml")
                    ?: zip.getEntry("nav.xhtml")
                if (navXhtml != null) {
                    val navHtml = zip.getInputStream(navXhtml).bufferedReader().readText()
                    val doc = Jsoup.parse(navHtml)
                    val links = doc.select("nav li a")
                    for (link in links) {
                        val href = link.attr("href")
                        val text = link.text()
                        if (href.isNotEmpty() && text.isNotEmpty()) {
                            tocEntries.add(TocEntry(text, href, extractDepth(link)))
                        }
                    }
                }
            }

            zip.close()
            tocEntries

        } catch (e: Exception) {
            Log.e(TAG, "parseToc error", e)
            emptyList()
        }
    }

    /** Parse spine (reading order) from EPUB */
    suspend fun parseSpine(bookId: String): List<Chapter> = withContext(Dispatchers.IO) {
        val book = dao.getBook(bookId) ?: return@withContext emptyList()
        val file = File(book.filePath)
        if (!file.exists()) return@withContext emptyList()

        try {
            val zip = ZipFile(file)
            val containerBytes = zip.getEntry("META-INF/container.xml")?.let { zip.getInputStream(it).readBytes() }
                ?: run { zip.close(); return@withContext emptyList() }

            val dbf = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }
            val db = dbf.newDocumentBuilder()
            val doc = db.parse(containerBytes.inputStream())
            val rootfiles = doc.getElementsByTagNameNS("*", "rootfile")
            if (rootfiles.length == 0) { zip.close(); return@withContext emptyList() }

            val opfPath = rootfiles.item(0).attributes.getNamedItem("full-path")?.textContent
                ?: run { zip.close(); return@withContext emptyList() }
            val baseDir = File(opfPath).parent?.replace('\\', '/') ?: ""
            val opfEntry = zip.getEntry(opfPath) ?: run { zip.close(); return@withContext emptyList() }

            val opfBytes = zip.getInputStream(opfEntry).readBytes()
            val opfDoc = db.parse(opfBytes.inputStream())

            // Build manifest map
            val manifestMap = mutableMapOf<String, String>()
            val manifest = opfDoc.getElementsByTagNameNS("*", "manifest").item(0)
            if (manifest != null) {
                val items = manifest.childNodes
                for (i in 0 until items.length) {
                    val item = items.item(i)
                    if (item.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                        val attrs = item.attributes
                        val id = attrs.getNamedItem("id")?.textContent ?: continue
                        val href = attrs.getNamedItem("href")?.textContent ?: continue
                        manifestMap[id] = href
                    }
                }
            }

            Log.d(TAG, "Manifest entries: ${manifestMap.size}")

            // Parse spine
            val chapters = mutableListOf<Chapter>()
            val spine = opfDoc.getElementsByTagNameNS("*", "spine").item(0)
            if (spine != null) {
                val refs = spine.childNodes
                var index = 0
                for (i in 0 until refs.length) {
                    val ref = refs.item(i)
                    if (ref.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                        val idref = ref.attributes.getNamedItem("idref")?.textContent ?: continue
                        val href = manifestMap[idref] ?: continue
                        val fullHref = if (baseDir.isNotEmpty()) "$baseDir/$href" else href
                        chapters.add(Chapter(idref, fullHref, index++))
                    }
                }
            }

            Log.d(TAG, "Spine entries: ${chapters.size}")

            if (chapters.isEmpty()) {
                // Fallback: If spine parsing failed, try getting all HTML files from manifest
                Log.w(TAG, "Spine empty, using manifest fallback")
                manifestMap.forEach { (id, href) ->
                    val ext = href.substringAfterLast('.', "")
                    if (ext in listOf("html", "xhtml", "htm")) {
                        val fullHref = if (baseDir.isNotEmpty()) "$baseDir/$href" else href
                        chapters.add(Chapter(id, fullHref, chapters.size))
                    }
                }
            }

            zip.close()
            chapters

        } catch (e: Exception) {
            Log.e(TAG, "parseSpine error", e)
            emptyList()
        }
    }

    /** Extract HTML content for a chapter */
    suspend fun getChapterContent(bookId: String, chapterHref: String): String? = withContext(Dispatchers.IO) {
        val book = dao.getBook(bookId) ?: return@withContext null
        val file = File(book.filePath)
        if (!file.exists()) return@withContext null

        try {
            val zip = ZipFile(file)
            var actualEntry = zip.getEntry(chapterHref)

            if (actualEntry == null) {
                // Try alternative paths
                val altPaths = listOf(
                    "OEBPS/$chapterHref",
                    "OPS/$chapterHref",
                    chapterHref.removePrefix("OEBPS/"),
                    chapterHref.removePrefix("OPS/"),
                    chapterHref.substringAfterLast('/'),
                    chapterHref.removePrefix("/"),
                    "OEBPS/${chapterHref.substringAfterLast('/')}",
                    "OPS/${chapterHref.substringAfterLast('/')}",
                )
                for (altPath in altPaths) {
                    val altEntry = zip.getEntry(altPath)
                    if (altEntry != null) {
                        actualEntry = altEntry
                        break
                    }
                }
                if (actualEntry == null) {
                    Log.w(TAG, "Chapter entry not found: $chapterHref")
                    val allEntries = zip.entries().toList().map { it.name }
                    Log.d(TAG, "Available entries (first 20): ${allEntries.take(20)}")
                    zip.close()
                    return@withContext null
                }
            }

            val html = zip.getInputStream(actualEntry).bufferedReader().readText()
            zip.close()

            val baseDir = File(chapterHref).parent?.replace('\\', '/') ?: ""
            val doc = Jsoup.parse(html)
            doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml)

            val jarPath = file.absolutePath
            doc.select("img[src]").forEach { img ->
                val src = img.attr("src")
                if (!src.startsWith("http") && !src.startsWith("data:")) {
                    img.attr("src", "epub-resource://${jarPath}!/${normalizePath(baseDir, src)}")
                }
            }
            doc.select("link[href]").forEach { link ->
                val href = link.attr("href")
                if (href.endsWith(".css") && !href.startsWith("http")) {
                    link.attr("href", "epub-resource://${jarPath}!/${normalizePath(baseDir, href)}")
                }
            }

            // Extract body content + head styles
            val headContent = buildString {
                doc.head()?.children()?.forEach { child ->
                    if (child.tagName() in listOf("style", "link", "title", "meta")) {
                        append(child.outerHtml()).append('\n')
                    }
                }
            }

            return@withContext headContent + (doc.body()?.html() ?: "")

        } catch (e: Exception) {
            Log.e(TAG, "getChapterContent error", e)
            null
        }
    }

    /** Get resource binary from EPUB (for custom WebView protocol) */
    fun getResource(resourceUri: String): ByteArray? {
        val match = Regex("epub-resource://(.+?)!/(.+)").find(resourceUri) ?: return null
        val zipPath = match.groupValues[1]
        val entryPath = match.groupValues[2]

        return try {
            val zip = ZipFile(zipPath)
            val entry = zip.getEntry(entryPath)
            val bytes = entry?.let { zip.getInputStream(it).readBytes() }
            zip.close()
            bytes
        } catch (e: Exception) { null }
    }

    suspend fun deleteBook(book: Book) = withContext(Dispatchers.IO) {
        dao.delete(book)
        File(book.filePath).delete()
        book.coverPath?.let { File(it).delete() }
    }

    suspend fun updateProgress(id: String, href: String?, index: Int, progress: Float, cfi: String?, paraIdx: Int = 0, theme: String = "default", fontSize: Int = 18) {
        dao.updateProgress(id, System.currentTimeMillis(), href, index, progress, cfi, paraIdx, theme, fontSize)
    }

    suspend fun updateBookTheme(id: String, themeId: String) = withContext(Dispatchers.IO) {
        val book = dao.getBook(id) ?: return@withContext
        dao.upsert(book.copy(themeId = themeId))
    }

    suspend fun updateBookFontSize(id: String, size: Int) = withContext(Dispatchers.IO) {
        val book = dao.getBook(id) ?: return@withContext
        dao.upsert(book.copy(fontSize = size))
    }

    suspend fun updateBookBrightness(id: String, brightness: Int) = withContext(Dispatchers.IO) {
        val book = dao.getBook(id) ?: return@withContext
        dao.upsert(book.copy(textBrightness = brightness))
    }

    suspend fun updateBookParagraph(id: String, paraIdx: Int) = withContext(Dispatchers.IO) {
        val book = dao.getBook(id) ?: return@withContext
        dao.upsert(book.copy(currentParagraphIndex = paraIdx))
    }

    suspend fun updateBookCover(id: String, coverPath: String) = withContext(Dispatchers.IO) {
        val book = dao.getBook(id) ?: return@withContext
        dao.upsert(book.copy(coverPath = coverPath))
    }

    suspend fun updateBookTtsConfig(id: String, provider: String, voice: String, speed: Float) = withContext(Dispatchers.IO) {
        dao.updateTtsConfig(id, provider, voice, speed)
    }

    // === Bookmark operations ===
    fun getAllBookmarks(): Flow<List<Bookmark>> = db.bookmarkDao().getAllBookmarks()
    
    fun getBookmarksForBook(bookId: String): Flow<List<Bookmark>> = db.bookmarkDao().getBookmarksForBook(bookId)
    
    suspend fun getBookmarksOnce(bookId: String): List<Bookmark> = db.bookmarkDao().getBookmarksOnce(bookId)

    suspend fun addBookmark(bookmark: Bookmark): Long = db.bookmarkDao().addBookmark(bookmark)
    
    suspend fun importBookmarks(bookmarks: List<Bookmark>) = db.bookmarkDao().addBookmarks(bookmarks)
    
    suspend fun updateBookmark(bookmark: Bookmark) = db.bookmarkDao().updateBookmark(bookmark)
    
    suspend fun deleteBookmark(id: Long) = db.bookmarkDao().deleteBookmarkById(id)
    
    suspend fun deleteBookmark(bookmark: Bookmark) = db.bookmarkDao().deleteBookmark(bookmark)

    // === Highlight operations ===
    fun getAllHighlights(): Flow<List<Highlight>> = db.highlightDao().getAllHighlights()
    
    fun getHighlightsForBook(bookId: String): Flow<List<Highlight>> = db.highlightDao().getHighlightsForBook(bookId)
    
    suspend fun getHighlightsOnce(bookId: String): List<Highlight> = db.highlightDao().getHighlightsOnce(bookId)

    suspend fun addHighlight(highlight: Highlight): Long = db.highlightDao().addHighlight(highlight)
    
    suspend fun importHighlights(highlights: List<Highlight>) = db.highlightDao().addHighlights(highlights)
    
    suspend fun updateHighlight(highlight: Highlight) = db.highlightDao().updateHighlight(highlight)
    
    suspend fun updateHighlightNote(id: Long, note: String?) = db.highlightDao().updateHighlightNote(id, note)
    
    suspend fun deleteHighlight(highlight: Highlight) = db.highlightDao().deleteHighlight(highlight)
    
    suspend fun deleteHighlightById(id: Long) = db.highlightDao().deleteHighlightById(id)

    // === Vocabulary operations ===
    fun getAllVocabulary(): Flow<List<Vocabulary>> = db.vocabularyDao().getAllVocabulary()
    fun getVocabularyByPlan(plan: String): Flow<List<Vocabulary>> = db.vocabularyDao().getVocabularyByPlan(plan)
    fun getPlanNames(): Flow<List<String>> = db.vocabularyDao().getPlanNames()
    suspend fun getPlanNamesOnce(): List<String> = db.vocabularyDao().getPlanNamesOnce()
    suspend fun deleteVocabularyByPlan(plan: String) = db.vocabularyDao().deleteByPlan(plan)
    
    suspend fun getVocabularyByWord(word: String): Vocabulary? = db.vocabularyDao().getVocabularyByWord(word)
    
    suspend fun insertVocabulary(vocabulary: Vocabulary): Long = db.vocabularyDao().insertVocabulary(vocabulary)
    
    suspend fun updateVocabularyDefinition(id: Long, pronunciation: String?, partOfSpeech: String?, definition: String?, example: String?) = 
        db.vocabularyDao().updateDefinition(id, pronunciation, partOfSpeech, definition, example)
    
    suspend fun updateVocabularyStructured(id: Long, pronunciation: String?, partOfSpeech: String?, chineseDef: String?, englishDef: String?, wordForms: String?, exampleJson: String?) =
        db.vocabularyDao().updateVocabularyStructured(id, pronunciation, partOfSpeech, chineseDef, englishDef, wordForms, exampleJson)
    
    suspend fun deleteVocabulary(id: Long) = db.vocabularyDao().deleteVocabulary(id)
    
    suspend fun isWordExists(word: String): Boolean = db.vocabularyDao().isWordExists(word)

    private fun countDepth(node: org.w3c.dom.Node): Int {
        var depth = 0
        var parent = node.parentNode
        while (parent != null) {
            if (parent.nodeName == "navPoint") depth++
            parent = parent.parentNode
        }
        return depth
    }

    private fun extractDepth(link: org.jsoup.nodes.Element): Int {
        var depth = 0
        var parent = link.parent()
        while (parent != null) {
            if (parent.tagName() == "ol" || parent.tagName() == "ul") depth++
            parent = parent.parent()
        }
        return (depth - 1).coerceAtLeast(0)
    }

    private fun normalizePath(baseDir: String, relativePath: String): String {
        if (relativePath.startsWith("/")) return relativePath.removePrefix("/")
        if (baseDir.isEmpty()) return relativePath
        return "$baseDir/$relativePath"
    }
}
