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
                toc.mapIndexed { idx, entry -> "${idx + 1}. ${entry.label.trim()}" }
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
            val chapterBlocks = mutableListOf<Pair<Int, String>>() // (章节号, 正文块)
            for (i in (safeStart - 1) until safeEnd) {
                val href = when {
                    i < chapters.size -> chapters[i].href
                    i < toc.size -> toc[i].href
                    else -> null
                } ?: continue
                val title = if (i < toc.size) toc[i].label.trim() else "Chapter ${i + 1}"
                val cleanHref = href.substringBefore('#')
                val html = repository.getChapterContent(bookId, cleanHref) ?: continue
                val plain = extractChapterText(html)
                if (plain.isNotBlank()) {
                    chapterBlocks.add(Pair(i + 1, "--- Chapter ${i + 1}: $title ---\n$plain\n\n"))
                }
            }

            if (chapterBlocks.isEmpty()) {
                return@withContext extractBookTocOverview(bookTitle, fallbackCurrentChapterText)
            }

            val sb = StringBuilder()
            val total = chapterBlocks.sumOf { it.second.length }
            if (total <= maxTotalChars) {
                chapterBlocks.forEach { sb.append(it.second) }
            } else {
                var used = 0
                val kept = mutableListOf<Pair<Int, String>>()
                for (block in chapterBlocks) {
                    if (used + block.second.length > maxTotalChars) break
                    used += block.second.length
                    kept.add(block)
                }
                if (kept.isEmpty()) {
                    kept.add(Pair(chapterBlocks[0].first, chapterBlocks[0].second.take(maxTotalChars)))
                }
                val lastKept = kept.last().first
                sb.append("[NOTICE: Only Chapter ${kept.first().first} to Chapter $lastKept of the selected range is provided due to length limits. ")
                sb.append("Generate questions ONLY from the provided chapters. Do NOT infer or mention any other chapter.]\n\n")
                kept.forEach { sb.append(it.second) }
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
}
