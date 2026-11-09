package com.moyue.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
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
    var translating by remember(hit) { mutableStateOf(true) }
    var error by remember(hit) { mutableStateOf<String?>(null) }
    var expanded by remember(hit) { mutableStateOf(false) }

    var tts by remember { mutableStateOf<TTSProvider?>(null) }
    var speakingText by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { tts?.stop() }
            runCatching { tts?.destroy() }
            tts = null
        }
    }

    LaunchedEffect(hit) {
        val cfg = readLlmConfig(context)
        if (cfg == null || cfg.apiKey.isBlank()) {
            translating = false
            error = "未配置翻译引擎，请先到「设置 → AI 翻译」填写接口与密钥"
            return@LaunchedEffect
        }
        translating = true
        translation = ""
        error = null
        val sb = StringBuilder()
        val result = withContext(Dispatchers.IO) {
            TranslationService().translate(cfg, hit.sentence, "translate") { chunk ->
                sb.append(chunk)
                translation = sb.toString()
            }
        }
        translating = false
        translation = sb.toString()
        val msg = result.exceptionOrNull()?.message
        if (result.isFailure || translation.isBlank()) {
            error = msg ?: "翻译失败，请检查网络或 AI 翻译设置"
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        speakingText = text
        scope.launch {
            val provider = tts ?: createDictProvider(context).also { tts = it }
            if (provider == null) {
                speakingText = null
                error = "语音引擎不可用，请到设置里选择朗读引擎"
                return@launch
            }
            withContext(Dispatchers.IO) {
                runCatching { provider.stop() }
                provider.speak(text, 1.0f, object : TTSListener {
                    override fun onStart() {}
                    override fun onDone() {
                        scope.launch { if (speakingText == text) speakingText = null }
                    }

                    override fun onError(message: String) {
                        scope.launch {
                            speakingText = null
                            error = "朗读失败：$message"
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
                    text = "点哪读哪",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "第 ${hit.pageIndex + 1} 页",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp))
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
                    translating -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("翻译中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { speak(hit.sentence) }) {
                    Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("读原文", fontSize = 13.sp)
                }
                TextButton(onClick = { speak(translation) }, enabled = translation.isNotBlank()) {
                    Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("读译文", fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(hit.sentence)) },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制原文",
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (hit.sentence.length > 60) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "收起" else "全文", fontSize = 13.sp)
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

private fun createDictProvider(context: Context): TTSProvider? {
    val prefs = context.getSharedPreferences("moreader_config", Context.MODE_PRIVATE)
    val type = runCatching {
        TTSProviderType.valueOf(prefs.getString("flashcard_tts_provider", "edge_tts") ?: "edge_tts")
    }.getOrDefault(TTSProviderType.EDGE_TTS)

    return when (type) {
        TTSProviderType.EDGE_TTS -> {
            val endpoint = com.moyue.util.SecureUrlHelper.secureEdgeEndpoint(
                prefs.getString("edge_endpoint", null)
            )
            val voice = prefs.getString("edge_voice", "zh-CN-XiaoxiaoNeural") ?: "zh-CN-XiaoxiaoNeural"
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
