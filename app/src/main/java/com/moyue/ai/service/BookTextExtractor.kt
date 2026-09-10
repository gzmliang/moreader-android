package com.moyue.ai.service

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

    suspend fun extractBookTocOverview(
        bookTitle: String,
        currentChapterText: String
    ): String = withContext(Dispatchers.Default) {
        // Creates a representative context for entire book summary
        val preview = currentChapterText.take(3000)
        """
        Book Title: $bookTitle
        Key Excerpt / Representative Chapter Content:
        $preview
        """.trimIndent()
    }
}
