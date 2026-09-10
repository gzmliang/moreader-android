package com.moyue.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
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

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Summary, 1: Plot, 2: Quiz, 3: Reports
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showGuideDialog by remember { mutableStateOf(!aiConfig.isConfigured) }

    val tabs = listOf(
        stringResource(R.string.ai_companion_tab_summary),
        stringResource(R.string.ai_companion_tab_plot),
        stringResource(R.string.ai_companion_tab_quiz),
        stringResource(R.string.ai_companion_tab_reports)
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = if (isEink) Color.White else MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
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

                            // Close
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.close),
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
                            edgeTTS = edgeTTS
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
                            onQuizCompleted = {
                                // switch to reports or stay
                            }
                        )
                        3 -> ReportsTabContent(
                            bookId = bookId,
                            repository = repository,
                            isEink = isEink,
                            textSizeSp = textSizeSp
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
