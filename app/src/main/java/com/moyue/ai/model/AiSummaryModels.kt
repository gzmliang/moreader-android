package com.moyue.ai.model

data class SummaryParagraph(
    val original: String = "",
    val translation: String = ""
)

data class AiSummaryResult(
    val bookId: String,
    val chapterIndex: Int,
    val scope: String, // "chapter" or "book"
    val ratio: Int,
    val level: String = "standard", // "simple", "standard", "advanced"
    val title: String = "",
    val paragraphs: List<SummaryParagraph> = emptyList(),
    val rawMarkdown: String = "",
    val generatedAt: Long = System.currentTimeMillis()
)
