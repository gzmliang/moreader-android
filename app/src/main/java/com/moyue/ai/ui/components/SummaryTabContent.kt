package com.moyue.ai.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyue.ai.data.AiCacheRepository
import com.moyue.ai.model.AiConfig
import com.moyue.ai.model.AiSummaryResult
import com.moyue.ai.model.SummaryParagraph
import com.moyue.ai.service.AiPromptBuilder
import com.moyue.ai.service.LlmClient
import com.moyue.ai.service.SummaryBookSaver
import com.moyue.app.R
import com.moyue.app.data.BookDao
import com.moyue.app.tts.EdgeTTSProvider
import kotlinx.coroutines.launch

@Composable
fun SummaryTabContent(
    bookId: String,
    bookTitle: String,
    chapterIndex: Int,
    chapterTitle: String,
    chapterText: String,
    repository: AiCacheRepository,
    config: AiConfig,
    isEink: Boolean,
    textSizeSp: Float,
    bookDao: BookDao,
    edgeTTS: EdgeTTSProvider?,
    displayMode: String = "bilingual",
    onDisplayModeChange: (String) -> Unit = {},
    onHasResultChange: (Boolean) -> Unit = {},
    onAudioPlayingChange: (Boolean) -> Unit = {},
    onRegisterAudioAction: (() -> Unit) -> Unit = {},
    onRegisterSaveAction: (() -> Unit) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedScope by remember { mutableStateOf("chapter") } // "chapter" or "book"
    var ratio by remember { mutableIntStateOf(30) }

    var summaryResult by remember {
        mutableStateOf(repository.getSummary(bookId, chapterIndex, selectedScope, ratio))
    }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Audio playback state
    var isPlayingAudio by remember { mutableStateOf(false) }
    var audioMode by remember { mutableStateOf("orig") } // "orig", "trans", "both"
    var showAudioDialog by remember { mutableStateOf(false) }

    // Synchronize result and audio states to parent top bar
    LaunchedEffect(summaryResult) {
        onHasResultChange(summaryResult != null)
    }
    LaunchedEffect(isPlayingAudio) {
        onAudioPlayingChange(isPlayingAudio)
    }
    DisposableEffect(Unit) {
        onDispose {
            if (isPlayingAudio) {
                edgeTTS?.stop()
                isPlayingAudio = false
            }
        }
    }

    // When parameters change, reload cache
    LaunchedEffect(bookId, chapterIndex, selectedScope, ratio) {
        summaryResult = repository.getSummary(bookId, chapterIndex, selectedScope, ratio)
    }

    // Audio Playback Handler
    fun toggleAudio() {
        if (isPlayingAudio) {
            edgeTTS?.stop()
            isPlayingAudio = false
            return
        }

        val paragraphs = summaryResult?.paragraphs ?: return
        if (paragraphs.isEmpty()) return

        val textToRead = StringBuilder()
        for (p in paragraphs) {
            when (audioMode) {
                "orig" -> if (!p.original.isNullOrBlank()) textToRead.append(p.original).append("\n\n")
                "trans" -> if (!p.translation.isNullOrBlank()) textToRead.append(p.translation).append("\n\n")
                "both" -> {
                    if (!p.original.isNullOrBlank()) textToRead.append(p.original).append("\n")
                    if (!p.translation.isNullOrBlank()) textToRead.append(p.translation).append("\n\n")
                }
            }
        }

        if (textToRead.isNotBlank()) {
            isPlayingAudio = true
            edgeTTS?.speak(textToRead.toString(), 1.0f, object : com.moyue.app.tts.TTSListener {
                override fun onStart() {
                    isPlayingAudio = true
                }
                override fun onDone() {
                    isPlayingAudio = false
                }
                override fun onError(message: String) {
                    isPlayingAudio = false
                }
            })
        }
    }

    // Register top bar actions for Audio and Save to Library
    val badgeText = stringResource(R.string.ai_summary_badge_text)
    val bookSuffix = stringResource(R.string.ai_summary_book_suffix)
    val chapterSuffix = stringResource(R.string.ai_summary_chapter_suffix)
    val saveSuccessMsg = stringResource(R.string.ai_save_to_library_success)
    val saveFailedMsg = stringResource(R.string.ai_save_to_library_failed)

    LaunchedEffect(summaryResult, isPlayingAudio, audioMode, selectedScope) {
        onRegisterAudioAction {
            if (isPlayingAudio) {
                edgeTTS?.stop()
                isPlayingAudio = false
            } else {
                showAudioDialog = true
            }
        }
        onRegisterSaveAction {
            val resToSave = summaryResult ?: return@onRegisterSaveAction
            scope.launch {
                val res = SummaryBookSaver.saveSummaryAsBook(
                    context = context,
                    bookDao = bookDao,
                    summary = resToSave,
                    sourceBookTitle = bookTitle,
                    badgeText = badgeText,
                    suffixText = if (selectedScope == "book") bookSuffix else chapterSuffix
                )
                res.fold(
                    onSuccess = { Toast.makeText(context, saveSuccessMsg.format(it.title), Toast.LENGTH_SHORT).show() },
                    onFailure = { Toast.makeText(context, saveFailedMsg.format(it.localizedMessage), Toast.LENGTH_SHORT).show() }
                )
            }
        }
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
            // Dropdown Capsules
            Row(
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

                // Ratio Capsule: [ 30% ▾ ]
                var ratioMenuExpanded by remember { mutableStateOf(false) }
                Box {
                    Surface(
                        color = if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .clickable { ratioMenuExpanded = true }
                            .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(16.dp)) else Modifier)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        ) {
                            Text(
                                text = "${ratio}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
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
                        expanded = ratioMenuExpanded,
                        onDismissRequest = { ratioMenuExpanded = false }
                    ) {
                        listOf(20, 30, 50).forEach { r ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "${r}%",
                                        fontSize = 13.sp,
                                        fontWeight = if (ratio == r) FontWeight.Bold else FontWeight.Normal,
                                        color = if (ratio == r) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    ratio = r
                                    ratioMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                // Display Mode Capsule: [ 双语 ▾ ] / [ 原文 ▾ ] / [ 译文 ▾ ]
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
                            val modeLabel = when (displayMode) {
                                "orig" -> stringResource(R.string.ai_display_mode_orig_short)
                                "target" -> stringResource(R.string.ai_display_mode_trans_short)
                                else -> stringResource(R.string.ai_display_mode_bilingual_short)
                            }
                            Text(
                                text = modeLabel,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
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
                                    modeMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Right side: ⚡已缓存 & 🔄重新生成
            if (summaryResult != null) {
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
                        onClick = {
                            isLoading = true
                            errorMessage = null
                            scope.launch {
                                val (sys, usr) = AiPromptBuilder.buildSummaryPrompt(
                                    config = config,
                                    title = if (selectedScope == "book") bookTitle else "$bookTitle - $chapterTitle",
                                    text = chapterText,
                                    ratio = ratio,
                                    scope = selectedScope
                                )
                                val res = LlmClient().chatCompletion(config, sys, usr, responseJson = true)
                                isLoading = false
                                res.fold(
                                    onSuccess = { json ->
                                        val parsed = AiPromptBuilder.parseSummaryResponse(json, bookId, chapterIndex, selectedScope, ratio)
                                        repository.saveSummary(parsed)
                                        summaryResult = parsed
                                    },
                                    onFailure = { errorMessage = it.localizedMessage }
                                )
                            }
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.ai_regenerate_btn),
                            tint = if (isEink) Color.Black else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // Content Area (全屏呼吸感正文区域)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            when {
                isLoading -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 3.dp,
                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.ai_generating),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
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
                    }
                }
                summaryResult != null -> {
                    val result = summaryResult!!
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp)
                    ) {
                        if (!result.title.isNullOrBlank()) {
                            item {
                                Surface(
                                    color = if (isEink) Color.Transparent else MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 14.dp)
                                ) {
                                    Text(
                                        text = result.title ?: "",
                                        fontSize = (textSizeSp + 4).sp,
                                        fontWeight = FontWeight.Bold,
                                        lineHeight = (textSizeSp * 1.5f + 4).sp,
                                        color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        }

                        items(result.paragraphs ?: emptyList()) { p ->
                            ParagraphItem(
                                paragraph = p,
                                displayMode = displayMode,
                                textSizeSp = textSizeSp,
                                isEink = isEink
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.ai_empty_hint),
                            fontSize = 14.sp,
                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                isLoading = true
                                errorMessage = null
                                scope.launch {
                                    val (sys, usr) = AiPromptBuilder.buildSummaryPrompt(
                                        config = config,
                                        title = if (selectedScope == "book") bookTitle else "$bookTitle - $chapterTitle",
                                        text = chapterText,
                                        ratio = ratio,
                                        scope = selectedScope
                                    )
                                    val res = LlmClient().chatCompletion(config, sys, usr, responseJson = true)
                                    isLoading = false
                                    res.fold(
                                        onSuccess = { json ->
                                            val parsed = AiPromptBuilder.parseSummaryResponse(json, bookId, chapterIndex, selectedScope, ratio)
                                            repository.saveSummary(parsed)
                                            summaryResult = parsed
                                        },
                                        onFailure = { errorMessage = it.localizedMessage }
                                    )
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = if (isEink) ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White)
                            else ButtonDefaults.buttonColors()
                        ) {
                            Text(stringResource(R.string.ai_generate_btn), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Audio Mode Picker Dialog
    if (showAudioDialog) {
        AlertDialog(
            onDismissRequest = { showAudioDialog = false },
            title = { Text(stringResource(R.string.ai_listen_mode_title)) },
            text = {
                Column {
                    listOf(
                        "orig" to stringResource(R.string.ai_listen_mode_orig),
                        "trans" to stringResource(R.string.ai_listen_mode_trans),
                        "both" to stringResource(R.string.ai_listen_mode_both)
                    ).forEach { (modeKey, modeTitle) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { audioMode = modeKey }
                                .padding(vertical = 8.dp)
                        ) {
                            RadioButton(
                                selected = (audioMode == modeKey),
                                onClick = { audioMode = modeKey }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(modeTitle, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showAudioDialog = false
                    toggleAudio()
                }) {
                    Text(stringResource(R.string.ai_play_listen_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAudioDialog = false }) {
                    Text(stringResource(com.moyue.app.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ParagraphItem(
    paragraph: SummaryParagraph,
    displayMode: String,
    textSizeSp: Float,
    isEink: Boolean
) {
    Surface(
        color = if (isEink) Color.White else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        shadowElevation = if (isEink) 0.dp else 0.5.dp,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Original text
            if ((displayMode == "bilingual" || displayMode == "orig") && !paragraph.original.isNullOrBlank()) {
                Text(
                    text = paragraph.original ?: "",
                    fontSize = textSizeSp.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = (textSizeSp * 1.6f).sp,
                    color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
                )
            }

            // Translation text
            if ((displayMode == "bilingual" || displayMode == "target") && !paragraph.translation.isNullOrBlank()) {
                if (displayMode == "bilingual" && !paragraph.original.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = if (isEink) Color.LightGray else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = paragraph.translation ?: "",
                    fontSize = (textSizeSp * 0.94f).sp,
                    fontWeight = FontWeight.Normal,
                    lineHeight = (textSizeSp * 1.55f).sp,
                    color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
