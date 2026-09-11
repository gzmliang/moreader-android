package com.moyue.ai.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

    fun startGenerateQuiz() {
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

    // Label helpers
    val diffShortLabel = when (difficulty) {
        "Basic" -> stringResource(R.string.ai_quiz_diff_basic_short)
        "Advanced" -> stringResource(R.string.ai_quiz_diff_advanced_short)
        else -> stringResource(R.string.ai_quiz_diff_intermediate_short)
    }
    val modeShortLabel = when (feedbackMode) {
        "submit" -> stringResource(R.string.ai_quiz_mode_submit_short)
        else -> stringResource(R.string.ai_quiz_mode_instant_short)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Compact single-line controls bar (height ~34dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Dropdown Capsules (Scrollable horizontally to prevent any wrapping or overflow)
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Scope Capsule: [ 当前章 ▾ ] / [ 全书 ▾ ]
                var scopeMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    Surface(
                        color = if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .clickable { scopeMenuExpanded = true }
                            .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(16.dp)) else Modifier)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        ) {
                            Text(
                                text = if (selectedScope == "book") stringResource(R.string.ai_scope_book_short) else stringResource(R.string.ai_scope_chapter_short),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = scopeMenuExpanded,
                        onDismissRequest = { scopeMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ai_scope_chapter), fontSize = 13.sp) },
                            onClick = {
                                selectedScope = "chapter"
                                scopeMenuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ai_scope_book), fontSize = 13.sp) },
                            onClick = {
                                selectedScope = "book"
                                scopeMenuExpanded = false
                            }
                        )
                    }
                }

                // Question Count Capsule: [ 5 题 ▾ ]
                var countMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    Surface(
                        color = if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .clickable { countMenuExpanded = true }
                            .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(16.dp)) else Modifier)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.ai_quiz_count_format, questionCount),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = countMenuExpanded,
                        onDismissRequest = { countMenuExpanded = false }
                    ) {
                        listOf(3, 5, 10).forEach { cnt ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.ai_quiz_count_format, cnt),
                                        fontSize = 13.sp,
                                        fontWeight = if (questionCount == cnt) FontWeight.Bold else FontWeight.Normal,
                                        color = if (questionCount == cnt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    questionCount = cnt
                                    countMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                // Difficulty Capsule: [ 进阶 ▾ ]
                var diffMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    Surface(
                        color = if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .clickable { diffMenuExpanded = true }
                            .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(16.dp)) else Modifier)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        ) {
                            Text(
                                text = diffShortLabel,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = diffMenuExpanded,
                        onDismissRequest = { diffMenuExpanded = false }
                    ) {
                        listOf(
                            "Basic" to stringResource(R.string.ai_quiz_diff_basic),
                            "Intermediate" to stringResource(R.string.ai_quiz_diff_intermediate),
                            "Advanced" to stringResource(R.string.ai_quiz_diff_advanced)
                        ).forEach { (dKey, dLabel) ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = dLabel,
                                        fontSize = 13.sp,
                                        fontWeight = if (difficulty == dKey) FontWeight.Bold else FontWeight.Normal,
                                        color = if (difficulty == dKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    difficulty = dKey
                                    diffMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                // Mode Capsule: [ 即时反馈 ▾ ]
                var modeMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    Surface(
                        color = if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .clickable { modeMenuExpanded = true }
                            .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(16.dp)) else Modifier)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        ) {
                            Text(
                                text = modeShortLabel,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = modeMenuExpanded,
                        onDismissRequest = { modeMenuExpanded = false }
                    ) {
                        listOf(
                            "instant" to stringResource(R.string.ai_quiz_mode_instant),
                            "submit" to stringResource(R.string.ai_quiz_mode_submit)
                        ).forEach { (mKey, mLabel) ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = mLabel,
                                        fontSize = 13.sp,
                                        fontWeight = if (feedbackMode == mKey) FontWeight.Bold else FontWeight.Normal,
                                        color = if (feedbackMode == mKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    feedbackMode = mKey
                                    modeMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Right side: ⚡已缓存 & 🔄重新生成
            if (quizResult != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "⚡" + stringResource(R.string.ai_cached_short),
                        fontSize = 11.sp,
                        color = if (isEink) Color.Black else Color(0xFF10B981),
                        fontWeight = FontWeight.SemiBold
                    )

                    IconButton(
                        onClick = { startGenerateQuiz() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.ai_regenerate_btn),
                            tint = if (isEink) Color.Black else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
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
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { startGenerateQuiz() },
                            colors = if (isEink) ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White)
                            else ButtonDefaults.buttonColors()
                        ) {
                            Text(stringResource(R.string.ai_regenerate_btn))
                        }
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
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isEink) Color.White else MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp)
                                        .then(if (isEink) Modifier.border(2.dp, Color.Black, RoundedCornerShape(12.dp)) else Modifier)
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
                    // Elegant Empty State Card
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            tonalElevation = if (isEink) 0.dp else 2.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 420.dp)
                                .then(
                                    if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(16.dp))
                                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Icon Circle
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .background(
                                            if (isEink) Color.Black else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                            RoundedCornerShape(32.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Quiz,
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                        tint = if (isEink) Color.White else MaterialTheme.colorScheme.primary
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Title
                                Text(
                                    text = stringResource(R.string.ai_quiz_card_title),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                // Description
                                Text(
                                    text = stringResource(R.string.ai_quiz_card_desc),
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    textAlign = TextAlign.Center,
                                    color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(20.dp))

                                // Configuration summary badges (2x2 layout to prevent horizontal compression)
                                val scopeText = if (selectedScope == "book") stringResource(R.string.ai_scope_book_short) else stringResource(R.string.ai_scope_chapter_short)
                                val countText = stringResource(R.string.ai_quiz_count_format, questionCount)
                                val row1 = listOf(scopeText, countText)
                                val row2 = listOf(diffShortLabel, modeShortLabel)

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(row1, row2).forEach { badgeRow ->
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            badgeRow.forEach { badge ->
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = if (isEink) Color.White else MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                                    modifier = Modifier.then(
                                                        if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp))
                                                        else Modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                                    )
                                                ) {
                                                    Text(
                                                        text = badge,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(24.dp))

                                // Big Generate Button
                                Button(
                                    onClick = { startGenerateQuiz() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = if (isEink) ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White)
                                    else ButtonDefaults.buttonColors()
                                ) {
                                    Text(
                                        text = stringResource(R.string.ai_quiz_generate_btn),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
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
        shape = RoundedCornerShape(12.dp),
        color = if (isEink) Color.White else MaterialTheme.colorScheme.surface,
        shadowElevation = if (isEink) 0.dp else 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(12.dp)) else Modifier)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Question Title (Orig + Trans)
            Text(
                text = "${question.id}. ${question.questionOriginal}",
                fontSize = (textSizeSp).sp,
                fontWeight = FontWeight.Bold,
                color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
            )
            if (question.questionTranslation.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = question.questionTranslation,
                    fontSize = (textSizeSp * 0.9f).sp,
                    color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

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
                    shape = RoundedCornerShape(8.dp),
                    color = optBgColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .border(if (isSelected || (showFeedback && isCorrect)) 2.dp else 1.dp, optBorderColor, RoundedCornerShape(8.dp))
                        .clickable { onSelectOption(optPrefix) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
                            modifier = Modifier.width(22.dp)
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
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.ai_quiz_analysis_title),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary
                        )
                        if (question.analysisOriginal.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
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
