package com.moyue.ai.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
    edgeTTS: EdgeTTSProvider?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedScope by remember { mutableStateOf("chapter") } // "chapter" or "book"
    var ratio by remember { mutableIntStateOf(30) }
    var displayMode by remember { mutableStateOf("bilingual") } // "bilingual", "orig", "target"

    var summaryResult by remember {
        mutableStateOf(repository.getSummary(bookId, chapterIndex, selectedScope, ratio))
    }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Audio playback state
    var isPlayingAudio by remember { mutableStateOf(false) }
    var audioMode by remember { mutableStateOf("orig") } // "orig", "trans", "both"
    var showAudioDialog by remember { mutableStateOf(false) }

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
                "orig" -> if (p.original.isNotBlank()) textToRead.append(p.original).append("\n\n")
                "trans" -> if (p.translation.isNotBlank()) textToRead.append(p.translation).append("\n\n")
                "both" -> {
                    if (p.original.isNotBlank()) textToRead.append(p.original).append("\n")
                    if (p.translation.isNotBlank()) textToRead.append(p.translation).append("\n\n")
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

    Column(modifier = Modifier.fillMaxSize()) {
        // 精简大气 Controls Header
        Surface(
            color = if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(12.dp)) else Modifier)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Row 1: Scope & Ratio & Cached Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Scope Selector (紧凑切换)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "chapter" to stringResource(R.string.ai_scope_chapter),
                            "book" to stringResource(R.string.ai_scope_book)
                        ).forEach { (sKey, sLabel) ->
                            val isSelected = (selectedScope == sKey)
                            Text(
                                text = sLabel,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .background(
                                        if (isSelected) (if (isEink) Color.Black else MaterialTheme.colorScheme.primary)
                                        else (if (isEink) Color.White else MaterialTheme.colorScheme.surface),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .then(if (isEink && !isSelected) Modifier.border(1.dp, Color.Black, RoundedCornerShape(6.dp)) else Modifier)
                                    .clickable { selectedScope = sKey }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                color = if (isSelected) Color.White else (if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface)
                            )
                        }
                    }

                    // Ratio Selector (扁平精致胶囊)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${ratio}%",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        listOf(20, 30, 50).forEach { r ->
                            val isSelected = (ratio == r)
                            Text(
                                text = "${r}%",
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .padding(horizontal = 2.dp)
                                    .background(
                                        if (isSelected) (if (isEink) Color.Black else MaterialTheme.colorScheme.primary)
                                        else (if (isEink) Color.LightGray.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .clickable { ratio = r }
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                                color = if (isSelected) Color.White else Color.Black
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Row 2: Display Mode & Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Display Mode Chips
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val modes = listOf(
                            "bilingual" to stringResource(R.string.ai_display_mode_bilingual),
                            "orig" to stringResource(R.string.ai_display_mode_original),
                            "target" to stringResource(R.string.ai_display_mode_target)
                        )
                        modes.forEach { (m, label) ->
                            val isSelected = (displayMode == m)
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier
                                    .background(
                                        if (isSelected) (if (isEink) Color.Black else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                        else (if (isEink) Color.White else MaterialTheme.colorScheme.surface),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .then(if (isSelected && !isEink) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)) else Modifier)
                                    .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(6.dp)) else Modifier)
                                    .clickable { displayMode = m }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                color = if (isSelected) (if (isEink) Color.White else MaterialTheme.colorScheme.primary)
                                        else (if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface)
                            )
                        }
                    }

                    // Listen & Save to Bookshelf
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (summaryResult != null) {
                            // Listen button
                            Text(
                                text = if (isPlayingAudio) stringResource(R.string.ai_stop_listen_btn) else stringResource(R.string.ai_play_listen_btn),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(
                                        if (isPlayingAudio) Color(0xFFEF4444) else (if (isEink) Color.White else Color(0xFF10B981)),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(6.dp)) else Modifier)
                                    .clickable {
                                        if (!isPlayingAudio) showAudioDialog = true
                                        else toggleAudio()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                color = if (isPlayingAudio) Color.White else (if (isEink) Color.Black else Color.White)
                            )

                            // Save to bookshelf button
                            val badgeText = stringResource(R.string.ai_summary_badge_text)
                            val bookSuffix = stringResource(R.string.ai_summary_book_suffix)
                            val chapterSuffix = stringResource(R.string.ai_summary_chapter_suffix)
                            val saveSuccessMsg = stringResource(R.string.ai_save_to_library_success)
                            val saveFailedMsg = stringResource(R.string.ai_save_to_library_failed)

                            Text(
                                text = stringResource(R.string.ai_save_to_library_btn),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(if (isEink) Color.White else MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                                    .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(6.dp)) else Modifier)
                                    .clickable {
                                        scope.launch {
                                            val res = SummaryBookSaver.saveSummaryAsBook(
                                                context = context,
                                                bookDao = bookDao,
                                                summary = summaryResult!!,
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
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                color = if (isEink) Color.Black else MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }

        // Cache badge or regenerate bar
        if (summaryResult != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚡ " + stringResource(R.string.ai_cached_badge),
                    fontSize = 11.sp,
                    color = if (isEink) Color.Black else Color(0xFF10B981),
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = stringResource(R.string.ai_regenerate_btn),
                    fontSize = 11.sp,
                    color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable {
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
                    }
                )
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
