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
