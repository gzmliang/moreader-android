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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyue.ai.data.AiCacheRepository
import com.moyue.ai.model.AiConfig
import com.moyue.ai.model.AiQuizResult
import com.moyue.ai.model.QuizQuestion
import com.moyue.ai.model.QuizReportRecord
import com.moyue.ai.model.QuizRules
import com.moyue.ai.service.AiPromptBuilder
import com.moyue.ai.service.BookTextExtractor
import com.moyue.ai.service.LlmClient
import com.moyue.app.R
import com.moyue.app.data.BookRepository
import com.moyue.app.tts.SegmentTtsPlayer
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
    displayMode: String = "bilingual",
    bookRepository: BookRepository? = null,
    edgeEndpoint: String = "",
    edgeVoice: String = "zh-CN-XiaoxiaoNeural",
    onDisplayModeChange: (String) -> Unit = {},
    onQuizCompleted: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedScope by remember { mutableStateOf(repository.getLastQuizScope(bookId)) } // "chapter" or "book"
    var questionCount by remember { mutableIntStateOf(repository.getLastQuizCount(bookId)) }
    var difficulty by remember { mutableStateOf(repository.getLastQuizDifficulty(bookId)) } // "Basic", "Intermediate", "Advanced"
    var feedbackMode by remember { mutableStateOf(QuizRules.normalizeMode(repository.getQuizFeedbackMode())) } // "submit"(默认) or "instant"

    var quizResult by remember {
        mutableStateOf(repository.getQuiz(bookId, chapterIndex, selectedScope, questionCount, difficulty))
    }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // User answers: questionId -> selectedOption ("A", "B", etc.)
    val userAnswers = remember {
        mutableStateMapOf<Int, String>().apply {
            putAll(repository.getQuizDraft(bookId, chapterIndex, selectedScope, questionCount, difficulty))
        }
    }
    var isSubmitted by remember { mutableStateOf(false) }

    // ── 题目朗读（独立播放器：不占用、不修改阅读器正在用的音色）──
    val ttsPlayer = remember(edgeEndpoint, edgeVoice) { SegmentTtsPlayer(edgeEndpoint, edgeVoice) }
    var speakingKey by remember { mutableStateOf<String?>(null) }
    val ttsUnavailable = stringResource(R.string.ai_tts_unavailable)
    DisposableEffect(Unit) {
        onDispose { ttsPlayer.destroy() }
    }

    fun speak(key: String, segments: List<String>) {
        if (speakingKey == key) {          // 再点一次同一个喇叭 = 停止
            ttsPlayer.stop()
            speakingKey = null
            return
        }
        speakingKey = key
        ttsPlayer.speak(
            texts = segments,
            onStart = { speakingKey = key },
            onDone = { if (speakingKey == key) speakingKey = null },
            onError = {
                if (speakingKey == key) speakingKey = null
                Toast.makeText(context, ttsUnavailable, Toast.LENGTH_SHORT).show()
            }
        )
    }

    fun stopSpeaking() {
        ttsPlayer.stop()
        speakingKey = null
    }

    LaunchedEffect(bookId, chapterIndex, selectedScope, questionCount, difficulty) {
        quizResult = repository.getQuiz(bookId, chapterIndex, selectedScope, questionCount, difficulty)
        userAnswers.clear()
        val draft = repository.getQuizDraft(bookId, chapterIndex, selectedScope, questionCount, difficulty)
        if (draft.isNotEmpty()) {
            userAnswers.putAll(draft)
        }
        isSubmitted = false
        stopSpeaking()
    }

    val warningUnanswered = stringResource(R.string.ai_quiz_unanswered_warning)
    val lockedHint = stringResource(R.string.ai_quiz_locked_hint)

    fun submitAll() {
        if (isSubmitted) return              // ★ 提交后不再重复记分（旧版即时模式每点一下记一条）
        val questions = quizResult?.questions ?: return
        if (!QuizRules.canSubmit(userAnswers.size, questions.size)) {
            Toast.makeText(context, warningUnanswered, Toast.LENGTH_SHORT).show()
            return
        }

        val correctCount = QuizRules.score(questions, userAnswers.toMap())

        stopSpeaking()
        isSubmitted = true
        repository.clearQuizDraft(bookId, chapterIndex, selectedScope, questionCount, difficulty)

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
        userAnswers.clear()
        isSubmitted = false
        repository.clearQuizDraft(bookId, chapterIndex, selectedScope, questionCount, difficulty)
        scope.launch {
            val textToAnalyze = if (selectedScope == "book") {
                BookTextExtractor.extractBookOverview(bookRepository, bookId, bookTitle, chapterText)
            } else {
                chapterText
            }
            val promptTitle = if (selectedScope == "book") bookTitle else "$bookTitle - $chapterTitle"
            val (sys, usr) = AiPromptBuilder.buildQuizPrompt(
                config = config,
                title = promptTitle,
                text = textToAnalyze,
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
    val modeShortLabel = when (QuizRules.normalizeMode(feedbackMode)) {
        QuizRules.MODE_SUBMIT -> stringResource(R.string.ai_quiz_mode_submit_short)
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
                                repository.setLastQuizScope(bookId, "chapter")
                                scopeMenuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ai_scope_book), fontSize = 13.sp) },
                            onClick = {
                                selectedScope = "book"
                                repository.setLastQuizScope(bookId, "book")
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
                                    repository.setLastQuizCount(bookId, cnt)
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
                                    repository.setLastQuizDifficulty(bookId, dKey)
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
                            QuizRules.MODE_INSTANT to stringResource(R.string.ai_quiz_mode_instant),
                            QuizRules.MODE_SUBMIT to stringResource(R.string.ai_quiz_mode_submit)
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
                                    repository.setQuizFeedbackMode(mKey)
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
                        contentPadding = PaddingValues(top = 10.dp, bottom = 48.dp)
                    ) {
                        items(result.questions) { question ->
                            QuizQuestionItem(
                                question = question,
                                selectedOption = userAnswers[question.id],
                                feedbackMode = feedbackMode,
                                isSubmitted = isSubmitted,
                                isEink = isEink,
                                textSizeSp = textSizeSp,
                                displayMode = displayMode,
                                speakingKey = speakingKey,
                                onSpeak = { key, segments -> speak(key, segments) },
                                onSelectOption = { opt ->
                                    // 已提交 → 一律锁定；即时反馈模式 → 第一击即锁定（错了也不给改），
                                    // 但仍会当场亮出正确答案与解析。
                                    val alreadyAnswered = userAnswers[question.id] != null
                                    when {
                                        !QuizRules.canSelectOption(feedbackMode, isSubmitted, alreadyAnswered) ->
                                            if (!isSubmitted) Toast.makeText(context, lockedHint, Toast.LENGTH_SHORT).show()
                                        else -> {
                                            userAnswers[question.id] = opt
                                            repository.saveQuizDraft(bookId, chapterIndex, selectedScope, questionCount, difficulty, userAnswers.toMap())
                                        }
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                        }

                        // Submit button：完卷提交模式随时可点（未答完会提示）；
                        // 即时反馈模式做完最后一题才出现（答完再整体提交、记一次成绩）。
                        if (QuizRules.shouldShowSubmitButton(feedbackMode, isSubmitted, userAnswers.size, result.questions.size)) {
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = { submitAll() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = if (isEink) ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White)
                                    else ButtonDefaults.buttonColors()
                                ) {
                                    Text(
                                        text = stringResource(R.string.ai_quiz_submit_btn),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(modifier = Modifier.height(36.dp))
                            }
                        }

                        // Score summary banner after submission + 重做入口
                        if (isSubmitted) {
                            item {
                                val correctCount = QuizRules.score(result.questions, userAnswers.toMap())
                                val percent = QuizRules.scorePercent(correctCount, result.questions.size)
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

                                Spacer(modifier = Modifier.height(10.dp))

                                // 重做本卷：清空答案重新做同一套题（不重新生成）
                                OutlinedButton(
                                    onClick = {
                                        stopSpeaking()
                                        userAnswers.clear()
                                        isSubmitted = false
                                        repository.clearQuizDraft(bookId, chapterIndex, selectedScope, questionCount, difficulty)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = if (isEink) ButtonDefaults.outlinedButtonColors(contentColor = Color.Black)
                                    else ButtonDefaults.outlinedButtonColors()
                                ) {
                                    Text(stringResource(R.string.ai_quiz_redo_btn))
                                }
                                Spacer(modifier = Modifier.height(36.dp))
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
    displayMode: String,
    speakingKey: String?,
    onSpeak: (String, List<String>) -> Unit,
    onSelectOption: (String) -> Unit
) {
    val showFeedback = (QuizRules.normalizeMode(feedbackMode) == QuizRules.MODE_INSTANT && selectedOption != null) ||
        (QuizRules.normalizeMode(feedbackMode) == QuizRules.MODE_SUBMIT && isSubmitted)

    val showOrig = displayMode == "bilingual" || displayMode == "orig" || question.questionTranslation.isBlank()
    val showTrans = (displayMode == "bilingual" || displayMode == "target") && question.questionTranslation.isNotBlank()

    // 朗读内容严格跟随屏幕上显示的内容（屏幕上没显示的就不念）
    val questionSegments = buildList {
        if (showOrig && question.questionOriginal.isNotBlank()) add(question.questionOriginal)
        if (showTrans && question.questionTranslation.isNotBlank()) add(question.questionTranslation)
    }
    val questionKey = "q${question.id}"
    val questionReadLabel = stringResource(R.string.ai_tts_read_question)
    val optionReadLabel = stringResource(R.string.ai_tts_read_option)
    val analysisReadLabel = stringResource(R.string.ai_tts_read_analysis)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isEink) Color.White else MaterialTheme.colorScheme.surface,
        shadowElevation = if (isEink) 0.dp else 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(12.dp)) else Modifier)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Question Title (Orig + Trans based on displayMode) + 题目朗读
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    if (showOrig) {
                        Text(
                            text = "${question.id}. ${question.questionOriginal}",
                            fontSize = (textSizeSp).sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (showTrans) {
                        if (showOrig) Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (!showOrig) "${question.id}. ${question.questionTranslation}" else question.questionTranslation,
                            fontSize = (if (!showOrig) textSizeSp else textSizeSp * 0.9f).sp,
                            fontWeight = if (!showOrig) FontWeight.Bold else FontWeight.Normal,
                            color = if (isEink) (if (!showOrig) Color.Black else Color.DarkGray) else (if (!showOrig) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                }
                QuizSpeakerButton(
                    isPlaying = speakingKey == questionKey,
                    isEink = isEink,
                    contentDescription = questionReadLabel,
                    onClick = { onSpeak(questionKey, questionSegments) }
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
                            modifier = Modifier.weight(1f),
                            fontSize = (textSizeSp * 0.95f).sp,
                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                        )

                        // 该选项单独朗读（内层按钮自己吃掉点击，不会误选答案）
                        QuizSpeakerButton(
                            isPlaying = speakingKey == "${questionKey}_$optPrefix",
                            isEink = isEink,
                            contentDescription = optionReadLabel,
                            onClick = {
                                onSpeak(
                                    "${questionKey}_$optPrefix",
                                    listOf(opt.replace(Regex("^\\s*[A-Za-z][.、．:：)]\\s*"), ""))
                                )
                            }
                        )
                    }
                }
            }

            // Explanation & Feedback
            if (showFeedback && (question.analysisOriginal.isNotBlank() || question.analysisTranslation.isNotBlank())) {
                val showAnalysisOrig = displayMode == "bilingual" || displayMode == "orig" || question.analysisTranslation.isBlank()
                val showAnalysisTrans = (displayMode == "bilingual" || displayMode == "target") && question.analysisTranslation.isNotBlank()
                val analysisSegments = buildList {
                    if (showAnalysisOrig && question.analysisOriginal.isNotBlank()) add(question.analysisOriginal)
                    if (showAnalysisTrans && question.analysisTranslation.isNotBlank()) add(question.analysisTranslation)
                }
                val analysisKey = "a${question.id}"

                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.ai_quiz_analysis_title),
                                modifier = Modifier.weight(1f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary
                            )
                            QuizSpeakerButton(
                                isPlaying = speakingKey == analysisKey,
                                isEink = isEink,
                                contentDescription = analysisReadLabel,
                                onClick = { onSpeak(analysisKey, analysisSegments) }
                            )
                        }
                        if (showAnalysisOrig && question.analysisOriginal.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = question.analysisOriginal,
                                fontSize = (textSizeSp * 0.9f).sp,
                                color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (showAnalysisTrans && question.analysisTranslation.isNotBlank()) {
                            Spacer(modifier = Modifier.height(if (showAnalysisOrig) 2.dp else 4.dp))
                            Text(
                                text = question.analysisTranslation,
                                fontSize = (if (!showAnalysisOrig) textSizeSp * 0.9f else textSizeSp * 0.85f).sp,
                                color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 小喇叭按钮：正常=朗读，正在朗读时变成停止，再点一下即停。 */
@Composable
private fun QuizSpeakerButton(
    isPlaying: Boolean,
    isEink: Boolean,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.VolumeUp,
            contentDescription = contentDescription,
            tint = when {
                isEink -> Color.Black
                isPlaying -> Color(0xFFEF4444)
                else -> MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.size(17.dp)
        )
    }
}
