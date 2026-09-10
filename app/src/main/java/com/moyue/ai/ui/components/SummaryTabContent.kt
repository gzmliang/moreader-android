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
        // Controls Header
        Surface(
            color = if (isEink) Color.White else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(8.dp)) else Modifier)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                // Row 1: Scope & Ratio & Cached Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Scope Selector
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = (selectedScope == "chapter"),
                            onClick = { selectedScope = "chapter" },
                            label = { Text(stringResource(R.string.ai_scope_chapter), fontSize = 12.sp) }
                        )
                        FilterChip(
                            selected = (selectedScope == "book"),
                            onClick = { selectedScope = "book" },
                            label = { Text(stringResource(R.string.ai_scope_book), fontSize = 12.sp) }
                        )
                    }

                    // Ratio Slider/Indicator
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${stringResource(R.string.ai_summary_ratio_label)}: ${ratio}%",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        // Quick ratio buttons
                        listOf(20, 30, 50).forEach { r ->
                            Text(
                                text = "${r}%",
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .background(
                                        if (ratio == r) (if (isEink) Color.Black else MaterialTheme.colorScheme.primary)
                                        else (if (isEink) Color.LightGray else MaterialTheme.colorScheme.surfaceVariant),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .clickable { ratio = r }
                                    .padding(horizontal = 5.dp, vertical = 2.dp),
                                color = if (ratio == r) Color.White else Color.Black
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Row 2: Display Mode & Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Display Mode Chips
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val modes = listOf(
                            "bilingual" to stringResource(R.string.ai_display_mode_bilingual),
                            "orig" to stringResource(R.string.ai_display_mode_original),
                            "target" to stringResource(R.string.ai_display_mode_target)
                        )
                        modes.forEach { (m, label) ->
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .background(
                                        if (displayMode == m) (if (isEink) Color.Black else MaterialTheme.colorScheme.primary)
                                        else (if (isEink) Color.White else MaterialTheme.colorScheme.surface),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .then(if (isEink && displayMode != m) Modifier.border(1.dp, Color.Black, RoundedCornerShape(4.dp)) else Modifier)
                                    .clickable { displayMode = m }
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                                color = if (displayMode == m) Color.White else (if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface)
                            )
                        }
                    }

                    // Listen & Save to Library
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (summaryResult != null) {
                            Text(
                                text = if (isPlayingAudio) stringResource(R.string.ai_stop_listen_btn) else stringResource(R.string.ai_play_listen_btn),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(
                                        if (isPlayingAudio) Color(0xFFEF4444) else (if (isEink) Color.White else Color(0xFF10B981)),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(4.dp)) else Modifier)
                                    .clickable {
                                        if (!isPlayingAudio) showAudioDialog = true
                                        else toggleAudio()
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                                color = if (isPlayingAudio) Color.White else (if (isEink) Color.Black else Color.White)
                            )

                            val badgeText = stringResource(R.string.ai_summary_badge_text)
                            val bookSuffix = stringResource(R.string.ai_summary_book_suffix)
                            val chapterSuffix = stringResource(R.string.ai_summary_chapter_suffix)
                            val saveSuccessMsg = stringResource(R.string.ai_save_to_library_success)
                            val saveFailedMsg = stringResource(R.string.ai_save_to_library_failed)

                            Text(
                                text = stringResource(R.string.ai_save_to_library_btn),
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .background(if (isEink) Color.White else MaterialTheme.colorScheme.secondary, RoundedCornerShape(4.dp))
                                    .then(if (isEink) Modifier.border(1.dp, Color.Black, RoundedCornerShape(4.dp)) else Modifier)
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
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                                color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSecondary
                            )
                        }
                    }
                }
            }
        }

        // Cache badge or status
        if (summaryResult != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.ai_cached_badge),
                    fontSize = 11.sp,
                    color = if (isEink) Color.Black else Color(0xFF10B981),
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = stringResource(R.string.ai_regenerate_btn),
                    fontSize = 11.sp,
                    color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary,
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

        // Content Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
        ) {
            when {
                isLoading -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.ai_generating),
                            fontSize = 14.sp,
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
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        if (result.title.isNotBlank()) {
                            item {
                                Text(
                                    text = result.title,
                                    fontSize = (textSizeSp + 4).sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isEink) Color.Black else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                            }
                        }

                        items(result.paragraphs) { p ->
                            ParagraphItem(
                                paragraph = p,
                                displayMode = displayMode,
                                textSizeSp = textSizeSp,
                                isEink = isEink
                            )
                            Spacer(modifier = Modifier.height(14.dp))
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
                        Spacer(modifier = Modifier.height(14.dp))
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
                            colors = if (isEink) ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White)
                            else ButtonDefaults.buttonColors()
                        ) {
                            Text(stringResource(R.string.ai_generate_btn))
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
                    Text("Cancel")
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isEink) Modifier
                    .border(1.dp, Color.Black, RoundedCornerShape(6.dp))
                    .padding(8.dp)
                else Modifier
            )
    ) {
        // Original text
        if ((displayMode == "bilingual" || displayMode == "orig") && paragraph.original.isNotBlank()) {
            Text(
                text = paragraph.original,
                fontSize = textSizeSp.sp,
                fontWeight = if (isEink) FontWeight.Bold else FontWeight.Medium,
                lineHeight = (textSizeSp * 1.55f).sp,
                color = if (isEink) Color.Black else MaterialTheme.colorScheme.onSurface
            )
        }

        // Translation text
        if ((displayMode == "bilingual" || displayMode == "target") && paragraph.translation.isNotBlank()) {
            if (displayMode == "bilingual") {
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = paragraph.translation,
                fontSize = (textSizeSp * 0.92f).sp,
                lineHeight = (textSizeSp * 1.5f).sp,
                color = if (isEink) Color.DarkGray else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
