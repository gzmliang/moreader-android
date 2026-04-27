package com.moreader.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.moreader.app.data.models.Book
import com.moreader.app.data.models.Chapter
import com.moreader.app.data.models.TocEntry
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
    private val dao = db.bookDao()
    private val cacheDir = File(context.cacheDir, "epubs").also { it.mkdirs() }

    fun getAllBooks(): Flow<List<Book>> = dao.getAllBooks()

    suspend fun getBook(id: String): Book? = dao.getBook(id)

    suspend fun importBook(uri: Uri): Book = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val fileName = "book_$id.epub"
        val file = File(cacheDir, fileName)

        // Copy EPUB to cache
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw Exception("Cannot open file")
        inputStream.use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }

        Log.d(TAG, "File saved: ${file.absolutePath} (${file.length()} bytes)")

        // Parse metadata
        val (title, author) = parseEpubMetadata(file)

        Log.d(TAG, "Parsed metadata: title='$title', author='$author'")

        val book = Book(
            id = id,
            title = title,
            author = author,
            filePath = file.absolutePath,
        )

        dao.upsert(book)
        book
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

    /** Extract cover image from EPUB */
    suspend fun extractCover(bookId: String): String? = withContext(Dispatchers.IO) {
        val book = dao.getBook(bookId) ?: return@withContext null
        val file = File(book.filePath)
        if (!file.exists()) return@withContext null

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

            // Find <meta name="cover" content="..."/>
            var coverId: String? = null
            val metas = opfDoc.getElementsByTagNameNS("*", "meta")
            for (i in 0 until metas.length) {
                val meta = metas.item(i)
                if (meta.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                    if ("cover" == meta.attributes.getNamedItem("name")?.textContent) {
                        coverId = meta.attributes.getNamedItem("content")?.textContent
                        break
                    }
                }
            }

            if (coverId == null) { zip.close(); return@withContext null }

            // Find the manifest item with that id
            val manifest = opfDoc.getElementsByTagNameNS("*", "manifest").item(0)
                ?: run { zip.close(); return@withContext null }
            val items = manifest.childNodes
            var coverHref: String? = null
            for (i in 0 until items.length) {
                val item = items.item(i)
                if (item.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                    if (coverId == item.attributes.getNamedItem("id")?.textContent) {
                        coverHref = item.attributes.getNamedItem("href")?.textContent
                        break
                    }
                }
            }

            if (coverHref == null) { zip.close(); return@withContext null }

            val fullPath = if (baseDir.isNotEmpty()) "$baseDir/$coverHref" else coverHref
            val coverEntry = zip.getEntry(fullPath) ?: zip.getEntry(coverHref)
                ?: run { zip.close(); return@withContext null }

            val coverFile = File(cacheDir, "cover_$bookId.jpg")
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

    /** Parse TOC from EPUB */
    suspend fun parseToc(bookId: String): List<TocEntry> = withContext(Dispatchers.IO) {
        val book = dao.getBook(bookId) ?: return@withContext emptyList()
        val file = File(book.filePath)
        if (!file.exists()) return@withContext emptyList()

        try {
            val zip = ZipFile(file)

            // Try toc.ncx (EPUB 2) — might be in root or under OEBPS/
            val tocNcx = zip.getEntry("OEBPS/toc.ncx")
                ?: zip.getEntry("toc.ncx")
                ?: zip.getEntry("EPUB/toc.ncx")
            val tocEntries = mutableListOf<TocEntry>()

            if (tocNcx != null) {
                val ncxBytes = zip.getInputStream(tocNcx).readBytes()
                val dbf = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }
                val db = dbf.newDocumentBuilder()
                val ncxDoc = db.parse(ncxBytes.inputStream())

                val navPoints = ncxDoc.getElementsByTagNameNS("*", "navPoint")
                for (i in 0 until navPoints.length) {
                    val np = navPoints.item(i) as? org.w3c.dom.Element ?: continue
                    val textNode = np.getElementsByTagNameNS("*", "text")?.item(0) ?: continue
                    val text = textNode.textContent ?: continue
                    val contentNode = np.getElementsByTagNameNS("*", "content")?.item(0) ?: continue
                    val src = contentNode.attributes.getNamedItem("src")?.textContent ?: continue
                    val depth = countDepth(np)
                    tocEntries.add(TocEntry(text, src, depth))
                }
            }

            if (tocEntries.isEmpty()) {
                // Try nav.xhtml (EPUB 3)
                val navXhtml = zip.getEntry("OEBPS/nav.xhtml")
                    ?: zip.getEntry("EPUB/nav.xhtml")
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
                    chapterHref.removePrefix("OEBPS/"),
                    chapterHref.substringAfterLast('/'),
                    chapterHref.removePrefix("/"),
                    "OEBPS/${chapterHref.substringAfterLast('/')}",
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

    suspend fun updateProgress(id: String, href: String?, index: Int, progress: Float, cfi: String?) {
        dao.updateProgress(id, System.currentTimeMillis(), href, index, progress, cfi)
    }

    suspend fun updateBookCover(id: String, coverPath: String) = withContext(Dispatchers.IO) {
        val book = dao.getBook(id) ?: return@withContext
        dao.upsert(book.copy(coverPath = coverPath))
    }

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
