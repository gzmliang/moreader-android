package com.moyue.ai.model

data class QuizQuestion(
    val id: Int,
    val questionOriginal: String,
    val questionTranslation: String,
    val options: List<String>, // e.g. ["A. xxx", "B. yyy"]
    val correctAnswer: String, // e.g. "A"
    val analysisOriginal: String = "",
    val analysisTranslation: String = ""
)

data class AiQuizResult(
    val bookId: String,
    val chapterIndex: Int,
    val scope: String,
    val count: Int,
    val difficulty: String,
    val questions: List<QuizQuestion> = emptyList(),
    val generatedAt: Long = System.currentTimeMillis()
)

data class UserQuizAnswer(
    val questionId: Int,
    val selectedOption: String, // e.g. "A"
    val isCorrect: Boolean
)

data class QuizReportRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val bookId: String,
    val bookTitle: String,
    val chapterTitle: String,
    val scope: String,
    val difficulty: String,
    val score: Int,
    val totalCount: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val questions: List<QuizQuestion>,
    val userAnswers: Map<Int, String> // questionId -> selectedOption
)
