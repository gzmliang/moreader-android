package com.moyue.ai.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyue.ai.data.AiCacheRepository
import com.moyue.ai.model.AiConfig
import com.moyue.ai.model.AiQuizResult
import com.moyue.ai.model.QuizQuestion
import com.moyue.ai.model.QuizReportRecord
import com.moyue.ai.service.AiPromptBuilder
import com.moyue.ai.service.LlmClient
import com.moyue.app.R
import kotlinx.coroutines.launch

@Composable
fun QuizTabContent(
    bookId: String,
    bookTitle: String,
    chapterIndex: Int,
    chapterTitle: String,
    chapterText: String,
    repository: AiCacheRepository,
    config: AiConfig,
    isEink: Boolean,
    textSizeSp: Float,
    onQuizCompleted: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedScope by remember { mutableStateOf("chapter") } // "chapter" or "book"
    var questionCount by remember { mutableIntStateOf(5) }
    var difficulty by remember { mutableStateOf("Intermediate") } // "Basic", "Intermediate", "Advanced"
    var feedbackMode by remember { mutableStateOf("instant") } // "instant" or "submit"

    var quizResult by remember {
        mutableStateOf(repository.getQuiz(bookId, chapterIndex, selectedScope, questionCount, difficulty))
    }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // User answers: questionId -> selectedOption ("A", "B", etc.)
    val userAnswers = remember { mutableStateMapOf<Int, String>() }
    var isSubmitted by remember { mutableStateOf(false) }

    LaunchedEffect(bookId, chapterIndex, selectedScope, questionCount, difficulty) {
        quizResult = repository.getQuiz(bookId, chapterIndex, selectedScope, questionCount, difficulty)
        userAnswers.clear()
        isSubmitted = false
    }

    val warningUnanswered = stringResource(R.string.ai_quiz_unanswered_warning)

    fun submitAll() {
        val questions = quizResult?.questions ?: return
        if (userAnswers.size < questions.size) {
            Toast.makeText(context, warningUnanswered, Toast.LENGTH_SHORT).show()
            return
        }

        var correctCount = 0
        for (q in questions) {
            val ans = userAnswers[q.id]
            if (ans != null && (ans.equals(q.correctAnswer, ignoreCase = true) || q.correctAnswer.startsWith(ans, ignoreCase = true))) {
                correctCount++
            }
        }

        isSubmitted = true

        // Save report to history
        val report = QuizReportRecord(
            bookId = bookId,
            bookTitle = bookTitle,
            chapterTitle = if (selectedScope == "book") "Entire Book" else chapterTitle,
            scope = selectedScope,
            difficulty = difficulty,
            score = correctCount,
            totalCount = questions.size,
            questions = questions,
            userAnswers = userAnswers.toMap()
        )
        repository.addQuizReport(report)
        onQuizCompleted()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Controls Header
        Surface(
            color = if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                // Row 1: Scope & Count & Difficulty
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Scope
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = (selectedScope == "chapter"),
                            onClick = { selectedScope = "chapter" },
                            label = { Text(stringResource(R.string.ai_scope_chapter), fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = (selectedScope == "book"),
                            onClick = { selectedScope = "book" },
                            label = { Text(stringResource(R.string.ai_scope_book), fontSize = 11.sp) }
                        )
                    }

                    // Count chips (3, 5, 10)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(3, 5, 10).forEach { cnt ->
                            Text(
                                text = stringResource(R.string.ai_quiz_count_format, cnt),
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .background(
                                        if (questionCount == cnt) (if (isEink) Color.Black else MaterialTheme.colorScheme.primary)
                                        else (if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .then(if (isEink && questionCount != cnt) Modifier.border(1.dp, Color.Black, RoundedCornerShape(4.dp)) else Modifier)
                                    .clickable { questionCount = cnt }
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                                color = if (questionCount == cnt) Color.White else (if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Row 2: Difficulty & Mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Difficulty
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val diffs = listOf(
                            "Basic" to stringResource(R.string.ai_quiz_diff_basic),
                            "Intermediate" to stringResource(R.string.ai_quiz_diff_intermediate),
                            "Advanced" to stringResource(R.string.ai_quiz_diff_advanced)
                        )
                        diffs.forEach { (dKey, dLabel) ->
                            Text(
                                text = dLabel,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .background(
                                        if (difficulty == dKey) (if (isEink) Color.Black else MaterialTheme.colorScheme.primary)
                                        else (if (isEink) Color.White else MaterialTheme.colorScheme.surface),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .then(if (isEink && difficulty != dKey) Modifier.border(1.dp, Color.Black, RoundedCornerShape(4.dp)) else Modifier)
                                    .clickable { difficulty = dKey }
                                    .padding(horizontal = 5.dp, vertical = 3.dp),
                                color = if (difficulty == dKey) Color.White else (if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface)
                            )
                        }
                    }

                    // Mode Toggle (Instant vs Submit)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val modes = listOf(
                            "instant" to stringResource(R.string.ai_quiz_mode_instant),
                            "submit" to stringResource(R.string.ai_quiz_mode_submit)
                        )
                        modes.forEach { (mKey, mLabel) ->
                            Text(
                                text = mLabel,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .background(
                                        if (feedbackMode == mKey) (if (isEink) Color.Black else MaterialTheme.colorScheme.secondary)
                                        else (if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .then(if (isEink && feedbackMode != mKey) Modifier.border(1.dp, Color.Black, RoundedCornerShape(4.dp)) else Modifier)
                                    .clickable { feedbackMode = mKey }
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                                color = if (feedbackMode == mKey) Color.White else (if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface)
                            )
                        }
                    }
                }
            }
        }

        // Cache Status or regenerate
        if (quizResult != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.ai_cached_badge),
                    fontSize = 11.sp,
                    color = if (isEink) Color.Black else Color(0xFF10B981),
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = stringResource(R.string.ai_regenerate_btn),
                    fontSize = 11.sp,
                    color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        isLoading = true
                        errorMessage = null
                        scope.launch {
                            val (sys, usr) = AiPromptBuilder.buildQuizPrompt(
                                config = config,
                                title = if (selectedScope == "book") bookTitle else "$bookTitle - $chapterTitle",
                                text = chapterText,
                                count = questionCount,
                                difficulty = difficulty,
                                scope = selectedScope
                            )
                            val res = LlmClient().chatCompletion(config, sys, usr, responseJson = true)
                            isLoading = false
                            res.fold(
                                onSuccess = { json ->
                                    val parsed = AiPromptBuilder.parseQuizResponse(json, bookId, chapterIndex, selectedScope, questionCount, difficulty)
                                    repository.saveQuiz(parsed)
                                    quizResult = parsed
                                    userAnswers.clear()
                                    isSubmitted = false
                                },
                                onFailure = { errorMessage = it.localizedMessage }
                            )
                        }
                    }
                )
            }
        }

        // Content Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
        ) {
            when {
                isLoading -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.ai_generating),
                            fontSize = 14.sp,
                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                errorMessage != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.ai_error_format, errorMessage ?: ""),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp
                        )
                    }
                }
                quizResult != null -> {
                    val result = quizResult!!
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        items(result.questions) { question ->
                            QuizQuestionItem(
                                question = question,
                                selectedOption = userAnswers[question.id],
                                feedbackMode = feedbackMode,
                                isSubmitted = isSubmitted,
                                isEink = isEink,
                                textSizeSp = textSizeSp,
                                onSelectOption = { opt ->
                                    if (feedbackMode == "submit" && isSubmitted) return@QuizQuestionItem
                                    userAnswers[question.id] = opt
                                    if (feedbackMode == "instant") {
                                        // in instant mode, if all answered, record report
                                        if (userAnswers.size == result.questions.size) {
                                            submitAll()
                                        }
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                        }

                        // Submit button in "submit" mode
                        if (feedbackMode == "submit" && !isSubmitted) {
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { submitAll() },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = if (isEink) ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White)
                                    else ButtonDefaults.buttonColors()
                                ) {
                                    Text(stringResource(R.string.ai_quiz_submit_btn))
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }

                        // Score summary banner after submission
                        if (isSubmitted || (feedbackMode == "instant" && userAnswers.size == result.questions.size)) {
                            item {
                                val correctCount = result.questions.count { q ->
                                    val ans = userAnswers[q.id]
                                    ans != null && (ans.equals(q.correctAnswer, ignoreCase = true) || q.correctAnswer.startsWith(ans, ignoreCase = true))
                                }
                                val percent = if (result.questions.isNotEmpty()) (correctCount * 100) / result.questions.size else 0
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isEink) Color.White else MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp)
                                        .then(if (isEink) Modifier.border(2.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier)
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(14.dp)) {
                                        Text(
                                            text = stringResource(R.string.ai_quiz_score_summary, correctCount, result.questions.size, percent),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.ai_empty_hint),
                            fontSize = 14.sp,
                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                isLoading = true
                                errorMessage = null
                                scope.launch {
                                    val (sys, usr) = AiPromptBuilder.buildQuizPrompt(
                                        config = config,
                                        title = if (selectedScope == "book") bookTitle else "$bookTitle - $chapterTitle",
                                        text = chapterText,
                                        count = questionCount,
                                        difficulty = difficulty,
                                        scope = selectedScope
                                    )
                                    val res = LlmClient().chatCompletion(config, sys, usr, responseJson = true)
                                    isLoading = false
                                    res.fold(
                                        onSuccess = { json ->
                                            val parsed = AiPromptBuilder.parseQuizResponse(json, bookId, chapterIndex, selectedScope, questionCount, difficulty)
                                            repository.saveQuiz(parsed)
                                            quizResult = parsed
                                            userAnswers.clear()
                                            isSubmitted = false
                                        },
                                        onFailure = { errorMessage = it.localizedMessage }
                                    )
                                }
                            },
                            colors = if (isEink) ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White)
                            else ButtonDefaults.buttonColors()
                        ) {
                            Text(stringResource(R.string.ai_generate_btn))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuizQuestionItem(
    question: QuizQuestion,
    selectedOption: String?,
    feedbackMode: String,
    isSubmitted: Boolean,
    isEink: Boolean,
    textSizeSp: Float,
    onSelectOption: (String) -> Unit
) {
    val showFeedback = (feedbackMode == "instant" && selectedOption != null) || (feedbackMode == "submit" && isSubmitted)

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isEink) Color.White else MaterialTheme.colorScheme.surface,
        shadowElevation = if (isEink) 0.dp else 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Question Title (Orig + Trans)
            Text(
                text = "${question.id}. ${question.questionOriginal}",
                fontSize = (textSizeSp).sp,
                fontWeight = FontWeight.Bold,
                color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
            )
            if (question.questionTranslation.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = question.questionTranslation,
                    fontSize = (textSizeSp * 0.9f).sp,
                    color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Options
            question.options.forEach { opt ->
                val optPrefix = opt.take(1).uppercase()
                val isSelected = (selectedOption == optPrefix)
                val isCorrect = question.correctAnswer.startsWith(optPrefix, ignoreCase = true)

                val optBorderColor = when {
                    isEink && isSelected -> Color.Black
                    isEink -> Color.Gray
                    showFeedback && isCorrect -> Color(0xFF10B981)
                    showFeedback && isSelected && !isCorrect -> Color(0xFFEF4444)
                    isSelected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outlineVariant
                }

                val optBgColor = when {
                    isEink -> Color.White
                    showFeedback && isCorrect -> Color(0xFF10B981).copy(alpha = 0.1f)
                    showFeedback && isSelected && !isCorrect -> Color(0xFFEF4444).copy(alpha = 0.1f)
                    isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    else -> Color.Transparent
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = optBgColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .border(if (isSelected || (showFeedback && isCorrect)) 2.dp else 1.dp, optBorderColor, RoundedCornerShape(6.dp))
                        .clickable { onSelectOption(optPrefix) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Radio / Check indicator
                        val indicatorText = when {
                            showFeedback && isCorrect -> "✓"
                            showFeedback && isSelected && !isCorrect -> "✗"
                            isSelected -> if (isEink) "●" else "●"
                            else -> if (isEink) "○" else "○"
                        }
                        Text(
                            text = indicatorText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                isEink -> Color.Black
                                showFeedback && isCorrect -> Color(0xFF10B981)
                                showFeedback && isSelected && !isCorrect -> Color(0xFFEF4444)
                                else -> MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.width(20.dp)
                        )

                        Text(
                            text = opt,
                            fontSize = (textSizeSp * 0.95f).sp,
                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Explanation & Feedback
            if (showFeedback && (question.analysisOriginal.isNotBlank() || question.analysisTranslation.isNotBlank())) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(6.dp)) else Modifier)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = stringResource(R.string.ai_quiz_analysis_title),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary
                        )
                        if (question.analysisOriginal.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = question.analysisOriginal,
                                fontSize = (textSizeSp * 0.9f).sp,
                                color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (question.analysisTranslation.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = question.analysisTranslation,
                                fontSize = (textSizeSp * 0.85f).sp,
                                color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
