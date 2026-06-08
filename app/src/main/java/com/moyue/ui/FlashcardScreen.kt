package com.moyue.app.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
import com.moyue.app.data.FlashcardDataStore.Companion.DEFAULT_PLAN
import com.moyue.app.data.FlashcardDataStore.Flashcard
import com.moyue.app.data.models.TTSProviderType

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
            title = { Text(stringResource(R.string.flashcard_reset_all_title)) },
            text = { Text(stringResource(R.string.flashcard_reset_all_confirm)) },
            confirmButton = {
                TextButton(onClick = { showResetConfirm = false; viewModel.resetAllFlashcards() }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }

    if (showNewPlanDialog) {
        AlertDialog(
            onDismissRequest = { showNewPlanDialog = false },
            title = { Text(stringResource(R.string.flashcard_plan_create_title)) },
            text = {
                OutlinedTextField(
                    value = planNameInput,
                    onValueChange = { planNameInput = it },
                    placeholder = { Text(stringResource(R.string.flashcard_plan_create_hint)) },
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
                }) { Text(stringResource(R.string.flashcard_plan_create_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewPlanDialog = false; planNameInput = "" }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }

    if (showDeletePlanDialog) {
        AlertDialog(
            onDismissRequest = { showDeletePlanDialog = false },
            title = { Text(stringResource(R.string.flashcard_plan_delete_title)) },
            text = { Text(stringResource(R.string.flashcard_plan_delete_confirm, uiState.currentPlan)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deletePlan(uiState.currentPlan); showDeletePlanDialog = false }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePlanDialog = false }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }

    // TTS log dialog
    if (showTtsLogDialog) {
        AlertDialog(
            onDismissRequest = { showTtsLogDialog = false },
            title = { Text(stringResource(R.string.flashcard_tts_log_title)) },
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
                        Text(stringResource(R.string.flashcard_tts_log_empty), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTtsLogDialog = false }) { Text(stringResource(R.string.flashcard_tts_close)) }
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
                        ttsLogContent = viewModel.getTtsLog()
                        showTtsLogDialog = true
                    }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.flashcard_tts_log_view))
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
        ) {
            // ── Settings Card: Plan + TTS Engine ──
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(14.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Section label
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "⚙",
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.flashcard_plan_label),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    PlanSelector(
                        plans = uiState.plans,
                        currentPlan = uiState.currentPlan,
                        onSelectPlan = { viewModel.switchPlan(it) },
                        onCreatePlan = { planNameInput = ""; showNewPlanDialog = true },
                        onDeletePlan = { showDeletePlanDialog = true }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                    )

                    // Flashcard TTS engine selector
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "🔊",
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.flashcard_tts_engine),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    FlashcardTtsEngineSelector(context = context)
                }
            }

            // Overview
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Total words stat
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(60.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    total.toString(),
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.flashcard_total),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }

                // Due count stat
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (dueCount > 0) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(60.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                dueCount.toString(),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (dueCount > 0) MaterialTheme.colorScheme.onTertiaryContainer
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.flashcard_due),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }

                // Progress
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(60.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            val progress = if (total > 0) ((total - dueCount).toFloat() / total * 100).toInt() else 0
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "$progress%",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.flashcard_review_overview),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onStartReview,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f),
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.flashcard_start_review),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            }

            if (total > 0) {
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = onRequestResetAll,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.flashcard_reset_all_btn),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
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
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        plans.forEach { plan ->
            val isSelected = plan == currentPlan
            Surface(
                onClick = { onSelectPlan(plan) },
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                border = if (isSelected) BorderStroke(
                    0.5.dp,
                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
                ) else null,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (plan == DEFAULT_PLAN) stringResource(R.string.flashcard_plan_default) else plan,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onTertiaryContainer
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    if (isSelected && plan != DEFAULT_PLAN) {
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = onDeletePlan,
                            modifier = Modifier.size(16.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.flashcard_plan_delete),
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
        // "+" button
        Surface(
            onClick = onCreatePlan,
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            border = BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
            ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "+",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.flashcard_plan_new),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlashcardTtsEngineSelector(context: Context) {
    val prefs = context.getSharedPreferences("moreader_config", Context.MODE_PRIVATE)
    var selectedProvider by remember { mutableStateOf(
        try { TTSProviderType.valueOf(prefs.getString("flashcard_tts_provider", "edge_tts") ?: "edge_tts") }
        catch (e: IllegalArgumentException) { TTSProviderType.EDGE_TTS }
    ) }
    var expanded by remember { mutableStateOf(false) }

    Box {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            border = androidx.compose.foundation.BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(
                        when (selectedProvider) {
                            TTSProviderType.EDGE_TTS -> R.string.tts_provider_edge
                            TTSProviderType.AI_VOICE -> R.string.tts_provider_ai
                            TTSProviderType.CUSTOM_TTS -> R.string.tts_provider_custom
                            TTSProviderType.SYSTEM -> R.string.tts_provider_system
                        }
                    ),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(200.dp)
        ) {
            TTSProviderType.entries.forEach { provider ->
                val isSelected = provider == selectedProvider
                DropdownMenuItem(
                    onClick = {
                        selectedProvider = provider
                        prefs.edit().putString("flashcard_tts_provider", provider.name).apply()
                        expanded = false
                    },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(
                                    when (provider) {
                                        TTSProviderType.EDGE_TTS -> R.string.tts_provider_edge
                                        TTSProviderType.AI_VOICE -> R.string.tts_provider_ai
                                        TTSProviderType.CUSTOM_TTS -> R.string.tts_provider_custom
                                        TTSProviderType.SYSTEM -> R.string.tts_provider_system
                                    }
                                ),
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.tertiary
                                        else MaterialTheme.colorScheme.onSurface
                            )
                            if (isSelected) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "✓",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                )
            }
        }
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
    val isDue = flashcard.dueDate <= System.currentTimeMillis()

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDue) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = flashcard.word,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                    if (isDue) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "●",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
                        )
                    } else {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = dueLabel,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }
                val posLine = listOfNotNull(
                    flashcard.pronunciation,
                    flashcard.partOfSpeech?.let { "[$it]" }
                ).joinToString("  ")
                if (posLine.isNotEmpty()) {
                    Text(
                        posLine,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                flashcard.chineseDef?.split("\n")?.firstOrNull()
                    ?.replace(Regex("^\\d+\\.\\s*"), "")?.let { def ->
                        Text(
                            text = def,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onSpeak,
                    modifier = Modifier.size(36.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f)
                    ) {
                        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.flashcard_speak),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
                if (flashcard.repetition > 0) {
                    IconButton(
                        onClick = onReset,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Replay,
                            contentDescription = stringResource(R.string.flashcard_reset_card),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                        )
                    }
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.flashcard_remove_card),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                    )
                }
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
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).padding(top = 40.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                IconButton(onClick = onExit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
            ) {
                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${uiState.currentPlan}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }

        // Progress indicator
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 80.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LinearProgressIndicator(
                progress = { (currentIndex.toFloat() + 1f) / uiState.dueFlashcards.size.toFloat() },
                modifier = Modifier.weight(1f).height(3.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                "${currentIndex + 1}/${uiState.dueFlashcards.size}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }

        // Card content - tap to flip
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 110.dp)
                .clickable { isFlipped = !isFlipped },
            contentAlignment = Alignment.Center
        ) {
            if (!isFlipped) {
                // Front: word card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(24.dp))
                        Text(
                            text = currentCard.word,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                            letterSpacing = 2.sp
                        )
                        Spacer(Modifier.height(40.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.2f)
                        ) {
                            IconButton(
                                onClick = { viewModel.speakWord(currentCard.word, context) },
                                modifier = Modifier.size(56.dp)
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = stringResource(R.string.flashcard_speak),
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(
                            stringResource(R.string.flashcard_tap_to_flip),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Light,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            } else {
                // Back: bilingual definitions
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                        Text(
                            text = currentCard.word,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            modifier = Modifier.fillMaxWidth()
                        )
                        currentCard.pronunciation?.let { pron ->
                            Text(pron, fontSize = 14.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                        currentCard.partOfSpeech?.let { pos ->
                            Spacer(Modifier.height(6.dp))
                            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.15f)) {
                                Text("  $pos  ", fontSize = 11.sp, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                            }
                        }
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                        Column(modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
                            if (isLoadingDefinition && currentCard.chineseDef.isNullOrBlank() && currentCard.englishDef.isNullOrBlank()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.tertiary)
                                    Spacer(Modifier.height(12.dp))
                                    Text(stringResource(R.string.flashcard_fetching_definition), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                }
                            } else {
                                if (!currentCard.chineseDef.isNullOrBlank()) {
                                    Text("中文释义", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), letterSpacing = 1.sp)
                                    Spacer(Modifier.height(4.dp))
                                    Text(currentCard.chineseDef, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), lineHeight = 22.sp, modifier = Modifier.padding(start = 2.dp))
                                    Spacer(Modifier.height(10.dp))
                                }
                                if (!currentCard.englishDef.isNullOrBlank()) {
                                    Text("English", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), letterSpacing = 1.sp)
                                    Spacer(Modifier.height(4.dp))
                                    Text(currentCard.englishDef, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), lineHeight = 20.sp, modifier = Modifier.padding(start = 2.dp))
                                    Spacer(Modifier.height(10.dp))
                                }
                                if (!currentCard.exampleText.isNullOrBlank()) {
                                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                                    Spacer(Modifier.height(4.dp))
                                    Text("例句", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), modifier = Modifier.padding(top = 4.dp), letterSpacing = 1.sp)
                                    Text(currentCard.exampleText, fontSize = 13.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 2.dp, start = 2.dp))
                                    currentCard.exampleTranslation?.let {
                                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.padding(top = 2.dp, start = 2.dp))
                                    }
                                }
                                if (currentCard.chineseDef.isNullOrBlank() && currentCard.englishDef.isNullOrBlank() && !isLoadingDefinition) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("⚠️", fontSize = 24.sp)
                                        Spacer(Modifier.height(8.dp))
                                        Text(stringResource(R.string.flashcard_no_definition), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                    }
                                }
                            }
                        }

                        Text(stringResource(R.string.flashcard_tap_back_front), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
        }

        // Bottom action buttons (only show when flipped)
        if (isFlipped) {
            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 24.dp).padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { isFlipped = false; viewModel.markForgotten() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                    ),
                    contentPadding = PaddingValues(vertical = 13.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.flashcard_forgot), fontWeight = FontWeight.Normal, fontSize = 13.sp)
                }
                Button(
                    onClick = { isFlipped = false; viewModel.markRemembered() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
                    ),
                    contentPadding = PaddingValues(vertical = 13.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.flashcard_remembered), fontWeight = FontWeight.Normal, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ReviewCompleteScreen(sessionReviewed: Int, sessionRemembered: Int, onExit: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.08f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.08f)
                    ),
                    endY = 600f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.15f),
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("✓", fontSize = 28.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.flashcard_review_complete),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "$sessionReviewed",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Text(
                            "已复习",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                    Box(modifier = Modifier.width(1.dp).height(36.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "$sessionRemembered",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                        )
                        Text(
                            "已记住",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }
            Spacer(Modifier.height(36.dp))
            Button(
                onClick = onExit,
                modifier = Modifier.widthIn(min = 200.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
                ),
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 13.dp)
            ) {
                Text(stringResource(R.string.flashcard_back_to_list), fontWeight = FontWeight.Normal, fontSize = 14.sp)
            }
        }
    }
}
