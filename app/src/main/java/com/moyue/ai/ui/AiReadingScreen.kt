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
import androidx.compose.material.icons.filled.Edit
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
import com.moyue.app.data.BookRepository
import com.moyue.app.data.models.ReaderTheme
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
    bookRepository: BookRepository? = null,
    edgeEndpoint: String = "",
    edgeVoice: String = "zh-CN-XiaoxiaoNeural",
    paragraphs: List<String> = emptyList(),
    currentParagraphIndex: Int = 0,
    selectedText: String? = null,
    initialTab: Int? = null,
    initialQuizScope: String? = null,
    initialQuizCount: Int? = null,
    initialQuizDifficulty: String? = null,
    isPdfMode: Boolean = false,
    readerTheme: ReaderTheme = ReaderTheme.LIGHT,
    onRecreateQuiz: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { AiCacheRepository(context) }

    var aiConfig by remember { mutableStateOf(repository.getAiConfig()) }
    val einkRequested = isEinkMode || repository.isEinkMode()
    // 暗色主题优先：用户主动选了深夜黑/石板灰时，墨水屏的白底黑字让位。
    // 否则会出现「暗色顶栏 + 黑字黑边框」完全看不见的死结。
    val isEink = einkRequested && !readerTheme.isDark
    // 跟随与否只看阅读主题本身：与墨水屏、App 全局主题全部解耦，不会互相干扰。
    val followReaderDark = readerTheme.isDark
    var textSizeSp by remember { mutableFloatStateOf(repository.getSummaryTextSize()) }
    var displayMode by remember { mutableStateOf(repository.getLanguageDisplayMode()) }

    val onDisplayModeChange: (String) -> Unit = { mode ->
        displayMode = mode
        repository.setLanguageDisplayMode(mode)
    }

    var selectedTab by remember { mutableIntStateOf(initialTab?.coerceIn(0, 3) ?: repository.getLastAiTab().coerceIn(0, 3)) } // 0: Summary, 1: Plot, 2: Quiz, 3: Reports
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
        AiThemeScope(followReaderDark, readerTheme) {
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

                                // 换页出题 / 重新出题按钮（由外层 PDF 阅读器注入）
                                if (onRecreateQuiz != null) {
                                    IconButton(onClick = onRecreateQuiz) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = stringResource(R.string.pdf_ai_quiz_recreate_action),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
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

                            // 4 Tabs (Enforce single-line compact layout)
                            TabRow(
                                selectedTabIndex = selectedTab,
                                containerColor = if (isEink) Color.White else MaterialTheme.colorScheme.surface,
                                contentColor = if (isEink) Color.Black else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                tabs.forEachIndexed { index, title ->
                                    Tab(
                                        selected = (selectedTab == index),
                                        onClick = {
                                            selectedTab = index
                                            repository.setLastAiTab(index)
                                        },
                                        modifier = Modifier.height(44.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 4.dp),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = title,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                softWrap = false,
                                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isEink) (if (selectedTab == index) Color.Black else Color.Gray)
                                                else (if (selectedTab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                            )
                                        }
                                    }
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
                                bookRepository = bookRepository,
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
                                textSizeSp = textSizeSp,
                                displayMode = displayMode,
                                bookRepository = bookRepository
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
                                bookRepository = bookRepository,
                                edgeEndpoint = edgeEndpoint,
                                edgeVoice = edgeVoice,
                                paragraphs = paragraphs,
                                currentParagraphIndex = currentParagraphIndex,
                                selectedText = selectedText,
                                initialScope = initialQuizScope,
                                initialCount = initialQuizCount,
                                initialDifficulty = initialQuizDifficulty,
                                isPdfMode = isPdfMode,
                                onTriggerPdfRecreate = onRecreateQuiz,
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

/**
 * AI 伴读页面的主题作用域。
 * 仅当「非墨水屏 + 阅读主题为暗色」时才套用暗色配色方案，其余情况原样放行，
 * 保证墨阅既有的墨水屏白底黑字逻辑与浅色主题一字不变。
 */
@Composable
private fun AiThemeScope(
    enabled: Boolean,
    readerTheme: ReaderTheme,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        content()
    } else {
        MaterialTheme(colorScheme = aiDarkSchemeFor(readerTheme), content = content)
    }
}

/**
 * 由阅读主题派生 AI 页面的暗色配色，底色与正文完全一致，卡片按比例提亮做出层次，
 * 避免「正文是深夜黑、AI 页却白底」的割裂与夜间刺眼。
 */
private fun aiDarkSchemeFor(theme: ReaderTheme): ColorScheme {
    val bg = parseReaderHex(theme.bgColor)
    val fg = parseReaderHex(theme.textColor)
    val surface = blendColor(bg, Color.White, 0.07f)
    val surfaceVariant = blendColor(bg, Color.White, 0.13f)
    val accent = Color(0xFF66B3FF)
    return darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF0A1520),
        primaryContainer = blendColor(bg, accent, 0.22f),
        onPrimaryContainer = fg,
        secondary = accent,
        onSecondary = Color(0xFF0A1520),
        tertiary = Color(0xFF34D399),
        onTertiary = Color(0xFF08130D),
        background = bg,
        onBackground = fg,
        surface = surface,
        onSurface = fg,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = blendColor(fg, bg, 0.22f),
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceVariant,
        surfaceContainerHighest = surfaceVariant,
        outline = blendColor(bg, Color.White, 0.24f),
        outlineVariant = blendColor(bg, Color.White, 0.14f),
        error = Color(0xFFFF8A80),
        onError = Color(0xFF200A0A),
        errorContainer = blendColor(bg, Color(0xFFEF4444), 0.24f),
        onErrorContainer = Color(0xFFFFDAD6),
        inverseSurface = fg,
        inverseOnSurface = bg,
    )
}

/** 线性混合两个颜色，t=0 取 a，t=1 取 b。 */
private fun blendColor(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f
)

/** 把 ReaderTheme 里的 "#RRGGBB" 解析成 Compose Color。 */
private fun parseReaderHex(hex: String): Color {
    val parsed = runCatching { android.graphics.Color.parseColor(hex) }.getOrNull()
        ?: return Color(0xFF1A1A1A)
    return Color(parsed)
}
