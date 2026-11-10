package com.moyue.ui.components

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyue.app.data.models.LLMConfig
import com.moyue.app.data.models.TTSProviderType
import com.moyue.app.tts.EdgeTTSProvider
import com.moyue.app.tts.SystemTTSProvider
import com.moyue.app.tts.TTSListener
import com.moyue.app.tts.TTSProvider
import com.moyue.reader.PdfTextLayer
import com.moyue.app.translate.OnlineBilingualDictionary
import com.moyue.app.translate.TranslationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PDF「点哪读哪」结果卡片：原文 + 流式翻译 + 朗读。
 *
 * 复用 EPUB 阅读器那一套能力（TranslationService 流式 LLM 翻译、Edge/系统 TTS），
 * 不新造轮子；配置全部读现有 moreader_config，用户在设置里配一次，这里直接生效。
 */
@Composable
fun PdfLookupCard(
    hit: PdfTextLayer.Hit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var translation by remember(hit) { mutableStateOf("") }
    var translating by remember(hit) { mutableStateOf(false) }
    var error by remember(hit) { mutableStateOf<String?>(null) }
    var expanded by remember(hit) { mutableStateOf(false) }
    // 与 EPUB 划词一致：选中后先只弹菜单，翻译/词典/朗读都由用户点。
    // requestSeq = 0 表示「还没请求过」，LaunchedEffect 直接返回。
    var useAi by remember(hit) { mutableStateOf(false) }
    var useDict by remember(hit) { mutableStateOf(false) }
    var requestSeq by remember(hit) { mutableIntStateOf(0) }

    var tts by remember { mutableStateOf<TTSProvider?>(null) }
    var speakingText by remember { mutableStateOf<String?>(null) }
    var retried by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { tts?.stop() }
            runCatching { tts?.destroy() }
            tts = null
        }
    }

    LaunchedEffect(hit, requestSeq) {
        if (requestSeq == 0) {
            translating = false
            return@LaunchedEffect
        }
        translating = true
        translation = ""
        error = null

        if (useDict) {
            val targetLang = context.getSharedPreferences("moreader_config", Context.MODE_PRIVATE)
                .getString("llm_target_lang", "Chinese") ?: "Chinese"
            val r = withContext(Dispatchers.IO) {
                OnlineBilingualDictionary.lookup(context, hit.sentence, targetLang)
            }
            translating = false
            r.onSuccess { translation = it.formattedDisplay }
                .onFailure { error = it.message ?: "词典查询失败，可试试「翻译」" }
            return@LaunchedEffect
        }

        if (!useAi) {
            // 免费通道：Google gtx + MyMemory，不需要任何 Key
            val targetLang = context.getSharedPreferences("moreader_config", Context.MODE_PRIVATE)
                .getString("llm_target_lang", "Chinese") ?: "Chinese"
            val r = withContext(Dispatchers.IO) {
                OnlineBilingualDictionary.translateFree(hit.sentence, targetLang)
            }
            translating = false
            r.onSuccess { translation = it }
                .onFailure {
                    error = (it.message ?: "免费翻译暂时不可用") + "\n可点「AI 翻译」试试"
                }
            return@LaunchedEffect
        }

        // AI 通道：走用户配置的 LLM
        val cfg = readLlmConfig(context)
        if (cfg == null || cfg.apiKey.isBlank()) {
            translating = false
            error = "未配置 AI 翻译，请先到「设置 → AI 翻译」填写接口与密钥"
            return@LaunchedEffect
        }
        val sb = StringBuilder()
        val result = withContext(Dispatchers.IO) {
            TranslationService().translate(cfg, hit.sentence, "translate") { chunk ->
                sb.append(chunk)
                translation = sb.toString()
            }
        }
        translating = false
        translation = sb.toString()
        if (result.isFailure || translation.isBlank()) {
            error = result.exceptionOrNull()?.message ?: "AI 翻译失败，请检查网络或设置"
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        // 清洗不可见字符（PDF 文字层带零宽/软连字符时，微软会返回空音频→服务端 500）
        val cleaned = text
            .replace(Regex("[\\u0000-\\u001F\\u007F\\u00AD\\u200B-\\u200F\\u202A-\\u202E\\uFEFF]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isBlank()) return
        // Edge-TTS 对超长文本会报错，先截断（整段译文常见）
        val safeText = if (cleaned.length > 1200) cleaned.take(1200) else cleaned
        speakingText = text
        scope.launch {
            // 关键：按「要读的文本语言」选音色。
            // 若用固定中文音色读英文、或用英文音色读中文，Edge-TTS 会直接 500。
            val prefs = context.getSharedPreferences("moreader_config", Context.MODE_PRIVATE)
            val baseVoice = prefs.getString("edge_voice", "zh-CN-XiaoxiaoNeural") ?: "zh-CN-XiaoxiaoNeural"
            // 注意：不能依赖「tts 是否已创建」来决定要不要做语言匹配 ——
            // 首次朗读时 tts 为 null，那样就会直接用用户设置里的音色（可能是英文音色），
            // 读中文译文时 Edge-TTS 直接 500。这里一律按文本语言匹配。
            val engineType = runCatching {
                com.moyue.app.data.models.TTSProviderType.valueOf(
                    prefs.getString("flashcard_tts_provider", "edge_tts") ?: "edge_tts"
                )
            }.getOrDefault(com.moyue.app.data.models.TTSProviderType.EDGE_TTS)
            val targetVoice = if (engineType == com.moyue.app.data.models.TTSProviderType.EDGE_TTS) {
                com.moyue.tts.LanguageVoiceDetector.pickEdgeVoiceForText(safeText, baseVoice)
            } else baseVoice

            val needRebuild = tts == null ||
                (tts is EdgeTTSProvider && (tts as EdgeTTSProvider).voice != targetVoice)
            if (needRebuild) {
                runCatching { tts?.stop(); tts?.destroy() }
                tts = createDictProvider(context, targetVoice)
            }
            val provider = tts
            if (provider == null) {
                speakingText = null
                error = "语音引擎不可用，请到设置里选择朗读引擎"
                return@launch
            }
            withContext(Dispatchers.IO) {
                runCatching { provider.stop() }
                provider.speak(safeText, 1.0f, object : TTSListener {
                    override fun onStart() {}
                    override fun onDone() {
                        scope.launch { if (speakingText == text) speakingText = null }
                    }

                    override fun onError(message: String) {
                        // 500 多半是「音色与文本语言不匹配 / 文本含特殊字符」，
                        // 换一套备选音色再试一次，避免直接失败。
                        if (message.contains("500") || message.contains("No audio", ignoreCase = true)) {
                            val fallbackVoice = if (targetVoice.startsWith("en-")) {
                                "zh-CN-XiaoxiaoNeural"
                            } else {
                                "en-US-JennyNeural"
                            }
                            if (fallbackVoice != targetVoice && !retried) {
                                retried = true
                                android.util.Log.w("PdfLookupTTS", "retry with fallback voice=$fallbackVoice")
                                runCatching { tts?.stop(); tts?.destroy() }
                                tts = createDictProvider(context, fallbackVoice)
                                val p2 = tts
                                if (p2 != null) {
                                    // onError 回调本身已在线程池里，直接调用即可
                                    p2.speak(safeText, 1.0f, object : TTSListener {
                                        override fun onStart() {}
                                        override fun onDone() { scope.launch { speakingText = null } }
                                        override fun onError(m2: String) {
                                            scope.launch {
                                                speakingText = null
                                                error = "朗读失败：$m2\n（音色 $fallbackVoice）"
                                            }
                                        }
                                    })
                                    return
                                }
                            }
                        }
                        android.util.Log.w(
                            "PdfLookupTTS",
                            "speak failed: $message | len=${safeText.length} " +
                                "voice=$targetVoice endpoint=${com.moyue.util.SecureUrlHelper.secureEdgeEndpoint(context.getSharedPreferences("moreader_config", Context.MODE_PRIVATE).getString("edge_endpoint", null))}"
                        )
                        scope.launch {
                            speakingText = null
                            // 把关键参数带进提示，方便用户直接反馈排查
                            error = "朗读失败：$message\n（音色 $targetVoice）"
                        }
                    }
                })
            }
        }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_lookup_title),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_lookup_page, hit.pageIndex + 1),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.close), modifier = Modifier.size(18.dp))
                }
            }

            Text(
                text = hit.sentence,
                fontSize = 14.sp,
                maxLines = if (expanded) 12 else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
            ) {
                when {
                    requestSeq == 0 -> Text(
                        text = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_lookup_hint),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    translating -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_lookup_translating),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    error != null -> Text(
                        text = error!!,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> Text(text = translation, fontSize = 14.sp)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { speak(hit.sentence) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_lookup_read_source), fontSize = 13.sp)
                }
                TextButton(
                    onClick = { speak(translation) },
                    enabled = translation.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_lookup_read_translation), fontSize = 13.sp)
                }
                TextButton(
                    onClick = { useDict = false; useAi = false; requestSeq++ },
                    enabled = !translating,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        androidx.compose.ui.res.stringResource(com.moyue.app.R.string.translate_free),
                        fontSize = 13.sp,
                    )
                }
                TextButton(
                    onClick = { useDict = false; useAi = true; requestSeq++ },
                    enabled = !translating,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        androidx.compose.ui.res.stringResource(com.moyue.app.R.string.translate_ai),
                        fontSize = 13.sp,
                    )
                }
                TextButton(
                    onClick = { useDict = true; useAi = false; requestSeq++ },
                    enabled = !translating,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_lookup_dictionary),
                        fontSize = 13.sp,
                    )
                }
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(hit.sentence)) },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_lookup_copy_source),
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (hit.sentence.length > 60) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            androidx.compose.ui.res.stringResource(
                                if (expanded) com.moyue.app.R.string.pdf_lookup_collapse
                                else com.moyue.app.R.string.pdf_lookup_expand
                            ),
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}

private fun readLlmConfig(context: Context): LLMConfig? {
    val prefs = context.getSharedPreferences("moreader_config", Context.MODE_PRIVATE)
    val endpoint = prefs.getString("llm_endpoint", "") ?: ""
    val model = prefs.getString("llm_model", "") ?: ""
    if (endpoint.isBlank() || model.isBlank()) return null
    return LLMConfig(
        provider = prefs.getString("llm_provider", "custom") ?: "custom",
        apiKey = prefs.getString("llm_apikey", "") ?: "",
        endpoint = endpoint,
        model = model,
        targetLang = prefs.getString("llm_target_lang", "Chinese") ?: "Chinese",
    )
}

private fun createDictProvider(context: Context, voiceOverride: String? = null): TTSProvider? {
    val prefs = context.getSharedPreferences("moreader_config", Context.MODE_PRIVATE)
    val type = runCatching {
        TTSProviderType.valueOf(prefs.getString("flashcard_tts_provider", "edge_tts") ?: "edge_tts")
    }.getOrDefault(TTSProviderType.EDGE_TTS)

    return when (type) {
        TTSProviderType.EDGE_TTS -> {
            val endpoint = com.moyue.util.SecureUrlHelper.secureEdgeEndpoint(
                prefs.getString("edge_endpoint", null)
            )
            val voice = voiceOverride
                ?: (prefs.getString("edge_voice", "zh-CN-XiaoxiaoNeural") ?: "zh-CN-XiaoxiaoNeural")
            EdgeTTSProvider(endpoint, voice)
        }

        TTSProviderType.SYSTEM -> SystemTTSProvider(context.applicationContext)

        TTSProviderType.AI_VOICE -> com.moyue.app.tts.AIVoiceTTSProvider(
            prefs.getString("ai_endpoint", "") ?: "",
            prefs.getString("ai_apikey", "") ?: "",
            prefs.getString("ai_model", "") ?: "",
            prefs.getString("ai_voice_id", "") ?: "",
        )

        TTSProviderType.CUSTOM_TTS -> com.moyue.app.tts.CustomTTSProvider(
            prefs.getString("custom_tts_endpoint", "") ?: "",
            prefs.getString("custom_tts_apikey", "") ?: "",
            prefs.getString("custom_tts_model", "") ?: "",
            prefs.getString("custom_tts_voice", "") ?: "",
        )
    }
}
