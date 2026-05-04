package com.moyue.app.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.moyue.app.data.BookRepository
import com.moyue.app.data.models.*
import com.moyue.app.reader.EpubWebView
import com.moyue.app.reader.repositoryRef
import com.moyue.app.ui.components.TtsSettingsSheet
import java.lang.ref.WeakReference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    repository: BookRepository,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = viewModel(
        key = "reader_$bookId",
        factory = ReaderViewModelFactory(repository, LocalContext.current.applicationContext as android.app.Application)
    ),
) {
    LaunchedEffect(repository) { repositoryRef = WeakReference(repository) }
    LaunchedEffect(bookId) { viewModel.loadBook(bookId) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val highlights by viewModel.loadedHighlights.collectAsStateWithLifecycle()

    // Load highlights when chapter changes
    LaunchedEffect(state.currentChapterIndex, state.currentHtml) {
        if (state.currentHtml != null && !state.isLoading) {
            viewModel.loadHighlightsForChapter()
        }
    }

    // Fullscreen tap hint - shows briefly when entering fullscreen
    var showFullscreenHint by remember { mutableStateOf(false) }
    LaunchedEffect(state.isFullscreen) {
        if (state.isFullscreen) {
            showFullscreenHint = true
            kotlinx.coroutines.delay(2000)
            showFullscreenHint = false
        }
    }

    // TTS highlight index
    val ttsHighlightIdx = if (state.currentChapterIndex < state.chapters.size) state.ttsCurrentIdx else -1

    // Translation result panel (kept as dialog, less frequent)
    if (state.showTranslationPanel) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissTranslationPanel() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(when (state.translationMode) { "translate" -> androidx.compose.ui.res.stringResource(com.moyue.app.R.string.ai_translate_title); "explain" -> androidx.compose.ui.res.stringResource(com.moyue.app.R.string.explain_title); "analyze" -> androidx.compose.ui.res.stringResource(com.moyue.app.R.string.grammar_title); else -> androidx.compose.ui.res.stringResource(com.moyue.app.R.string.ai_translate_title) },
                        modifier = Modifier.weight(1f))
                    // Speaker button to read the selected text aloud
                    if (state.selectedText != null) {
                        IconButton(onClick = { viewModel.speakTranslationText(state.selectedText!!) }) {
                            Icon(Icons.Default.VolumeUp, contentDescription = "朗读", tint = Color(0xFF059669), modifier = Modifier.size(20.dp))
                        }
                    }
                }
            },
            text = {
                Column {
                    if (state.isTranslating) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp)); Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.translating), fontSize = 14.sp)
                        }
                    }
                    Text(state.translationResult ?: "", fontSize = 14.sp, lineHeight = 22.sp)
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.dismissTranslationPanel() }) { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.close)) } },
        )
    }

    // Main layout
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(state.showBookmarkToast) {
        if (state.showBookmarkToast) {
            snackbarHostState.showSnackbar(state.bookmarkToastMsg)
        }
    }
    
    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        topBar = {
            AnimatedVisibility(visible = !state.isFullscreen) {
                TopAppBar(
                    title = {
                        Column {
                            Text(state.book?.title ?: androidx.compose.ui.res.stringResource(com.moyue.app.R.string.loading), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (state.currentChapterIndex < state.chapters.size)
                                Text("${state.currentChapterIndex + 1}/${state.chapters.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.back)) } },
                    actions = {
                        IconButton(onClick = { viewModel.toggleFullscreen() }) { Icon(Icons.Default.Fullscreen, contentDescription = "全屏模式") }
                        IconButton(onClick = { viewModel.toggleBookmarkPanel() }) { Icon(Icons.Outlined.BookmarkBorder, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.bookmark_list_title)) }
                        IconButton(onClick = { viewModel.toggleTocPanel() }) { Icon(Icons.Default.List, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.table_of_contents)) }
                        IconButton(onClick = { viewModel.toggleTtsSettingsPanel() }) { Icon(Icons.Default.Settings, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_settings)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(android.graphics.Color.parseColor(state.theme.bgColor)),
                        titleContentColor = Color(android.graphics.Color.parseColor(state.theme.textColor)),
                        actionIconContentColor = Color(android.graphics.Color.parseColor(state.theme.textColor)),
                        navigationIconContentColor = Color(android.graphics.Color.parseColor(state.theme.textColor)),
                    ),
                )
            }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                // Debug Log panel
                AnimatedVisibility(visible = state.showTtsDebugLog && !state.isFullscreen) {
                    Surface(tonalElevation = 4.dp, color = Color(0xFF1A1A2E)) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).padding(8.dp).verticalScroll(rememberScrollState())) {
                            Text(text = state.ttsDebugLog.ifEmpty { androidx.compose.ui.res.stringResource(com.moyue.app.R.string.debug_no_log) },
                                fontSize = 10.sp, color = Color(0xFF00FF88),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, lineHeight = 14.sp)
                        }
                    }
                    Surface(color = Color(0xFF2A2A3E)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.highlight_offset), fontSize = 11.sp, color = Color(0xFF888888))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { viewModel.decreaseHighlightOffset() }, modifier = Modifier.size(28.dp)) { Text("-", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                                Text("${state.ttsHighlightOffset}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
                                IconButton(onClick = { viewModel.increaseHighlightOffset() }, modifier = Modifier.size(28.dp)) { Text("+", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                            }
                        }
                    }
                }

                // Selection toolbar — compact single row
                if (state.showSelectionMenu && state.selectedText != null && !state.isTtsPlaying && !state.isTtsPaused) {
                    val existingHighlight = viewModel.getExistingHighlightForSelection()
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        shadowElevation = 8.dp,
                        shape = RoundedCornerShape(12.dp),
                        color = Color(android.graphics.Color.parseColor(state.theme.bgColor)).copy(alpha = 0.97f),
                    ) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 2.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { viewModel.addVocabulary() },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF7C4DFF)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF7C4DFF))
                                Spacer(Modifier.width(2.dp))
                                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.vocabulary_add), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                            if (existingHighlight != null) {
                                TextButton(
                                    onClick = { viewModel.dismissSelectionMenu(); viewModel.removeHighlight(existingHighlight) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.highlight_remove), fontSize = 11.sp, maxLines = 1) }
                            } else {
                                TextButton(
                                    onClick = { viewModel.dismissSelectionMenu(); viewModel.addHighlight() },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE6A800)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.highlight_add), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                            }
                            TextButton(onClick = { viewModel.dismissSelectionMenu(); viewModel.readSelection(state.selectedText!!) }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF059669)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.read_aloud), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                            TextButton(onClick = { viewModel.dismissSelectionMenu(); viewModel.translate("translate") }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.ai_translate), fontSize = 11.sp, maxLines = 1) }
                            TextButton(onClick = { viewModel.dismissSelectionMenu(); viewModel.translate("explain") }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.explain), fontSize = 11.sp, maxLines = 1) }
                            TextButton(onClick = { viewModel.dismissSelectionMenu(); viewModel.translate("analyze") }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.grammar_analysis), fontSize = 11.sp, maxLines = 1) }
                        }
                    }
                }

                AnimatedVisibility(visible = !state.isFullscreen) {
                    ReaderBottomBar(
                        currentIndex = state.currentChapterIndex, totalChapters = state.chapters.size,
                        fontSize = state.fontSize,
                        isTtsPlaying = state.isTtsPlaying, isTtsPaused = state.isTtsPaused,
                        onPrev = { viewModel.prevChapter() }, onNext = { viewModel.nextChapter() },
                        onFontSizeChange = { viewModel.setFontSize(it) },
                        onPlayPause = { viewModel.togglePlayPause() },
                        onStop = { viewModel.ttsStop() },
                        onToggleDebug = { viewModel.toggleTtsDebugLog() },
                        onNavigateToChapter = { idx ->
                            val ch = state.chapters.getOrNull(idx)
                            if (ch != null) viewModel.navigateToChapter(ch.href)
                        },
                        bookProgress = if (state.chapters.isNotEmpty()) (state.currentChapterIndex + 1).toFloat() / state.chapters.size else 0f,
                        bgColor = state.theme.bgColor,
                        textColor = state.theme.textColor,
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(state.loadingMessage, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Spacer(Modifier.height(8.dp))
                        Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.large_book_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
                }
            } else if (state.error != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
                }
            } else {
                val baseUrl = state.chapters.getOrNull(state.currentChapterIndex)?.href?.let { href ->
                    val idx = href.lastIndexOf('/')
                    if (idx >= 0) "file:///${href.substring(0, idx + 1)}" else null
                }
                
                // Paragraph click menu state
                var showParagraphMenu by remember { mutableStateOf(false) }
                var clickedParagraphIndex by remember { mutableStateOf(-1) }
                
                EpubWebView(
                    htmlContent = state.currentHtml,
                    baseUrl = baseUrl,
                    bgColor = state.theme.bgColor,
                    textColor = state.theme.textColor,
                    fontScale = state.fontSize / 18f,
                    onTextSelected = { viewModel.onTextSelected(it) },
                    onLinkClicked = { viewModel.onLinkClicked(it) },
                    onParagraphClicked = { idx ->
                        clickedParagraphIndex = idx
                        showParagraphMenu = true
                    },
                    ttsHighlightIndex = ttsHighlightIdx,
                    scrollToParagraph = if (state.scrollToParagraph >= 0) state.scrollToParagraph else null,
                    highlightsToRender = highlights.map { Triple(it.startParagraph, it.startOffset, it.endOffset) },
                    highlightToRemove = state.highlightToRemove?.let { Pair(it.startOffset, it.endOffset) },
                    modifier = Modifier.fillMaxSize().background(Color(android.graphics.Color.parseColor(state.theme.bgColor))),
                )
                
                // Paragraph click menu
                if (showParagraphMenu) {
                    AlertDialog(
                        onDismissRequest = { showParagraphMenu = false },
                        title = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.paragraph_menu_title)) },
                        text = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.paragraph_menu_message)) },
                        confirmButton = {
                            Row {
                                TextButton(
                                    onClick = {
                                        viewModel.setCurrentParagraph(clickedParagraphIndex)
                                        showParagraphMenu = false
                                        viewModel.addBookmark()
                                    }
                                ) { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.bookmark_list_title)) }
                                TextButton(
                                    onClick = {
                                        showParagraphMenu = false
                                        viewModel.readFromParagraph(clickedParagraphIndex)
                                    }
                                ) { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.read_from_here)) }
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showParagraphMenu = false }
                            ) { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.cancel)) }
                        }
                    )
                }
                }

                // Fullscreen exit button — semi-transparent X at bottom center
                if (state.isFullscreen) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                            .clickable(onClick = { viewModel.toggleFullscreen() })
                            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "退出全屏",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                // TOC overlay
            AnimatedVisibility(
                visible = state.showTocPanel,
                enter = slideInHorizontally(initialOffsetX = { -it }),
                exit = slideOutHorizontally(targetOffsetX = { -it }),
                modifier = Modifier.fillMaxHeight().width(280.dp),
            ) {
                TocDrawer(state.toc, state.chapters.getOrNull(state.currentChapterIndex)?.href,
                    onNavigate = { viewModel.navigateToChapter(it) }, onClose = { viewModel.toggleTocPanel() })
            }

            // Bookmark panel (slide from right)
            AnimatedVisibility(
                visible = state.showBookmarkPanel,
                enter = slideInHorizontally(initialOffsetX = { it }),
                exit = slideOutHorizontally(targetOffsetX = { it }),
                modifier = Modifier.fillMaxHeight().width(300.dp).align(Alignment.CenterEnd),
            ) {
                val bookmarks by repository.getBookmarksForBook(state.book?.id ?: "").collectAsStateWithLifecycle(initialValue = emptyList())
                val scope = rememberCoroutineScope()
                BookmarkPanel(
                    bookmarks = bookmarks,
                    onNavigate = { viewModel.navigateToBookmark(it) },
                    onDelete = { bookmark ->
                        scope.launch { repository.deleteBookmark(bookmark.id) }
                    },
                    onClose = { viewModel.toggleBookmarkPanel() },
                )
            }

            // TTS Settings overlay (includes theme picker now)
            AnimatedVisibility(
                visible = state.showTtsSettingsPanel,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                TtsSettingsSheet(
                    currentProvider = state.ttsProvider, ttsSpeed = state.ttsSpeed,
                    edgeEndpoint = state.edgeTtsEndpoint, edgeVoice = state.edgeTtsVoice,
                    aiEndpoint = state.aiVoiceEndpoint, aiApiKey = state.aiVoiceApiKey,
                    aiModel = state.aiVoiceModel, aiVoice = state.aiVoiceId,
                    customEndpoint = state.customTtsEndpoint, customApiKey = state.customTtsApiKey,
                    customModel = state.customTtsModel, customVoice = state.customTtsVoice,
                    llmConfig = state.llmConfig,
                    currentTheme = state.theme,
                    onProviderChange = { viewModel.setTTSProvider(it) },
                    onSpeedChange = { viewModel.setTTSSpeed(it) },
                    onEdgeConfigChange = { ep, v -> viewModel.updateEdgeTTSConfig(ep, v) },
                    onAIVoiceConfigChange = { ep, key, m, v -> viewModel.updateAIVoiceConfig(ep, key, m, v) },
                    onCustomTTSConfigChange = { ep, key, m, v -> viewModel.updateCustomTTSConfig(ep, key, m, v) },
                    onLLMConfigChange = { viewModel.updateLLMConfig(it) },
                    onThemeChange = { viewModel.setTheme(it) },
                    onClose = { viewModel.toggleTtsSettingsPanel() },
                )
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    currentIndex: Int, totalChapters: Int, fontSize: Int,
    isTtsPlaying: Boolean, isTtsPaused: Boolean,
    onPrev: () -> Unit, onNext: () -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onPlayPause: () -> Unit, onStop: () -> Unit,
    onToggleDebug: () -> Unit,
    onNavigateToChapter: (Int) -> Unit,
    bookProgress: Float,
    bgColor: String,
    textColor: String,
) {
    Surface(
        color = Color(android.graphics.Color.parseColor(bgColor)),
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Book progress — thin bar with small thumb, continuous drag
            val barTextColor = Color(android.graphics.Color.parseColor(textColor))
            val progressColor = Color(0xFF333333) // Dark/black line
            val trackColor = barTextColor.copy(alpha = 0.1f)
            var trackWidth by remember { mutableStateOf(0f) }
            var lastDragChapter by remember { mutableStateOf(-1) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${(bookProgress * 100).toInt()}%", fontSize = 9.sp, color = barTextColor, modifier = Modifier.width(30.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(16.dp) // Larger touch area
                        .onGloballyPositioned { coords -> trackWidth = coords.size.width.toFloat() }
                        .pointerInput(trackWidth, totalChapters, currentIndex) {
                            if (trackWidth <= 0) return@pointerInput
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val change = event.changes.firstOrNull() ?: break
                                    
                                    if (event.type == PointerEventType.Release || event.type == PointerEventType.Unknown) {
                                        lastDragChapter = -1
                                        break
                                    }
                                    
                                    if (event.type == PointerEventType.Press || event.type == PointerEventType.Move) {
                                        change.consume()
                                        val x = change.position.x.coerceIn(0f, trackWidth)
                                        val ratio = x / trackWidth
                                        val chapter = (ratio * totalChapters).toInt().coerceIn(0, totalChapters - 1)
                                        // Only navigate when chapter actually changes (prevents spam)
                                        if (chapter != lastDragChapter) {
                                            lastDragChapter = chapter
                                            if (chapter != currentIndex) onNavigateToChapter(chapter)
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    // Track background — centered vertically
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .height(3.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(2.dp))
                            .background(trackColor)
                    )
                    // Progress fill — centered vertically
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .height(3.dp)
                            .fillMaxWidth(bookProgress)
                            .clip(RoundedCornerShape(2.dp))
                            .background(progressColor)
                    )
                    // Small thumb indicator — positioned via Row + Spacer trick
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxSize()
                    ) {
                        Spacer(modifier = Modifier.fillMaxHeight().weight(if (bookProgress > 0.001f) bookProgress else 0.001f))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(progressColor)
                        )
                        Spacer(modifier = Modifier.fillMaxHeight().weight(if (bookProgress < 0.999f) 1f - bookProgress else 0.001f))
                    }
                }
                Text("${currentIndex + 1}/$totalChapters", fontSize = 9.sp, color = barTextColor, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
            }

            // Control buttons
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrev, enabled = currentIndex > 0) { Icon(Icons.Default.ChevronLeft, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.previous_chapter), tint = barTextColor) }

                IconButton(onClick = { onFontSizeChange(fontSize - 2) }) { Text("A-", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = barTextColor) }
                Text("$fontSize", fontSize = 11.sp, color = barTextColor)
                IconButton(onClick = { onFontSizeChange(fontSize + 2) }) { Text("A+", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = barTextColor) }

                Spacer(Modifier.weight(1f))

                if (isTtsPlaying || isTtsPaused) {
                    IconButton(onClick = onPlayPause) {
                        Icon(if (isTtsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isTtsPlaying) androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pause) else androidx.compose.ui.res.stringResource(com.moyue.app.R.string.resume),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    IconButton(onClick = onPlayPause) { Icon(Icons.Default.VolumeUp, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.play_chapter), tint = barTextColor) }
                }

                if (isTtsPlaying || isTtsPaused) {
                    IconButton(onClick = onStop) { Icon(Icons.Default.Stop, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.stop), tint = MaterialTheme.colorScheme.error) }
                }

                IconButton(onClick = onToggleDebug) { Icon(Icons.Default.BugReport, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.debug_log), modifier = Modifier.size(20.dp), tint = barTextColor) }

                IconButton(onClick = onNext, enabled = currentIndex < totalChapters - 1) { Icon(Icons.Default.ChevronRight, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.next_chapter), tint = barTextColor) }
            }
        }
    }
}

@Composable
private fun TocDrawer(toc: List<TocEntry>, currentChapterHref: String?, onNavigate: (String) -> Unit, onClose: () -> Unit) {
    Surface(Modifier.fillMaxHeight(), shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.table_of_contents), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.close)) }
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(toc) { _, entry ->
                    val isActive = currentChapterHref?.let { href ->
                        entry.href == href || href.endsWith(entry.href) || entry.href.endsWith(href) || entry.href.substringBefore('#') == href || href == entry.href.substringBefore('#')
                    } ?: false
                    ListItem(
                        headlineContent = { Text(entry.label, fontSize = (14 - entry.level).coerceAtLeast(12).sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.fillMaxWidth().clickable { onNavigate(entry.href) }.background(if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarkPanel(
    bookmarks: List<Bookmark>,
    onNavigate: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onClose: () -> Unit,
) {
    val dateFormat = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()) }
    var deleteTarget by remember { mutableStateOf<Bookmark?>(null) }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除书签") },
            text = { Text("确定要删除这个书签吗？") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget?.let { onDelete(it) }
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }

    Surface(Modifier.fillMaxHeight(), shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.bookmark_list_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.close)) }
            }
            HorizontalDivider()
            if (bookmarks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.bookmark_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(bookmarks, key = { b: Bookmark -> b.id }) { bookmark ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            onClick = { onNavigate(bookmark) },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = bookmark.chapterTitle ?: "第 ${bookmark.chapterIndex + 1} 章",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (bookmark.paragraphText != null) {
                                        Text(
                                            text = bookmark.paragraphText,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                    }
                                    Text(
                                        text = "${dateFormat.format(java.util.Date(bookmark.createdAt))} · 段落 ${bookmark.paragraphIndex + 1}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                IconButton(onClick = { deleteTarget = bookmark }) {
                                    Icon(Icons.Default.Delete, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
