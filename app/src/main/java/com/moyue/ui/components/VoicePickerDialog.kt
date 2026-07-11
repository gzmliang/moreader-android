package com.moyue.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Voice picker dialog with search, language tabs, and preview playback.
 * Groups voices by language category and supports 1-tap preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePickerDialog(
    endpoint: String,
    currentVoiceId: String,
    onVoiceSelected: (voiceId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- State ----
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }
    var previewingVoiceId by remember { mutableStateOf<String?>(null) }
    var mediaPlayerRef by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    val previewClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    // Recent voices (stored in prefs or just use current as recent for now)
    val recentVoices = remember { mutableStateListOf(currentVoiceId) }

    // ---- Group voices by language category ----
    val voiceCategories = remember {
        mapOf(
            0 to "common",   // 常用
            1 to listOf("zh"),     // 中文
            2 to listOf("en"),     // English
            3 to listOf("ja"),     // 日本語
            4 to listOf("ko"),     // 한국어
            5 to emptyList<String>(),      // 其他
        )
    }

    // Filtered + grouped voices
    val filtered = remember(searchQuery, selectedTab) {
        EDGE_VOICES.filter { voice ->
            val matchesSearch = searchQuery.isBlank() ||
                    voice.displayName(context).contains(searchQuery, ignoreCase = true) ||
                    voice.id.contains(searchQuery, ignoreCase = true)
            if (!matchesSearch) return@filter false

            if (searchQuery.isNotBlank()) return@filter true // Show all matches when searching

            when (selectedTab) {
                0 -> recentVoices.contains(voice.id) // 常用
                1 -> voice.id.startsWith("zh-") // 中文
                2 -> voice.id.startsWith("en-") // English
                3 -> voice.id.startsWith("ja-") // 日本語
                4 -> voice.id.startsWith("ko-") // 한국어
                else -> voice.id !in listOf("zh", "en", "ja", "ko").flatMap { p ->
                    EDGE_VOICES.filter { it.id.startsWith("$p-") }.map { it.id }
                }
            }
        }
    }

    // ---- Preview function ----
    fun stopPreview() {
        mediaPlayerRef?.let { mp ->
            try { mp.stop() } catch (_: Exception) {}
            try { mp.release() } catch (_: Exception) {}
        }
        mediaPlayerRef = null
        previewingVoiceId = null
    }

    fun playPreview(voiceId: String) {
        if (previewingVoiceId != null) {
            stopPreview()
            return
        }
        previewingVoiceId = voiceId
        scope.launch {
            try {
                val previewText = when {
                    voiceId.startsWith("zh-") ->
                        context.getString(com.moyue.app.R.string.voice_picker_preview_text_chinese)
                    voiceId.startsWith("ja-") ->
                        context.getString(com.moyue.app.R.string.voice_picker_preview_text_japanese)
                    voiceId.startsWith("ko-") ->
                        context.getString(com.moyue.app.R.string.voice_picker_preview_text_korean)
                    else ->
                        context.getString(com.moyue.app.R.string.voice_picker_preview_text_english)
                }
                val json = JSONObject().apply {
                    put("text", previewText)
                    put("voice", voiceId)
                    put("rate", "+0%")
                    put("pitch", "+0Hz")
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${endpoint.removeSuffix("/")}/tts")
                    .post(body)
                    .build()
                val response = withContext(Dispatchers.IO) {
                    previewClient.newCall(request).execute()
                }
                if (response.isSuccessful) {
                    val audioBytes = response.body?.bytes()
                    if (audioBytes != null && audioBytes.isNotEmpty()) {
                        val tempFile = java.io.File.createTempFile("voice_preview_", ".mp3")
                        tempFile.writeBytes(audioBytes)
                        val mp = android.media.MediaPlayer().apply {
                            setDataSource(tempFile.absolutePath)
                            setOnCompletionListener {
                                release()
                                tempFile.delete()
                                if (mediaPlayerRef === this) mediaPlayerRef = null
                                previewingVoiceId = null
                            }
                            setOnErrorListener { mp2, _, _ ->
                                mp2.release()
                                tempFile.delete()
                                if (mediaPlayerRef === mp2) mediaPlayerRef = null
                                previewingVoiceId = null
                                true
                            }
                            prepare()
                            start()
                        }
                        mediaPlayerRef = mp
                    } else {
                        previewingVoiceId = null
                    }
                } else {
                    previewingVoiceId = null
                }
            } catch (e: Exception) {
                previewingVoiceId = null
            }
        }
    }

    // 停止预览并释放资源（Dialog 关闭时调用）
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayerRef?.let { mp ->
                try { mp.stop() } catch (_: Exception) {}
                try { mp.release() } catch (_: Exception) {}
            }
            mediaPlayerRef = null
        }
    }

    // ---- Dialog ----
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.82f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Header: title + close ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        context.getString(com.moyue.app.R.string.voice_picker_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${filtered.size} ${context.getString(com.moyue.app.R.string.voice_female).firstOrNull()?.let { "/" + it }?.removeSuffix("/") ?: ""}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                // ── Search bar ──
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it; if (it.isNotBlank()) selectedTab = 0 },
                    placeholder = {
                        Text(
                            context.getString(com.moyue.app.R.string.voice_picker_search),
                            fontSize = 13.sp,
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                    ),
                )

                Spacer(Modifier.height(4.dp))

                // ── Language tabs (hidden when searching) ──
                if (searchQuery.isBlank()) {
                    ScrollableTabRow(
                        selectedTabIndex = selectedTab,
                        modifier = Modifier.fillMaxWidth(),
                        edgePadding = 12.dp,
                        divider = {},
                        containerColor = Color.Transparent,
                    ) {
                        val tabs = listOf(
                            com.moyue.app.R.string.voice_picker_tab_common,
                            com.moyue.app.R.string.voice_picker_tab_chinese,
                            com.moyue.app.R.string.voice_picker_tab_english,
                            com.moyue.app.R.string.voice_picker_tab_japanese,
                            com.moyue.app.R.string.voice_picker_tab_korean,
                            com.moyue.app.R.string.voice_picker_tab_other,
                        )
                        tabs.forEachIndexed { index, resId ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = {
                                    Text(
                                        context.getString(resId),
                                        fontSize = 12.sp,
                                        fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                },
                                selectedContentColor = MaterialTheme.colorScheme.primary,
                                unselectedContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            )
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
                    )
                }

                // ── Voice list ──
                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            context.getString(com.moyue.app.R.string.voice_picker_empty),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        items(filtered, key = { it.id }) { voice ->
                            val isSelected = voice.id == currentVoiceId
                            val isPreviewing = voice.id == previewingVoiceId
                            val nameStr = voice.displayName(context)
                            val localeStr = voice.locale.getDisplayName(Locale.getDefault())

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onVoiceSelected(voice.id); onDismiss() },
                                color = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                else Color.Transparent,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Gender icon
                                    Text(
                                        when (voice.gender) {
                                            "female" -> "♀"
                                            "male" -> "♂"
                                            else -> "·"
                                        },
                                        fontSize = 14.sp,
                                        color = when (voice.gender) {
                                            "female" -> Color(0xFFE91E63)
                                            "male" -> Color(0xFF2196F3)
                                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                        },
                                        modifier = Modifier.width(18.dp),
                                    )

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            nameStr,
                                            fontSize = 14.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            localeStr,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                        )
                                    }

                                    // Preview button
                                    FilledTonalIconButton(
                                        onClick = { playPreview(voice.id) },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        if (isPreviewing) {
                                            Icon(
                                                Icons.Default.Stop,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        } else {
                                            Icon(
                                                Icons.Default.PlayArrow,
                                                contentDescription = context.getString(com.moyue.app.R.string.voice_picker_preview),
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }

                                    Spacer(Modifier.width(4.dp))

                                    // Checkmark
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    } else {
                                        Spacer(Modifier.width(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
