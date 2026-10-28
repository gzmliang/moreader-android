package com.moyue.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moyue.ai.model.AiConfig
import com.moyue.ai.model.AiQuizResult
import com.moyue.ai.model.StudyInsightResult
import com.moyue.ai.service.AiPromptBuilder
import com.moyue.ai.service.LlmClient
import com.moyue.app.R
import kotlinx.coroutines.launch

private enum class WizardStep {
    SELECT_SCOPE,
    STUDIED_CUSTOMIZE
}

/**
 * 智能分页切片助手：
 * 保证每页内容稳定在 500~700 字符之间（标准阅读屏），
 * 绝不会出现把整章 13 万字直接当单页塞爆界面的情况。
 */
private object ReadingPaginator {
    private const val TARGET_PAGE_CHARS = 550

    fun paginate(paragraphs: List<String>, fullText: String): List<String> {
        val rawParas = if (paragraphs.isNotEmpty()) {
            paragraphs.filter { it.isNotBlank() }
        } else {
            fullText.split("\n\n", "\n").map { it.trim() }.filter { it.isNotBlank() }
        }

        if (rawParas.isEmpty()) {
            return fullText.chunked(TARGET_PAGE_CHARS).ifEmpty { listOf(fullText) }
        }

        val pages = mutableListOf<String>()
        val buf = StringBuilder()

        for (para in rawParas) {
            if (buf.length + para.length > TARGET_PAGE_CHARS && buf.isNotEmpty()) {
                pages.add(buf.toString().trim())
                buf.clear()
            }
            if (buf.isNotEmpty()) buf.append("\n\n")
            buf.append(para)
        }
        if (buf.isNotEmpty()) {
            pages.add(buf.toString().trim())
        }
        return if (pages.isEmpty()) listOf(fullText.take(TARGET_PAGE_CHARS)) else pages
    }
}

@Composable
fun InteractiveQuizDialog(
    bookId: String,
    bookTitle: String,
    chapterIndex: Int,
    chapterTitle: String,
    fullChapterText: String,
    paragraphs: List<String> = emptyList(),
    currentParagraphIndex: Int = 0,
    selectedText: String? = null,
    config: AiConfig,
    isEink: Boolean,
    initialDifficulty: String = "Basic",
    initialCount: Int = 5,
    initialInsight: StudyInsightResult? = null,
    avoidQuestions: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onQuizGenerated: (AiQuizResult) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by remember {
        mutableStateOf(if (initialInsight != null && initialInsight.keyPoints.isNotEmpty()) WizardStep.STUDIED_CUSTOMIZE else WizardStep.SELECT_SCOPE)
    }

    // ── 科学分页与页数步进器（梁老师指定核心功能）──
    val bookPages = remember(paragraphs, fullChapterText) {
        ReadingPaginator.paginate(paragraphs, fullChapterText)
    }

    val startPageIndex = remember(bookPages, currentParagraphIndex) {
        if (paragraphs.isNotEmpty() && bookPages.isNotEmpty()) {
            val ratio = currentParagraphIndex.toFloat() / maxOf(1, paragraphs.size)
            (ratio * bookPages.size).toInt().coerceIn(0, bookPages.size - 1)
        } else {
            0
        }
    }

    // 用户划选判断
    val hasSelection = !selectedText.isNullOrBlank()
    var useSelection by remember { mutableStateOf(hasSelection) }

    // 增加了几页：0 表示仅当前页（共 1 页），1 表示当前页 + 后续 1 页（共 2 页）...
    var addedPages by remember { mutableIntStateOf(0) }
    val maxAddPages = (bookPages.size - 1 - startPageIndex).coerceIn(0, 19)

    // 计算当前划定范围的文本
    val targetText by remember(useSelection, selectedText, addedPages, startPageIndex, bookPages, fullChapterText) {
        derivedStateOf {
            if (useSelection && !selectedText.isNullOrBlank()) {
                selectedText.trim()
            } else {
                val endIdx = (startPageIndex + addedPages).coerceAtMost(bookPages.size - 1)
                val slice = bookPages.subList(startPageIndex, endIdx + 1)
                if (slice.isNotEmpty()) slice.joinToString("\n\n") else fullChapterText.take(1500)
            }
        }
    }

    // 首尾对齐预览
    val previewStart by remember(targetText) {
        derivedStateOf {
            val t = targetText.trim()
            if (t.length <= 80) t else t.take(80).replace("\n", " ").trim() + "…"
        }
    }
    val previewEnd by remember(targetText) {
        derivedStateOf {
            val t = targetText.trim()
            if (t.length <= 80) t else "…" + t.takeLast(80).replace("\n", " ").trim()
        }
    }

    // ── 研读成果与定制参数 ──
    var insightResult by remember { mutableStateOf(initialInsight ?: StudyInsightResult()) }
    var selectedCount by remember { mutableIntStateOf(initialCount) }
    var selectedDifficulty by remember { mutableStateOf(initialDifficulty) }
    var userInstruction by remember { mutableStateOf("") }

    var isStudying by remember { mutableStateOf(false) }
    var isGeneratingQuiz by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // ── 第一阶段：让 AI 研读要点 ──
    fun startStudy() {
        if (targetText.isBlank()) return
        isStudying = true
        errorMessage = null
        scope.launch {
            val (sys, usr) = AiPromptBuilder.buildStudyMaterialPrompt(
                config = config,
                title = "$bookTitle - $chapterTitle",
                text = targetText
            )
            val res = LlmClient().chatCompletion(config, sys, usr, responseJson = true)
            isStudying = false
            res.fold(
                onSuccess = { json ->
                    val parsed = AiPromptBuilder.parseStudyMaterialResponse(json)
                    if (parsed.keyPoints.isNotEmpty()) {
                        insightResult = parsed
                        step = WizardStep.STUDIED_CUSTOMIZE
                    } else {
                        insightResult = StudyInsightResult(
                            summary = parsed.summary.ifBlank { "已通读当前指定篇幅" },
                            keyPoints = listOf("深入考查本段核心定义与逻辑", "考查段落中提及的关键事实与因果")
                        )
                        step = WizardStep.STUDIED_CUSTOMIZE
                    }
                },
                onFailure = { err ->
                    errorMessage = err.localizedMessage
                }
            )
        }
    }

    // ── 第二阶段：根据研读成果定制出题 ──
    fun startGenerateInteractiveQuiz() {
        if (targetText.isBlank()) return
        isGeneratingQuiz = true
        errorMessage = null
        scope.launch {
            val (sys, usr) = AiPromptBuilder.buildInteractiveQuizPrompt(
                config = config,
                title = "$bookTitle - $chapterTitle",
                text = targetText,
                keyPoints = insightResult.keyPoints,
                count = selectedCount,
                difficulty = selectedDifficulty,
                scope = "interactive",
                userInstruction = userInstruction.trim(),
                avoidQuestions = avoidQuestions
            )
            val res = LlmClient().chatCompletion(config, sys, usr, responseJson = true)
            isGeneratingQuiz = false
            res.fold(
                onSuccess = { json ->
                    val quiz = AiPromptBuilder.parseQuizResponse(
                        jsonString = json,
                        bookId = bookId,
                        chapterIndex = chapterIndex,
                        scope = "interactive",
                        count = selectedCount,
                        difficulty = selectedDifficulty
                    )
                    val enriched = quiz.copy(
                        interactiveSummary = insightResult.summary,
                        interactiveKeyPoints = insightResult.keyPoints,
                        userInstruction = userInstruction.trim()
                    )
                    onQuizGenerated(enriched)
                },
                onFailure = { err ->
                    errorMessage = err.localizedMessage
                }
            )
        }
    }

    Dialog(
        onDismissRequest = {
            if (!isStudying && !isGeneratingQuiz) onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.86f)
                .padding(vertical = 12.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            shape = RoundedCornerShape(24.dp),
            color = if (isEink) Color.White else MaterialTheme.colorScheme.surface,
            tonalElevation = if (isEink) 0.dp else 6.dp,
            border = if (isEink) androidx.compose.foundation.BorderStroke(1.5.dp, Color.Black) else null
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                // ── 顶部 Header ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Psychology,
                                    contentDescription = null,
                                    tint = if (isEink) Color.White else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.ai_quiz_interactive_title),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.ai_quiz_interactive_desc),
                                fontSize = 11.sp,
                                color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        enabled = !isStudying && !isGeneratingQuiz,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = if (isEink) Color.Black else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // 错误提示条
                if (errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                // ── 主体内容区（滚动）──
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (step) {
                        WizardStep.SELECT_SCOPE -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                // 划选与按页切换（若有划选）
                                if (hasSelection) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        FilterChip(
                                            selected = useSelection,
                                            onClick = { useSelection = true },
                                            label = { Text(stringResource(R.string.ai_quiz_scope_selected_text, selectedText?.length ?: 0), fontSize = 12.sp) }
                                        )
                                        FilterChip(
                                            selected = !useSelection,
                                            onClick = { useSelection = false },
                                            label = { Text(stringResource(R.string.ai_quiz_scope_page_current, targetText.length), fontSize = 12.sp) }
                                        )
                                    }
                                }

                                // ── 核心范围与页数步进器卡片（彻底解决挤压与页数反馈）──
                                if (!useSelection) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        border = if (isEink) androidx.compose.foundation.BorderStroke(1.dp, Color.Black) else null,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            // 当前页数与字数文字描述
                                            val scopeTitle = if (addedPages == 0) {
                                                stringResource(R.string.ai_quiz_scope_page_current, targetText.length)
                                            } else {
                                                stringResource(
                                                    R.string.ai_quiz_scope_page_expanded,
                                                    addedPages,
                                                    addedPages + 1,
                                                    targetText.length
                                                )
                                            }
                                            Text(
                                                text = scopeTitle,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary
                                            )

                                            // 极简步进器：[ ➖ ]  [ X Pages ]  [ ➕ ]（彻底杜绝文字折行重叠）
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                FilledTonalIconButton(
                                                    onClick = { if (addedPages > 0) addedPages-- },
                                                    enabled = addedPages > 0,
                                                    modifier = Modifier.size(40.dp),
                                                    shape = RoundedCornerShape(10.dp)
                                                ) {
                                                    Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.ai_quiz_scope_btn_minus_page), modifier = Modifier.size(18.dp))
                                                }

                                                Surface(
                                                    color = if (isEink) Color.White else MaterialTheme.colorScheme.surface,
                                                    shape = RoundedCornerShape(10.dp),
                                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isEink) Color.Black else MaterialTheme.colorScheme.outlineVariant),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(40.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Text(
                                                            text = if (addedPages == 0) stringResource(R.string.ai_quiz_page_count_single)
                                                            else stringResource(R.string.ai_quiz_page_count_format, addedPages + 1),
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                                                        )
                                                    }
                                                }

                                                Button(
                                                    onClick = { if (addedPages < maxAddPages) addedPages++ },
                                                    enabled = addedPages < maxAddPages,
                                                    modifier = Modifier
                                                        .size(40.dp),
                                                    shape = RoundedCornerShape(10.dp),
                                                    contentPadding = PaddingValues(0.dp)
                                                ) {
                                                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.ai_quiz_scope_btn_add_page), modifier = Modifier.size(18.dp))
                                                }

                                                if (addedPages > 0) {
                                                    TextButton(
                                                        onClick = { addedPages = 0 },
                                                        modifier = Modifier.height(40.dp),
                                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                                    ) {
                                                        Text(stringResource(R.string.ai_quiz_scope_btn_reset), fontSize = 11.sp)
                                                    }
                                                }
                                            }

                                            // 快捷页数跳选胶囊：1页 / 5页 / 10页 / 20页（一秒直达整篇故事）
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                listOf(1, 5, 10, 20).forEach { p ->
                                                    val targetAdd = (p - 1).coerceAtMost(maxAddPages)
                                                    FilterChip(
                                                        selected = (addedPages + 1 == p),
                                                        onClick = { addedPages = targetAdd },
                                                        label = {
                                                            Text(
                                                                text = if (p <= 1) stringResource(R.string.ai_quiz_page_count_single)
                                                                else stringResource(R.string.ai_quiz_page_count_format, p),
                                                                fontSize = 11.sp
                                                            )
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // ── 首尾对齐预览卡片（保留精髓，优化行间距）──
                                Surface(
                                    color = if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(14.dp),
                                    border = if (isEink) androidx.compose.foundation.BorderStroke(1.dp, Color.Black) else androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text(
                                            text = stringResource(R.string.ai_quiz_preview_header),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = stringResource(R.string.ai_quiz_preview_start, previewStart),
                                            fontSize = 12.sp,
                                            lineHeight = 18.sp,
                                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = stringResource(R.string.ai_quiz_preview_end, previewEnd),
                                            fontSize = 12.sp,
                                            lineHeight = 18.sp,
                                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }

                        WizardStep.STUDIED_CUSTOMIZE -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                // AI 研读要点展示卡片
                                Surface(
                                    color = if (isEink) Color.White else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(14.dp),
                                    border = if (isEink) androidx.compose.foundation.BorderStroke(1.dp, Color.Black) else null,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.Lightbulb,
                                                    contentDescription = null,
                                                    tint = if (isEink) Color.Black else MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = stringResource(R.string.ai_quiz_studied_badge),
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            TextButton(
                                                onClick = { startStudy() },
                                                enabled = !isStudying && !isGeneratingQuiz,
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(stringResource(R.string.ai_quiz_btn_re_study), fontSize = 11.sp)
                                            }
                                        }

                                        if (insightResult.summary.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = insightResult.summary,
                                                fontSize = 12.sp,
                                                lineHeight = 17.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))
                                        insightResult.keyPoints.forEachIndexed { idx, point ->
                                            Text(
                                                text = point,
                                                fontSize = 12.sp,
                                                lineHeight = 18.sp,
                                                color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.padding(vertical = 2.dp)
                                            )
                                        }
                                    }
                                }

                                // 题数控制（支持步进器 + 常用快捷胶囊 1题/3题/5题/6题/10题）
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = stringResource(R.string.ai_quiz_count_label),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        FilledTonalIconButton(
                                            onClick = { if (selectedCount > 1) selectedCount-- },
                                            enabled = selectedCount > 1,
                                            modifier = Modifier.size(36.dp),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(16.dp))
                                        }

                                        Surface(
                                            color = if (isEink) Color.White else MaterialTheme.colorScheme.surface,
                                            shape = RoundedCornerShape(8.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isEink) Color.Black else MaterialTheme.colorScheme.outlineVariant),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(36.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = stringResource(R.string.ai_quiz_count_format, selectedCount),
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }

                                        Button(
                                            onClick = { if (selectedCount < 30) selectedCount++ },
                                            enabled = selectedCount < 30,
                                            modifier = Modifier.size(36.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(1, 3, 5, 6, 10).forEach { cnt ->
                                            FilterChip(
                                                selected = (selectedCount == cnt),
                                                onClick = { selectedCount = cnt },
                                                label = { Text(stringResource(R.string.ai_quiz_count_format, cnt), fontSize = 11.sp) }
                                            )
                                        }
                                    }
                                }

                                // 难度控制
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = stringResource(R.string.ai_quiz_difficulty_label),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf(
                                            "Basic" to stringResource(R.string.ai_quiz_diff_basic),
                                            "Intermediate" to stringResource(R.string.ai_quiz_diff_intermediate),
                                            "Advanced" to stringResource(R.string.ai_quiz_diff_advanced)
                                        ).forEach { (key, label) ->
                                            FilterChip(
                                                selected = (selectedDifficulty == key),
                                                onClick = { selectedDifficulty = key },
                                                label = { Text(label, fontSize = 12.sp) }
                                            )
                                        }
                                    }
                                }

                                // 特别嘱咐输入框
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = stringResource(R.string.ai_quiz_custom_instruction_label),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                                    )
                                    OutlinedTextField(
                                        value = userInstruction,
                                        onValueChange = { userInstruction = it },
                                        placeholder = {
                                            Text(
                                                text = stringResource(R.string.ai_quiz_custom_instruction_hint),
                                                fontSize = 12.sp,
                                                color = Color.Gray
                                            )
                                        },
                                        maxLines = 3,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = if (isEink) Color.Black else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // ── 底部操作按钮 ──
                when (step) {
                    WizardStep.SELECT_SCOPE -> {
                        Button(
                            onClick = { startStudy() },
                            enabled = !isStudying,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            if (isStudying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.ai_quiz_studying_hint), fontSize = 12.sp, maxLines = 1)
                            } else {
                                Text(stringResource(R.string.ai_quiz_btn_start_study), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                    }

                    WizardStep.STUDIED_CUSTOMIZE -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { step = WizardStep.SELECT_SCOPE },
                                enabled = !isGeneratingQuiz,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.ai_quiz_btn_reselect_material),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Button(
                                onClick = { startGenerateInteractiveQuiz() },
                                enabled = !isGeneratingQuiz,
                                modifier = Modifier
                                    .weight(1.5f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                if (isGeneratingQuiz) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.ai_quiz_generating_hint), fontSize = 12.sp, maxLines = 1)
                                } else {
                                    Text(
                                        text = stringResource(R.string.ai_quiz_btn_generate_now),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
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
