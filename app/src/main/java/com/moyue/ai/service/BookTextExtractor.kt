package com.moyue.ai.service

import com.moyue.app.data.BookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

object BookTextExtractor {

    suspend fun extractChapterText(
        html: String,
        fallbackText: String = ""
    ): String = withContext(Dispatchers.Default) {
        if (html.isNotBlank()) {
            val doc = Jsoup.parse(html)
            val text = doc.body().text()
            if (text.isNotBlank()) return@withContext text
        }
        fallbackText
    }

    suspend fun extractBookOverview(
        repository: BookRepository?,
        bookId: String,
        bookTitle: String,
        currentChapterText: String = ""
    ): String = withContext(Dispatchers.IO) {
        if (repository == null) {
            return@withContext extractBookTocOverview(bookTitle, currentChapterText)
        }
        try {
            val sb = StringBuilder()
            sb.append("Book Title: ").append(bookTitle).append("\n\n")

            // 1. Table of Contents / Chapter List
            val toc = repository.parseToc(bookId)
            val chapters = repository.parseSpine(bookId)

            val chapterTitles = if (toc.isNotEmpty()) {
                TocLabelFormat.bodyIndices(toc.map { it.label }).mapIndexed { pos, origIdx ->
                    TocLabelFormat.format(pos + 1, toc[origIdx].label)
                }
            } else if (chapters.isNotEmpty()) {
                chapters.mapIndexed { idx, _ -> "${idx + 1}. Chapter ${idx + 1}" }
            } else {
                emptyList()
            }

            if (chapterTitles.isNotEmpty()) {
                sb.append("Complete Table of Contents / Chapter Milestones (Total ${chapterTitles.size} chapters):\n")
                chapterTitles.take(100).forEach { sb.append(it).append("\n") }
                if (chapterTitles.size > 100) {
                    sb.append("... (${chapterTitles.size - 100} more chapters omitted)\n")
                }
                sb.append("\n")
            }

            // 2. Sample Key Excerpts across the book (Beginning, Middle, Climax/Ending)
            val sampleHrefs = mutableListOf<String>()
            if (toc.isNotEmpty()) {
                val step = maxOf(1, toc.size / 5)
                for (i in toc.indices step step) {
                    sampleHrefs.add(toc[i].href)
                    if (sampleHrefs.size >= 5) break
                }
                if (toc.isNotEmpty() && !sampleHrefs.contains(toc.last().href)) {
                    sampleHrefs.add(toc.last().href)
                }
            } else if (chapters.isNotEmpty()) {
                val step = maxOf(1, chapters.size / 5)
                for (i in chapters.indices step step) {
                    sampleHrefs.add(chapters[i].href)
                    if (sampleHrefs.size >= 5) break
                }
                if (chapters.isNotEmpty() && !sampleHrefs.contains(chapters.last().href)) {
                    sampleHrefs.add(chapters.last().href)
                }
            }

            val samples = mutableListOf<String>()
            for (href in sampleHrefs) {
                val cleanHref = href.substringBefore('#')
                val html = repository.getChapterContent(bookId, cleanHref)
                if (!html.isNullOrBlank()) {
                    val plain = extractChapterText(html)
                    if (plain.isNotBlank()) {
                        samples.add(plain.take(800))
                    }
                }
                if (samples.size >= 4) break
            }

            if (samples.isNotEmpty()) {
                sb.append("Key Narrative Excerpts across Book Arcs:\n")
                samples.forEachIndexed { i, s ->
                    sb.append("--- Milestone Segment ${i + 1} ---\n")
                    sb.append(s).append("\n\n")
                }
            } else if (currentChapterText.isNotBlank()) {
                sb.append("Current Reference Segment:\n").append(currentChapterText.take(1500)).append("\n")
            }

            val result = sb.toString().trim()
            if (result.length > 50) result else extractBookTocOverview(bookTitle, currentChapterText)
        } catch (e: Exception) {
            extractBookTocOverview(bookTitle, currentChapterText)
        }
    }

    /**
     * 抽取指定章节范围的内容 (startChapterIndex 到 endChapterIndex，1-based 序号)
     */
    suspend fun extractChapterRangeContent(
        repository: BookRepository?,
        bookId: String,
        bookTitle: String,
        startChapter: Int, // 1-based
        endChapter: Int,   // 1-based
        fallbackCurrentChapterText: String = ""
    ): String = withContext(Dispatchers.IO) {
        if (repository == null) {
            return@withContext extractBookTocOverview(bookTitle, fallbackCurrentChapterText)
        }
        try {
            val chapters = repository.parseSpine(bookId)
            val toc = repository.parseToc(bookId)
            val totalChapters = chapters.size.coerceAtLeast(toc.size)
            if (totalChapters == 0) {
                return@withContext extractBookTocOverview(bookTitle, fallbackCurrentChapterText)
            }

            val safeStart = startChapter.coerceIn(1, totalChapters)
            val safeEnd = endChapter.coerceIn(safeStart, totalChapters)

            // 方案 A：范围内全部章节正文按顺序送入，不做采样、不附目录大纲（避免模型借目录越界出题）
            // 总量超限时保留前面章节，并明确告知模型范围不完整
            val maxTotalChars = 60000
            val chapterBlocks = mutableListOf<Triple<Int, String, String>>() // (章节序号, 标题, 正文)
            for (i in (safeStart - 1) until safeEnd) {
                val href = when {
                    i < chapters.size -> chapters[i].href
                    i < toc.size -> toc[i].href
                    else -> null
                } ?: continue
                val rawTitle = if (i < toc.size) toc[i].label.trim() else ""
                val cleanHref = href.substringBefore('#')
                val html = repository.getChapterContent(bookId, cleanHref) ?: continue
                val plain = extractChapterText(html)
                if (plain.isNotBlank()) {
                    val displayHeading = if (rawTitle.isNotBlank()) "--- Section: $rawTitle ---" else "--- Section ${i + 1} ---"
                    chapterBlocks.add(Triple(i + 1, rawTitle, "$displayHeading\n$plain\n\n"))
                }
            }

            if (chapterBlocks.isEmpty()) {
                return@withContext extractBookTocOverview(bookTitle, fallbackCurrentChapterText)
            }

            val sb = StringBuilder()
            sb.append("=== CUSTOM CHAPTER RANGE EXCERPTS ===\n")
            sb.append("Selected Range: Item $safeStart to Item $safeEnd (Total ${chapterBlocks.size} Sections Included)\n")
            sb.append("Instruction: Read ALL provided sections carefully. Questions must test across different sections, not just the first one.\n\n")

            val total = chapterBlocks.sumOf { it.third.length }
            if (total <= maxTotalChars) {
                chapterBlocks.forEach { sb.append(it.third) }
            } else {
                var used = 0
                val kept = mutableListOf<Triple<Int, String, String>>()
                for (block in chapterBlocks) {
                    if (used + block.third.length > maxTotalChars) break
                    used += block.third.length
                    kept.add(block)
                }
                if (kept.isEmpty()) {
                    val first = chapterBlocks[0]
                    kept.add(Triple(first.first, first.second, first.third.take(maxTotalChars)))
                }
                val lastKept = kept.last().first
                sb.append("[NOTICE: Only Section ${kept.first().first} to Section $lastKept of the selected range is provided due to length limits. ")
                sb.append("Generate questions ONLY from the provided sections. Do NOT infer or mention any other section.]\n\n")
                kept.forEach { sb.append(it.third) }
            }

            val result = sb.toString().trim()
            if (result.length > 50) result else extractBookTocOverview(bookTitle, fallbackCurrentChapterText)
        } catch (e: Exception) {
            extractBookTocOverview(bookTitle, fallbackCurrentChapterText)
        }
    }

    suspend fun extractBookTocOverview(
        bookTitle: String,
        currentChapterText: String
    ): String = withContext(Dispatchers.Default) {
        val preview = currentChapterText.take(3000)
        """
        Book Title: $bookTitle
        Key Excerpt / Representative Content:
        $preview
        """.trimIndent()
    }

    /**
     * 异步提取指定章节的简要片段（开头或结尾约 150 字），供范围选择对话框实时对齐预览
     */
    suspend fun extractChapterPreviewSnippet(
        repository: BookRepository?,
        bookId: String,
        chapterIndex0Based: Int,
        takeEnd: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        if (repository == null) return@withContext ""
        try {
            val chapters = repository.parseSpine(bookId)
            val toc = repository.parseToc(bookId)
            val href = when {
                chapterIndex0Based in chapters.indices -> chapters[chapterIndex0Based].href
                chapterIndex0Based in toc.indices -> toc[chapterIndex0Based].href
                else -> null
            } ?: return@withContext ""
            val cleanHref = href.substringBefore('#')
            val html = repository.getChapterContent(bookId, cleanHref) ?: return@withContext ""
            val plain = extractChapterText(html).trim()
            if (plain.isBlank()) return@withContext ""
            if (takeEnd) {
                if (plain.length <= 160) plain else "..." + plain.takeLast(160).trim()
            } else {
                if (plain.length <= 160) plain else plain.take(160).trim() + "..."
            }
        } catch (_: Exception) {
            ""
        }
    }
}
