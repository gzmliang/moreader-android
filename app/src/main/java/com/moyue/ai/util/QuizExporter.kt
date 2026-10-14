package com.moyue.ai.util

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.moyue.ai.model.QuizQuestion
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object QuizExporter {

    private fun escapeHtml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun getExportDir(context: Context): File {
        val publicDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = if (publicDownload != null && publicDownload.exists()) {
            File(publicDownload, "Moreader")
        } else {
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Moreader")
        }
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        return targetDir
    }

    fun shareFile(context: Context, file: File, mimeType: String, title: String) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /**
     * Export Quiz to standard EPUB 3 file.
     */
    fun exportToEpub(
        context: Context,
        bookTitle: String,
        chapterTitle: String,
        questions: List<QuizQuestion>,
        withAnswers: Boolean = false
    ): File {
        val targetDir = getExportDir(context)
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        val suffix = if (withAnswers) "Solutions" else "Blank"
        val safeBookName = bookTitle.replace("[\\\\/:*?\"<>|]".toRegex(), "_").take(30)
        val fileName = "${safeBookName}_Quiz_${suffix}_$timeStamp.epub"
        val outFile = File(targetDir, fileName)

        val bookId = "urn:uuid:${UUID.randomUUID()}"
        val docTitle = if (withAnswers) "Quiz Solutions - $chapterTitle" else "Quiz Practice - $chapterTitle"

        var quizBodyHtml = ""
        questions.forEachIndexed { idx, q ->
            quizBodyHtml += "<div class=\"quiz-block\">\n"
            quizBodyHtml += "<div class=\"quiz-question\"><strong>Q${idx + 1}. ${escapeHtml(q.questionOriginal)}</strong></div>\n"
            quizBodyHtml += "<div class=\"quiz-options\">\n"
            q.options.forEach { opt ->
                quizBodyHtml += "<div class=\"quiz-opt\">${escapeHtml(opt)}</div>\n"
            }
            quizBodyHtml += "</div>\n"

            if (withAnswers) {
                quizBodyHtml += "<div class=\"quiz-answer-box\">\n"
                quizBodyHtml += "<div class=\"ans-badge\">✓ Standard Answer: <strong>${escapeHtml(q.correctAnswer)}</strong></div>\n"
                if (q.analysisOriginal.isNotBlank()) {
                    quizBodyHtml += "<div class=\"ans-exp\"><strong>Explanation:</strong> ${escapeHtml(q.analysisOriginal)}</div>\n"
                }
                quizBodyHtml += "</div>\n"
            } else {
                quizBodyHtml += "<div class=\"quiz-blank-answer\">My Answer: [ ____ ]</div>\n"
            }
            quizBodyHtml += "</div>\n"
        }

        FileOutputStream(outFile).use { fos ->
            ZipOutputStream(fos).use { zos ->
                // 1. mimetype (Must be STORED / uncompressed)
                val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
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
                val containerXml = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>""".trimIndent()
                zos.putNextEntry(ZipEntry("META-INF/container.xml"))
                zos.write(containerXml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // 3. OEBPS/content.opf
                val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
                val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="BookId">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>${escapeHtml(docTitle)}</dc:title>
    <dc:creator>Moreader AI Quiz</dc:creator>
    <dc:identifier id="BookId">$bookId</dc:identifier>
    <dc:language>en</dc:language>
    <meta property="dcterms:modified">$nowIso</meta>
  </metadata>
  <manifest>
    <item id="chapter1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
    <item id="style" href="style.css" media-type="text/css"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="chapter1"/>
  </spine>
</package>""".trimIndent()
                zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
                zos.write(opf.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // 4. OEBPS/toc.ncx
                val ncx = """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head><meta name="dtb:uid" content="$bookId"/></head>
  <docTitle><text>${escapeHtml(docTitle)}</text></docTitle>
  <navMap>
    <navPoint id="nav1" playOrder="1">
      <navLabel><text>${escapeHtml(docTitle)}</text></navLabel>
      <content src="chapter1.xhtml"/>
    </navPoint>
  </navMap>
</ncx>""".trimIndent()
                zos.putNextEntry(ZipEntry("OEBPS/toc.ncx"))
                zos.write(ncx.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // 5. OEBPS/style.css
                val css = """
body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; line-height: 1.6; padding: 4% 6%; color: #1a1a1a; }
h1 { font-size: 1.4em; border-bottom: 2px solid #2d5a47; padding-bottom: 0.3em; margin-bottom: 0.4em; color: #1b3d2f; }
.quiz-block { border: 1px solid #e2e8f0; border-radius: 8px; padding: 14px; margin: 16px 0; background: #fafafa; }
.quiz-question { font-size: 1.05em; margin-bottom: 8px; color: #111; }
.quiz-opt { padding: 4px 8px; margin: 4px 0; font-size: 0.95em; }
.quiz-answer-box { margin-top: 10px; padding: 10px; border-radius: 6px; background: #f0fdf4; border: 1px solid #bbf7d0; font-size: 0.9em; }
.ans-badge { color: #15803d; font-weight: 600; margin-bottom: 4px; }
.ans-exp { color: #374151; font-size: 0.88em; line-height: 1.5; }
.quiz-blank-answer { margin-top: 10px; font-size: 0.9em; color: #6b7280; border-top: 1px dashed #d1d5db; padding-top: 6px; }
""".trimIndent()
                zos.putNextEntry(ZipEntry("OEBPS/style.css"))
                zos.write(css.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // 6. OEBPS/chapter1.xhtml
                val chapter1 = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
  <title>${escapeHtml(docTitle)}</title>
  <link rel="stylesheet" type="text/css" href="style.css"/>
</head>
<body>
  <h1>${escapeHtml(docTitle)}</h1>
  <p style="color: #64748b; font-size: 0.9em; margin-bottom: 1.5em;">📖 ${escapeHtml(bookTitle)} · ${questions.size} Questions</p>
  $quizBodyHtml
</body>
</html>""".trimIndent()
                zos.putNextEntry(ZipEntry("OEBPS/chapter1.xhtml"))
                zos.write(chapter1.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return outFile
    }

    /**
     * Export Quiz to standard vector PDF file via Android native PdfDocument.
     */
    fun exportToPdf(
        context: Context,
        bookTitle: String,
        chapterTitle: String,
        questions: List<QuizQuestion>,
        withAnswers: Boolean = false
    ): File {
        val targetDir = getExportDir(context)
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        val suffix = if (withAnswers) "Solutions" else "Blank"
        val safeBookName = bookTitle.replace("[\\\\/:*?\"<>|]".toRegex(), "_").take(30)
        val fileName = "${safeBookName}_Quiz_${suffix}_$timeStamp.pdf"
        val outFile = File(targetDir, fileName)

        val pageWidth = 595 // A4 standard width in points (72 dpi)
        val pageHeight = 842 // A4 standard height
        val margin = 40
        val contentWidth = pageWidth - margin * 2

        val pdfDoc = PdfDocument()

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(27, 61, 47)
            textSize = 17f
            isFakeBoldText = true
        }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(100, 116, 139)
            textSize = 10f
        }
        val qPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(17, 24, 39)
            textSize = 11.5f
            isFakeBoldText = true
        }
        val optPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(55, 65, 81)
            textSize = 10.5f
        }
        val ansBadgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(21, 128, 61)
            textSize = 10.5f
            isFakeBoldText = true
        }
        val expPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(75, 85, 99)
            textSize = 9.5f
        }
        val blankLinePaint = Paint().apply {
            color = Color.rgb(209, 213, 219)
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }

        var pageNumber = 1
        var page = pdfDoc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas = page.canvas
        var currentY = margin.toFloat()

        fun drawHeader() {
            val docTitle = if (withAnswers) "Quiz Solutions & Explanations" else "Quiz Practice Sheet"
            val titleLayout = StaticLayout.Builder.obtain(docTitle, 0, docTitle.length, titlePaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            canvas.save()
            canvas.translate(margin.toFloat(), currentY)
            titleLayout.draw(canvas)
            canvas.restore()
            currentY += titleLayout.height + 4

            val subText = "Book: $bookTitle · Chapter: $chapterTitle · ${questions.size} Questions"
            val subLayout = StaticLayout.Builder.obtain(subText, 0, subText.length, subPaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            canvas.save()
            canvas.translate(margin.toFloat(), currentY)
            subLayout.draw(canvas)
            canvas.restore()
            currentY += subLayout.height + 14

            // Divider line
            canvas.drawLine(margin.toFloat(), currentY, (pageWidth - margin).toFloat(), currentY, Paint().apply {
                color = Color.rgb(45, 90, 71)
                strokeWidth = 1.5f
            })
            currentY += 16
        }

        drawHeader()

        questions.forEachIndexed { idx, q ->
            // Pre-calculate height of this question block
            val qText = "Q${idx + 1}. ${q.questionOriginal}"
            val qLayout = StaticLayout.Builder.obtain(qText, 0, qText.length, qPaint, contentWidth).build()

            val optLayouts = q.options.map { opt ->
                StaticLayout.Builder.obtain(opt, 0, opt.length, optPaint, contentWidth - 10).build()
            }
            val totalOptHeight = optLayouts.sumOf { it.height + 4 }

            val ansHeight = if (withAnswers) {
                val badgeLayout = StaticLayout.Builder.obtain("✓ Answer: ${q.correctAnswer}", 0, "✓ Answer: ${q.correctAnswer}".length, ansBadgePaint, contentWidth).build()
                val expLayout = if (q.analysisOriginal.isNotBlank()) {
                    StaticLayout.Builder.obtain("Explanation: ${q.analysisOriginal}", 0, "Explanation: ${q.analysisOriginal}".length, expPaint, contentWidth).build()
                } else null
                badgeLayout.height + 6 + (expLayout?.height ?: 0) + 12
            } else {
                24 // blank line height
            }

            val blockHeight = qLayout.height + 8 + totalOptHeight + ansHeight + 16

            if (currentY + blockHeight > pageHeight - margin) {
                // New page
                pdfDoc.finishPage(page)
                pageNumber++
                page = pdfDoc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                canvas = page.canvas
                currentY = margin.toFloat()
            }

            // Draw Question
            canvas.save()
            canvas.translate(margin.toFloat(), currentY)
            qLayout.draw(canvas)
            canvas.restore()
            currentY += qLayout.height + 6

            // Draw Options
            optLayouts.forEach { optLayout ->
                canvas.save()
                canvas.translate((margin + 10).toFloat(), currentY)
                optLayout.draw(canvas)
                canvas.restore()
                currentY += optLayout.height + 4
            }

            // Draw Answer / Blank
            if (withAnswers) {
                currentY += 4
                val ansText = "✓ Standard Answer: ${q.correctAnswer}"
                val badgeLayout = StaticLayout.Builder.obtain(ansText, 0, ansText.length, ansBadgePaint, contentWidth - 16).build()
                canvas.save()
                canvas.translate((margin + 10).toFloat(), currentY)
                badgeLayout.draw(canvas)
                canvas.restore()
                currentY += badgeLayout.height + 4

                if (q.analysisOriginal.isNotBlank()) {
                    val expText = "Explanation: ${q.analysisOriginal}"
                    val expLayout = StaticLayout.Builder.obtain(expText, 0, expText.length, expPaint, contentWidth - 16).build()
                    canvas.save()
                    canvas.translate((margin + 10).toFloat(), currentY)
                    expLayout.draw(canvas)
                    canvas.restore()
                    currentY += expLayout.height + 6
                }
            } else {
                currentY += 8
                val blankText = "My Answer: [ ______ ]"
                val blankLayout = StaticLayout.Builder.obtain(blankText, 0, blankText.length, subPaint, contentWidth).build()
                canvas.save()
                canvas.translate((margin + 10).toFloat(), currentY)
                blankLayout.draw(canvas)
                canvas.restore()
                currentY += blankLayout.height + 6
            }

            // Question separator line
            currentY += 6
            canvas.drawLine(margin.toFloat(), currentY, (pageWidth - margin).toFloat(), currentY, blankLinePaint)
            currentY += 12
        }

        pdfDoc.finishPage(page)

        FileOutputStream(outFile).use { fos ->
            pdfDoc.writeTo(fos)
        }
        pdfDoc.close()

        return outFile
    }
}
