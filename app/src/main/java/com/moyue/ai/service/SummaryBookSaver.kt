package com.moyue.ai.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.moyue.ai.model.AiSummaryResult
import com.moyue.app.data.BookDao
import com.moyue.app.data.models.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SummaryBookSaver {

    suspend fun saveSummaryAsBook(
        context: Context,
        bookDao: BookDao,
        summary: AiSummaryResult,
        sourceBookTitle: String,
        badgeText: String,
        suffixText: String
    ): Result<Book> = withContext(Dispatchers.IO) {
        try {
            val cleanTitle = if (summary.scope == "book") {
                "$sourceBookTitle $suffixText"
            } else {
                "$sourceBookTitle - Ch.${summary.chapterIndex + 1} $suffixText"
            }

            // Generate simple valid EPUB file
            val booksDir = File(context.filesDir, "ai_books")
            booksDir.mkdirs()
            val epubFile = File(booksDir, "summary_${System.currentTimeMillis()}.epub")

            createEpubZip(epubFile, cleanTitle, summary)

            // Generate simple cover
            val coverFile = File(booksDir, "cover_${System.currentTimeMillis()}.png")
            generateCover(coverFile, cleanTitle, badgeText)

            val bookId = java.util.UUID.randomUUID().toString()
            val newBook = Book(
                id = bookId,
                title = cleanTitle,
                author = "AI Companion",
                filePath = epubFile.absolutePath,
                coverPath = coverFile.absolutePath,
                addedAt = System.currentTimeMillis(),
                lastReadAt = System.currentTimeMillis()
            )

            bookDao.upsert(newBook)
            Result.success(newBook)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun generateCover(file: File, title: String, badgeText: String) {
        val width = 400
        val height = 600
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background
        canvas.drawColor(Color.parseColor("#1E293B")) // Slate 800

        // Badge banner
        val badgePaint = Paint().apply {
            color = Color.parseColor("#3B82F6") // Blue 500
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 40f, width.toFloat(), 120f, badgePaint)

        val badgeTextPaint = Paint().apply {
            color = Color.WHITE
            textSize = 32f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText(badgeText, width / 2f, 92f, badgeTextPaint)

        // Title text
        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = 28f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val shortTitle = if (title.length > 20) title.take(18) + "…" else title
        canvas.drawText(shortTitle, width / 2f, height / 2f, titlePaint)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
    }

    private fun createEpubZip(outFile: File, title: String, summary: AiSummaryResult) {
        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            // 1. mimetype (must be uncompressed stored)
            val mimeBytes = "application/epub+zip".toByteArray(StandardCharsets.US_ASCII)
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimeBytes.size.toLong()
                compressedSize = mimeBytes.size.toLong()
                crc = CRC32().apply { update(mimeBytes) }.value
            }
            zos.putNextEntry(mimeEntry)
            zos.write(mimeBytes)
            zos.closeEntry()

            // 2. META-INF/container.xml
            val containerXml = """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent()
            addZipTextFile(zos, "META-INF/container.xml", containerXml)

            // 3. OEBPS/chapter1.html
            val sbHtml = java.lang.StringBuilder()
            sbHtml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            sbHtml.append("<!DOCTYPE html>\n")
            sbHtml.append("<html xmlns=\"http://www.w3.org/1999/xhtml\">\n<head><title>").append(title).append("</title></head>\n<body>\n")
            sbHtml.append("<h1>").append(title).append("</h1>\n")
            for (p in summary.paragraphs) {
                if (p.original.isNotBlank()) {
                    sbHtml.append("<p><strong>").append(p.original).append("</strong></p>\n")
                }
                if (p.translation.isNotBlank()) {
                    sbHtml.append("<p style=\"color: #4B5563;\">").append(p.translation).append("</p>\n")
                }
                sbHtml.append("<hr/>\n")
            }
            sbHtml.append("</body></html>")
            addZipTextFile(zos, "OEBPS/chapter1.html", sbHtml.toString())

            // 4. OEBPS/content.opf
            val opf = """<?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId" version="2.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>$title</dc:title>
                    <dc:creator>Moreader AI</dc:creator>
                    <dc:language>en</dc:language>
                    <dc:identifier id="BookId">urn:uuid:${java.util.UUID.randomUUID()}</dc:identifier>
                  </metadata>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="chapter1" href="chapter1.html" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="chapter1"/>
                  </spine>
                </package>
            """.trimIndent()
            addZipTextFile(zos, "OEBPS/content.opf", opf)

            // 5. OEBPS/toc.ncx
            val ncx = """<?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <head>
                    <meta name="dtb:uid" content="urn:uuid:12345"/>
                    <meta name="dtb:depth" content="1"/>
                  </head>
                  <docTitle><text>$title</text></docTitle>
                  <navMap>
                    <navPoint id="navPoint-1" playOrder="1">
                      <navLabel><text>Chapter 1</text></navLabel>
                      <content src="chapter1.html"/>
                    </navPoint>
                  </navMap>
                </ncx>
            """.trimIndent()
            addZipTextFile(zos, "OEBPS/toc.ncx", ncx)
        }
    }

    private fun addZipTextFile(zos: ZipOutputStream, path: String, content: String) {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        val entry = ZipEntry(path)
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }
}
