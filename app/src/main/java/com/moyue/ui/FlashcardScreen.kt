package com.moyue.app.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.ExperimentalMaterial3Api
import com.moyue.app.R
import com.moyue.app.data.BookRepository
import com.moyue.app.data.FlashcardDataStore
import com.moyue.app.data.FlashcardDataStore.Flashcard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashcardScreen(
    repository: BookRepository,
    onBack: () -> Unit,
    viewModel: FlashcardViewModel = viewModel(
        factory = FlashcardViewModelFactory(
            FlashcardDataStore(LocalContext.current),
            repository
        )
    )
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Refresh when screen becomes visible
    LaunchedEffect(Unit) {
        viewModel.refreshAll()
    }

    if (uiState.isReviewMode) {
        FlashcardReviewScreen(
            uiState = uiState,
            viewModel = viewModel,
            context = context,
            onExit = { viewModel.exitReview() }
        )
        return
    }

    // Dialog outside of LazyColumn
    var showResetConfirm by remember { mutableStateOf(false) }
    var showNewPlanDialog by remember { mutableStateOf(false) }
    var showDeletePlanDialog by remember { mutableStateOf(false) }
    var showTtsLogDialog by remember { mutableStateOf(false) }
    var ttsLogContent by remember { mutableStateOf<String?>(null) }
    var planNameInput by remember { mutableStateOf("") }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("重置复习进度") },
            text = { Text("所有闪卡将回到新卡状态，可以重新复习。确定吗？") },
            confirmButton = {
                TextButton(onClick = { showResetConfirm = false; viewModel.resetAllFlashcards() }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showNewPlanDialog) {
        AlertDialog(
            onDismissRequest = { showNewPlanDialog = false },
            title = { Text("新建复习计划") },
            text = {
                OutlinedTextField(
                    value = planNameInput,
                    onValueChange = { planNameInput = it },
                    placeholder = { Text("例如：A的计划") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (planNameInput.isNotBlank()) {
                        viewModel.createPlan(planNameInput.trim())
                        showNewPlanDialog = false
                        planNameInput = ""
                    }
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showNewPlanDialog = false; planNameInput = "" }) { Text("取消") }
            }
        )
    }

    if (showDeletePlanDialog) {
        AlertDialog(
            onDismissRequest = { showDeletePlanDialog = false },
            title = { Text("删除计划") },
            text = { Text("确定要删除「${uiState.currentPlan}」吗？该计划下所有闪卡都会被删除。") },
            confirmButton = {
                TextButton(onClick = { viewModel.deletePlan(uiState.currentPlan); showDeletePlanDialog = false }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePlanDialog = false }) { Text("取消") }
            }
        )
    }

    // TTS log dialog
    if (showTtsLogDialog) {
        AlertDialog(
            onDismissRequest = { showTtsLogDialog = false },
            title = { Text("🔊 TTS发音日志") },
            text = {
                val scrollState = androidx.compose.foundation.rememberScrollState()
                Column(modifier = Modifier.verticalScroll(scrollState).fillMaxWidth()) {
                    if (ttsLogContent != null) {
                        Text(
                            text = ttsLogContent!!,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    } else {
                        Text("暂无TTS日志\n\n请先在闪卡列表中点击🔊按钮测试发音", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTtsLogDialog = false }) { Text("关闭") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.flashcard_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    // TTS log button
                    IconButton(onClick = {
                        ttsLogContent = viewModel.getTtsLog(context)
                        showTtsLogDialog = true
                    }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "查看TTS日志")
                    }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Plan selector row
            PlanSelector(
                plans = uiState.plans,
                currentPlan = uiState.currentPlan,
                onSelectPlan = { viewModel.switchPlan(it) },
                onCreatePlan = { planNameInput = ""; showNewPlanDialog = true },
                onDeletePlan = { showDeletePlanDialog = true }
            )

            // Overview FIXED at top — not inside scrollable area
            if (uiState.allFlashcards.isNotEmpty() || uiState.dueCount > 0) {
                FlashcardOverview(
                    total = uiState.allFlashcards.size,
                    dueCount = uiState.dueCount,
                    onStartReview = { viewModel.startReview() },
                    onRequestResetAll = { showResetConfirm = true },
                    enabled = uiState.dueCount > 0
                )
            }

            if (uiState.allFlashcards.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.flashcard_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // weight(1f) fills remaining space below overview
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    items(uiState.allFlashcards, key = { it.id }) { card ->
                        FlashcardListItem(
                            flashcard = card,
                            onSpeak = { viewModel.speakWord(card.word, context) },
                            onRemove = { viewModel.removeFlashcard(card.id) },
                            onReset = { viewModel.resetFlashcard(card.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FlashcardOverview(
    total: Int,
    dueCount: Int,
    onStartReview: () -> Unit,
    onRequestResetAll: () -> Unit,
    enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.flashcard_review_overview), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem(stringResource(R.string.flashcard_total), total.toString(), MaterialTheme.colorScheme.onSurface)
                StatItem(stringResource(R.string.flashcard_due), dueCount.toString(), MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onStartReview, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.flashcard_start_review))
            }
            if (total > 0) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRequestResetAll, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("重置全部进度")
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, count: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Plan selector — horizontal scrollable tabs with + and ✕ buttons */
@Composable
private fun PlanSelector(
    plans: List<String>,
    currentPlan: String,
    onSelectPlan: (String) -> Unit,
    onCreatePlan: () -> Unit,
    onDeletePlan: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        plans.forEach { plan ->
            val isSelected = plan == currentPlan
            FilterChip(
                onClick = { onSelectPlan(plan) },
                label = {
                    Text(
                        plan,
                        style = if (isSelected) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                selected = isSelected,
                trailingIcon = if (isSelected && plan != "默认") {
                    @Composable {
                        IconButton(onClick = onDeletePlan, modifier = Modifier.size(18.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "删除计划", modifier = Modifier.size(14.dp))
                        }
                    }
                } else null,
                modifier = Modifier.height(32.dp)
            )
        }
        FilterChip(
            onClick = onCreatePlan,
            label = { Text("+ 新建", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            selected = false,
            modifier = Modifier.height(32.dp)
        )
    }
}

@Composable
private fun FlashcardListItem(
    flashcard: Flashcard,
    onSpeak: () -> Unit,
    onRemove: () -> Unit,
    onReset: () -> Unit
) {
    val dueLabel = if (flashcard.dueDate <= System.currentTimeMillis()) stringResource(R.string.flashcard_due_now) else stringResource(R.string.flashcard_scheduled)

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = flashcard.word, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(text = dueLabel, style = MaterialTheme.typography.labelSmall, color = if (flashcard.dueDate <= System.currentTimeMillis()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
                IconButton(onClick = onSpeak) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "发音", modifier = Modifier.size(20.dp))
                }
                if (flashcard.repetition > 0) {
                    IconButton(onClick = onReset) {
                        Icon(Icons.Default.Replay, contentDescription = stringResource(R.string.flashcard_reset_card), modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.flashcard_remove_card), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                }
            }
            val posLine = listOfNotNull(flashcard.pronunciation, flashcard.partOfSpeech?.let { "[$it]" }).joinToString("  ")
            if (posLine.isNotEmpty()) {
                Text(posLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            flashcard.chineseDef?.split("\n")?.firstOrNull()?.replace(Regex("^\\d+\\.\\s*"), "")?.let { def ->
                Text(text = def, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

// ========================
// Review Mode - Simple card-by-card, no pager
// ========================

@Composable
private fun FlashcardReviewScreen(
    uiState: FlashcardUiState,
    viewModel: FlashcardViewModel,
    context: Context,
    onExit: () -> Unit
) {
    if (uiState.reviewComplete) {
        ReviewCompleteScreen(
            sessionReviewed = uiState.sessionReviewed,
            sessionRemembered = uiState.sessionRemembered,
            onExit = onExit
        )
        return
    }

    if (uiState.dueFlashcards.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.flashcard_no_due_cards), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onExit) { Text(stringResource(R.string.back)) }
            }
        }
        return
    }

    // Safe index clamping
    val currentIndex = uiState.currentCardIndex.coerceAtMost(uiState.dueFlashcards.size - 1)
    val currentCard = uiState.dueFlashcards[currentIndex]
    var isFlipped by remember { mutableStateOf(false) }
    val isLoadingDefinition = uiState.fetchingWord.equals(currentCard.word, ignoreCase = true)

    // Auto-fetch definition when flipped and card has no definitions
    LaunchedEffect(isFlipped, currentCard.id) {
        if (isFlipped && (currentCard.chineseDef.isNullOrBlank() && currentCard.englishDef.isNullOrBlank())
            && !uiState.fetchingWord.equals(currentCard.word, ignoreCase = true)) {
            viewModel.fetchDefinition(currentCard.word, context)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).padding(top = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onExit) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = MaterialTheme.colorScheme.onBackground)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("计划：${uiState.currentPlan}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text("${currentIndex + 1} / ${uiState.dueFlashcards.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

        // Card content - tap to flip
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 80.dp)
                .clickable { isFlipped = !isFlipped },
            contentAlignment = Alignment.Center
        ) {
            if (!isFlipped) {
                // Front: word + pronunciation
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = currentCard.word,
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        currentCard.pronunciation?.let { pron ->
                            Text(pron, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                        }
                        Spacer(Modifier.height(24.dp))
                        IconButton(onClick = { viewModel.speakWord(currentCard.word, context) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "发音", modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("点击翻转", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                }
            } else {
                // Back: bilingual definitions
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                        // Word header
                        Text(
                            text = currentCard.word,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth()
                        )
                        currentCard.pronunciation?.let { pron ->
                            Text(pron, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                        currentCard.partOfSpeech?.let { pos ->
                            Text("[${pos}]", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                        
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))

                        // If no definitions and loading — show spinner
                        if (isLoadingDefinition && currentCard.chineseDef.isNullOrBlank() && currentCard.englishDef.isNullOrBlank()) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                                Spacer(Modifier.height(12.dp))
                                Text("正在获取释义…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            // Chinese definition (always shown if available)
                            if (!currentCard.chineseDef.isNullOrBlank()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🇨🇳 ", fontSize = 16.sp)
                                    Text("中文释义", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF2196F3))
                                }
                                Text(currentCard.chineseDef, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
                                Spacer(Modifier.height(12.dp))
                            }

                            // English definition / translation (always shown if available)
                            if (!currentCard.englishDef.isNullOrBlank()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🇬🇧 ", fontSize = 16.sp)
                                    Text("English Definition", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                                }
                                Text(currentCard.englishDef, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
                                Spacer(Modifier.height(12.dp))
                            }

                            // Example sentence
                            if (!currentCard.exampleText.isNullOrBlank()) {
                                HorizontalDivider()
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("📖 ", fontSize = 16.sp)
                                    Text("例句 Example", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFFFF9800))
                                }
                                Text(currentCard.exampleText, style = MaterialTheme.typography.bodyMedium, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
                                currentCard.exampleTranslation?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
                                }
                                Spacer(Modifier.height(8.dp))
                            }

                            // If still no definitions after loading — show tip
                            if (currentCard.chineseDef.isNullOrBlank() && currentCard.englishDef.isNullOrBlank() && !isLoadingDefinition) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("⚠️", fontSize = 24.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Text("暂无释义", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("请检查 AI 词典 API 配置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                }
                            }
                        }

                        Text("点击返回正面", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        // Bottom action buttons (only show when flipped)
        if (isFlipped) {
            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 32.dp).padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { isFlipped = false; viewModel.markForgotten() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(stringResource(R.string.flashcard_forgot))
                }
                Button(
                    onClick = { isFlipped = false; viewModel.markRemembered() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(stringResource(R.string.flashcard_remembered))
                }
            }
        }
    }
}

@Composable
private fun ReviewCompleteScreen(sessionReviewed: Int, sessionRemembered: Int, onExit: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🎉", fontSize = 64.sp)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.flashcard_review_complete), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.flashcard_session_summary, sessionReviewed, sessionRemembered), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            Button(onClick = onExit, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.flashcard_back_to_list)) }
        }
    }
}
