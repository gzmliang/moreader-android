package com.moyue.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
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
import com.moyue.ai.data.AiCacheRepository
import com.moyue.ai.model.AiConfig
import com.moyue.ai.ui.components.PlotMapTabContent
import com.moyue.ai.ui.components.QuizTabContent
import com.moyue.ai.ui.components.ReportsTabContent
import com.moyue.ai.ui.components.SummaryTabContent
import com.moyue.app.R
import com.moyue.app.data.BookDao
import com.moyue.app.tts.EdgeTTSProvider

@Composable
fun AiReadingScreen(
    bookId: String,
    bookTitle: String,
    chapterIndex: Int,
    chapterTitle: String,
    chapterText: String,
    bookDao: BookDao,
    edgeTTS: EdgeTTSProvider?,
    isEinkMode: Boolean = false,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { AiCacheRepository(context) }

    var aiConfig by remember { mutableStateOf(repository.getAiConfig()) }
    val isEink = isEinkMode || repository.isEinkMode()
    var textSizeSp by remember { mutableFloatStateOf(repository.getSummaryTextSize()) }
    var displayMode by remember { mutableStateOf(repository.getLanguageDisplayMode()) }

    val onDisplayModeChange: (String) -> Unit = { mode ->
        displayMode = mode
        repository.setLanguageDisplayMode(mode)
    }

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Summary, 1: Plot, 2: Quiz, 3: Reports
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showGuideDialog by remember { mutableStateOf(!aiConfig.isConfigured) }

    // Summary tab actions & state
    var summaryHasResult by remember { mutableStateOf(false) }
    var isSummaryAudioPlaying by remember { mutableStateOf(false) }
    var onSummaryAudioClick by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onSummarySaveClick by remember { mutableStateOf<(() -> Unit)?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            edgeTTS?.stop()
        }
    }

    val tabs = listOf(
        stringResource(R.string.ai_companion_tab_summary),
        stringResource(R.string.ai_companion_tab_plot),
        stringResource(R.string.ai_companion_tab_quiz),
        stringResource(R.string.ai_companion_tab_reports)
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = if (isEink) Color.White else MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                // Top Navigation Bar
                Surface(
                    color = if (isEink) Color.White else MaterialTheme.colorScheme.surface,
                    shadowElevation = if (isEink) 0.dp else 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (isEink) Modifier.border(1.dp, Color.Black) else Modifier)
                ) {
                    Column {
                        // Title row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Default.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                    tint = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = bookTitle,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = chapterTitle,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Summary actions: 🎧 Listen & 📥 Save to Library (shown when summary is ready)
                            if (selectedTab == 0 && summaryHasResult) {
                                IconButton(
                                    onClick = { onSummaryAudioClick?.invoke() }
                                ) {
                                    Icon(
                                        imageVector = if (isSummaryAudioPlaying) Icons.Default.Stop else Icons.Default.Headphones,
                                        contentDescription = if (isSummaryAudioPlaying) stringResource(R.string.ai_stop_listen_btn) else stringResource(R.string.ai_play_listen_btn),
                                        tint = if (isSummaryAudioPlaying) Color(0xFFEF4444) else (if (isEink) Color.Black else Color(0xFF10B981))
                                    )
                                }

                                IconButton(
                                    onClick = { onSummarySaveClick?.invoke() }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.BookmarkAdd,
                                        contentDescription = stringResource(R.string.ai_save_to_library_btn),
                                        tint = if (isEink) Color.Black else MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            // Global Language Mode Dropdown: 🌐 [双语 ▾] / [原文 ▾] / [译文 ▾]
                            var langMenuExpanded by remember { mutableStateOf(false) }
                            Box {
                                Surface(
                                    color = if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .height(28.dp)
                                        .clickable { langMenuExpanded = true }
                                        .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(12.dp)) else Modifier)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 6.dp)
                                    ) {
                                        val langLabel = when (displayMode) {
                                            "orig" -> stringResource(R.string.ai_display_mode_orig_short)
                                            "target" -> stringResource(R.string.ai_display_mode_trans_short)
                                            else -> stringResource(R.string.ai_display_mode_bilingual_short)
                                        }
                                        Text(
                                            text = "🌐 $langLabel",
                                            fontSize = 11.sp,
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
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = langMenuExpanded,
                                    onDismissRequest = { langMenuExpanded = false }
                                ) {
                                    listOf(
                                        "bilingual" to stringResource(R.string.ai_display_mode_bilingual),
                                        "orig" to stringResource(R.string.ai_display_mode_original),
                                        "target" to stringResource(R.string.ai_display_mode_target)
                                    ).forEach { (mKey, mLabel) ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = mLabel,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (displayMode == mKey) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (displayMode == mKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                            },
                                            onClick = {
                                                onDisplayModeChange(mKey)
                                                langMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            // A- / A+ Text size controls
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "A-",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .clickable {
                                            if (textSizeSp > 12f) {
                                                textSizeSp -= 2f
                                                repository.setSummaryTextSize(textSizeSp)
                                            }
                                        }
                                        .padding(4.dp)
                                )
                                Text(
                                    text = "A+",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .clickable {
                                            if (textSizeSp < 28f) {
                                                textSizeSp += 2f
                                                repository.setSummaryTextSize(textSizeSp)
                                            }
                                        }
                                        .padding(4.dp)
                                )
                            }

                            // Settings
                            IconButton(onClick = { showSettingsDialog = true }) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = stringResource(R.string.ai_settings_title),
                                    tint = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // 4 Tabs
                        TabRow(
                            selectedTabIndex = selectedTab,
                            containerColor = if (isEink) Color.White else MaterialTheme.colorScheme.surface,
                            contentColor = if (isEink) Color.Black else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            tabs.forEachIndexed { index, title ->
                                Tab(
                                    selected = (selectedTab == index),
                                    onClick = { selectedTab = index },
                                    text = {
                                        Text(
                                            text = title,
                                            fontSize = 12.sp,
                                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isEink) (if (selectedTab == index) Color.Black else Color.Gray)
                                            else (if (selectedTab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                // Body content based on selected Tab
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (selectedTab) {
                        0 -> SummaryTabContent(
                            bookId = bookId,
                            bookTitle = bookTitle,
                            chapterIndex = chapterIndex,
                            chapterTitle = chapterTitle,
                            chapterText = chapterText,
                            repository = repository,
                            config = aiConfig,
                            isEink = isEink,
                            textSizeSp = textSizeSp,
                            bookDao = bookDao,
                            edgeTTS = edgeTTS,
                            displayMode = displayMode,
                            onDisplayModeChange = onDisplayModeChange,
                            onHasResultChange = { summaryHasResult = it },
                            onAudioPlayingChange = { isSummaryAudioPlaying = it },
                            onRegisterAudioAction = { action -> onSummaryAudioClick = action },
                            onRegisterSaveAction = { action -> onSummarySaveClick = action }
                        )
                        1 -> PlotMapTabContent(
                            bookId = bookId,
                            bookTitle = bookTitle,
                            chapterIndex = chapterIndex,
                            chapterTitle = chapterTitle,
                            chapterText = chapterText,
                            repository = repository,
                            config = aiConfig,
                            isEink = isEink,
                            textSizeSp = textSizeSp
                        )
                        2 -> QuizTabContent(
                            bookId = bookId,
                            bookTitle = bookTitle,
                            chapterIndex = chapterIndex,
                            chapterTitle = chapterTitle,
                            chapterText = chapterText,
                            repository = repository,
                            config = aiConfig,
                            isEink = isEink,
                            textSizeSp = textSizeSp,
                            displayMode = displayMode,
                            onDisplayModeChange = onDisplayModeChange,
                            onQuizCompleted = {
                                // switch to reports or stay
                            }
                        )
                        3 -> ReportsTabContent(
                            bookId = bookId,
                            repository = repository,
                            isEink = isEink,
                            textSizeSp = textSizeSp,
                            displayMode = displayMode
                        )
                    }
                }
            }
        }
    }

    // Guide Dialog if not configured
    if (showGuideDialog) {
        AlertDialog(
            onDismissRequest = { showGuideDialog = false },
            title = { Text(stringResource(R.string.ai_settings_title)) },
            text = { Text(stringResource(R.string.ai_settings_guide_tip)) },
            confirmButton = {
                Button(onClick = {
                    showGuideDialog = false
                    showSettingsDialog = true
                }) {
                    Text(stringResource(R.string.ai_settings_guide_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGuideDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    // Settings Dialog
    if (showSettingsDialog) {
        AiSettingsDialog(
            repository = repository,
            onDismiss = { showSettingsDialog = false },
            onSaved = { newConfig ->
                aiConfig = newConfig
            }
        )
    }
}
