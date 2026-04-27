package com.moreader.app.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.moreader.app.data.BookRepository
import com.moreader.app.data.models.*
import com.moreader.app.reader.EpubWebView
import com.moreader.app.reader.repositoryRef
import com.moreader.app.ui.components.TtsSettingsSheet
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

    // TTS highlight index
    val ttsHighlightIdx = state.ttsCurrentIdx

    // Translation result panel (kept as dialog, less frequent)
    if (state.showTranslationPanel) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissTranslationPanel() },
            title = { Text(when (state.translationMode) { "translate" -> androidx.compose.ui.res.stringResource(com.moreader.app.R.string.ai_translate_title); "explain" -> androidx.compose.ui.res.stringResource(com.moreader.app.R.string.explain_title); "analyze" -> androidx.compose.ui.res.stringResource(com.moreader.app.R.string.grammar_title); else -> androidx.compose.ui.res.stringResource(com.moreader.app.R.string.ai_translate_title) }) },
            text = {
                Column {
                    if (state.isTranslating) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp)); Text(androidx.compose.ui.res.stringResource(com.moreader.app.R.string.translating), fontSize = 14.sp)
                        }
                    }
                    Text(state.translationResult ?: "", fontSize = 14.sp, lineHeight = 22.sp)
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.dismissTranslationPanel() }) { Text(androidx.compose.ui.res.stringResource(com.moreader.app.R.string.close)) } },
        )
    }

    // Main layout
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.book?.title ?: androidx.compose.ui.res.stringResource(com.moreader.app.R.string.loading), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (state.currentChapterIndex < state.chapters.size)
                            Text("${state.currentChapterIndex + 1}/${state.chapters.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = androidx.compose.ui.res.stringResource(com.moreader.app.R.string.back)) } },
                actions = {
                    IconButton(onClick = { viewModel.toggleTocPanel() }) { Icon(Icons.Default.List, contentDescription = androidx.compose.ui.res.stringResource(com.moreader.app.R.string.table_of_contents)) }
                    IconButton(onClick = { viewModel.toggleTtsSettingsPanel() }) { Icon(Icons.Default.Settings, contentDescription = androidx.compose.ui.res.stringResource(com.moreader.app.R.string.tts_settings)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, titleContentColor = MaterialTheme.colorScheme.onSurface),
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                // Debug Log panel
                if (state.showTtsDebugLog) {
                    Surface(tonalElevation = 4.dp, color = Color(0xFF1A1A2E)) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).padding(8.dp).verticalScroll(rememberScrollState())) {
                            Text(text = state.ttsDebugLog.ifEmpty { androidx.compose.ui.res.stringResource(com.moreader.app.R.string.debug_no_log) },
                                fontSize = 10.sp, color = Color(0xFF00FF88),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, lineHeight = 14.sp)
                        }
                    }
                    Surface(color = Color(0xFF2A2A3E)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(androidx.compose.ui.res.stringResource(com.moreader.app.R.string.highlight_offset), fontSize = 11.sp, color = Color(0xFF888888))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { viewModel.decreaseHighlightOffset() }, modifier = Modifier.size(28.dp)) { Text("-", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                                Text("${state.ttsHighlightOffset}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
                                IconButton(onClick = { viewModel.increaseHighlightOffset() }, modifier = Modifier.size(28.dp)) { Text("+", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                            }
                        }
                    }
                }

                // Selection toolbar — floating above bottom bar, doesn't block system menu
                if (state.showSelectionMenu && state.selectedText != null && !state.isTtsPlaying && !state.isTtsPaused) {
                    Surface(
                        shadowElevation = 8.dp,
                        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { viewModel.dismissSelectionMenu(); viewModel.translate("translate") }) { Text(androidx.compose.ui.res.stringResource(com.moreader.app.R.string.ai_translate), fontSize = 12.sp) }
                            TextButton(onClick = { viewModel.dismissSelectionMenu(); viewModel.translate("explain") }) { Text(androidx.compose.ui.res.stringResource(com.moreader.app.R.string.explain), fontSize = 12.sp) }
                            TextButton(onClick = { viewModel.dismissSelectionMenu(); viewModel.translate("analyze") }) { Text(androidx.compose.ui.res.stringResource(com.moreader.app.R.string.grammar_analysis), fontSize = 12.sp) }
                            TextButton(onClick = { viewModel.dismissSelectionMenu(); viewModel.readSelection(state.selectedText!!) },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF059669))) { Text(androidx.compose.ui.res.stringResource(com.moreader.app.R.string.read_aloud), fontSize = 12.sp) }
                        }
                    }
                }

                ReaderBottomBar(
                    currentIndex = state.currentChapterIndex, totalChapters = state.chapters.size,
                    fontSize = state.fontSize,
                    isTtsPlaying = state.isTtsPlaying, isTtsPaused = state.isTtsPaused,
                    onPrev = { viewModel.prevChapter() }, onNext = { viewModel.nextChapter() },
                    onFontSizeChange = { viewModel.setFontSize(it) },
                    onPlayPause = { viewModel.togglePlayPause() },
                    onStop = { viewModel.ttsStop() },
                    onToggleDebug = { viewModel.toggleTtsDebugLog() },
                )
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
                        Text(androidx.compose.ui.res.stringResource(com.moreader.app.R.string.large_book_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
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
                EpubWebView(
                    htmlContent = state.currentHtml,
                    baseUrl = baseUrl,
                    bgColor = state.theme.bgColor,
                    textColor = state.theme.textColor,
                    fontScale = state.fontSize / 18f,
                    onTextSelected = { viewModel.onTextSelected(it) },
                    ttsHighlightIndex = ttsHighlightIdx,
                    modifier = Modifier.fillMaxSize().background(Color(android.graphics.Color.parseColor(state.theme.bgColor))),
                )
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
                    llmConfig = state.llmConfig,
                    currentTheme = state.theme,
                    onProviderChange = { viewModel.setTTSProvider(it) },
                    onSpeedChange = { viewModel.setTTSSpeed(it) },
                    onEdgeConfigChange = { ep, v -> viewModel.updateEdgeTTSConfig(ep, v) },
                    onAIVoiceConfigChange = { ep, key, m, v -> viewModel.updateAIVoiceConfig(ep, key, m, v) },
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
) {
    Surface(tonalElevation = 2.dp, shadowElevation = 4.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev, enabled = currentIndex > 0) { Icon(Icons.Default.ChevronLeft, contentDescription = androidx.compose.ui.res.stringResource(com.moreader.app.R.string.previous_chapter)) }

            IconButton(onClick = { onFontSizeChange(fontSize - 2) }) { Text("A-", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            Text("$fontSize", fontSize = 11.sp)
            IconButton(onClick = { onFontSizeChange(fontSize + 2) }) { Text("A+", fontSize = 14.sp, fontWeight = FontWeight.Bold) }

            Spacer(Modifier.weight(1f))

            if (isTtsPlaying || isTtsPaused) {
                IconButton(onClick = onPlayPause) {
                    Icon(if (isTtsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isTtsPlaying) androidx.compose.ui.res.stringResource(com.moreader.app.R.string.pause) else androidx.compose.ui.res.stringResource(com.moreader.app.R.string.resume),
                        tint = MaterialTheme.colorScheme.primary)
                }
            } else {
                IconButton(onClick = onPlayPause) { Icon(Icons.Default.VolumeUp, contentDescription = androidx.compose.ui.res.stringResource(com.moreader.app.R.string.play_chapter)) }
            }

            if (isTtsPlaying || isTtsPaused) {
                IconButton(onClick = onStop) { Icon(Icons.Default.Stop, contentDescription = androidx.compose.ui.res.stringResource(com.moreader.app.R.string.stop), tint = MaterialTheme.colorScheme.error) }
            }

            IconButton(onClick = onToggleDebug) { Icon(Icons.Default.BugReport, contentDescription = androidx.compose.ui.res.stringResource(com.moreader.app.R.string.debug_log), modifier = Modifier.size(20.dp)) }

            IconButton(onClick = onNext, enabled = currentIndex < totalChapters - 1) { Icon(Icons.Default.ChevronRight, contentDescription = androidx.compose.ui.res.stringResource(com.moreader.app.R.string.next_chapter)) }
        }
    }
}

@Composable
private fun TocDrawer(toc: List<TocEntry>, currentChapterHref: String?, onNavigate: (String) -> Unit, onClose: () -> Unit) {
    Surface(Modifier.fillMaxHeight(), shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(androidx.compose.ui.res.stringResource(com.moreader.app.R.string.table_of_contents), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = androidx.compose.ui.res.stringResource(com.moreader.app.R.string.close)) }
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
