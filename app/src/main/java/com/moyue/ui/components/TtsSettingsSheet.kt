package com.moyue.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyue.app.data.models.LLMConfig
import com.moyue.app.data.models.ReaderTheme
import com.moyue.app.data.models.TTSProviderType
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

// Common voice presets
private val AI_VOICE_MODELS = listOf(
    "fnlp/MOSS-TTSD-v0.5" to com.moyue.app.R.string.model_moss,
    "FunAudioLLM/CosyVoice2-0.5B" to com.moyue.app.R.string.model_cosyvoice,
    "tts-1" to com.moyue.app.R.string.model_tts1,
    "tts-1-hd" to com.moyue.app.R.string.model_tts1_hd,
)

private fun aiVoicesForModel(model: String): List<Pair<String, String>> {
    val names = listOf("anna", "alex", "bella", "benjamin", "charles", "claire", "david", "diana")
    return names.map { name ->
        val genderLabel = when (name) {
            "anna", "bella", "claire", "diana" -> "♀"
            else -> "♂"
        }
        "$model:$name" to "${name.replaceFirstChar { it.uppercase() }} $genderLabel"
    }
}

// -- Collapsible section header --
@Composable
private fun SectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit, help: (@Composable () -> Unit)? = null) {
    Surface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            Spacer(Modifier.width(4.dp))
            Text(title, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.weight(1f))
            help?.invoke()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TtsSettingsSheet(
    currentProvider: TTSProviderType,
    ttsSpeed: Float,
    edgeEndpoint: String,
    edgeVoice: String,
    aiEndpoint: String,
    aiApiKey: String,
    aiModel: String,
    aiVoice: String,
    // Custom TTS (OpenAI-compatible)
    customEndpoint: String = "http://192.168.199.101:18083",
    customApiKey: String = "dummy",
    customModel: String = "moss-tts-nano",
    customVoice: String = "Lingyu",
    llmConfig: LLMConfig,
    // Local AI
    translateEngine: com.moyue.app.data.models.TranslateEngine = com.moyue.app.data.models.TranslateEngine.CLOUD,
    localAiModelName: String = "",
    localAiGpuLayers: Int = 0,
    currentTheme: ReaderTheme = ReaderTheme.LIGHT,
    onProviderChange: (TTSProviderType) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onEdgeConfigChange: (endpoint: String, voice: String) -> Unit,
    onAIVoiceConfigChange: (endpoint: String, apiKey: String, model: String, voice: String) -> Unit,
    onCustomTTSConfigChange: (endpoint: String, apiKey: String, model: String, voice: String) -> Unit,
    onLLMConfigChange: (LLMConfig) -> Unit,
    onTranslateEngineChange: (com.moyue.app.data.models.TranslateEngine) -> Unit = {},
    onLocalAiModelSelect: (android.net.Uri) -> Unit = {},
    onLocalAiModelUnload: () -> Unit = {},
    getLocalAiLogs: () -> String = { "" },
    clearLocalAiLogs: () -> Unit = {},
    onGpuLayersChange: (Int) -> Unit = {},
    onThemeChange: (ReaderTheme) -> Unit = {},
    onRecordingClick: () -> Unit = {},
    onBrowseRecordingsClick: () -> Unit = {},
    onClose: () -> Unit,
) {
    // -- Collapsible state (AI Translate & Local AI only) --
    var showLLMConfig by remember { mutableStateOf(false) }
    var showLocalAiConfig by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(max = 580.dp),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp).verticalScroll(rememberScrollState())) {
            // === Header ===
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_settings),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.close),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // === Engine + Speed (compact) ===
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_engine),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(4.dp))
                var showTtsHelp by remember { mutableStateOf(false) }
                TextButton(
                    onClick = { showTtsHelp = true },
                    contentPadding = PaddingValues(4.dp),
                ) {
                    Text("?", fontSize = 12.sp, color = Color(0xFFE53935))
                }
                if (showTtsHelp) {
                    HelpDialog(
                        title = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_btn_tts),
                        onDismiss = { showTtsHelp = false },
                    ) {
                        HelpText(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_tts_intro))
                        HelpSection(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_tts_edge_title))
                        HelpBullet(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_tts_edge_bullet1))
                        HelpBullet(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_tts_edge_bullet2))
                        HelpBullet(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_tts_edge_bullet3))
                        HelpSection(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_tts_ai_title))
                        HelpBullet(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_tts_ai_bullet1))
                        HelpBullet(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_tts_ai_bullet2))
                        HelpBullet(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_tts_ai_bullet3))
                        HelpText(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_tts_ai_example))
                        HelpSection(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_tts_custom_title))
                        HelpBullet(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_tts_custom_bullet1))
                        HelpBullet(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_tts_custom_bullet2))
                        HelpSection(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_tts_speed_title))
                        HelpBullet(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_tts_speed_bullet1))
                        HelpBullet(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_tts_speed_bullet2))
                    }
                }
            }

            // Engine chips — FlowRow for auto-wrap
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TTSProviderType.entries.forEach { provider ->
                    FilterChip(
                        selected = currentProvider == provider,
                        onClick = { onProviderChange(provider) },
                        label = {
                            Text(
                                androidx.compose.ui.res.stringResource(
                                    when (provider) {
                                        TTSProviderType.EDGE_TTS -> com.moyue.app.R.string.tts_provider_edge
                                        TTSProviderType.AI_VOICE -> com.moyue.app.R.string.tts_provider_ai
                                        TTSProviderType.CUSTOM_TTS -> com.moyue.app.R.string.tts_provider_custom
                                        TTSProviderType.SYSTEM -> com.moyue.app.R.string.tts_provider_system
                                    }
                                ),
                                fontSize = 12.sp,
                            )
                        },
                    )
                }
            }

            // === Flashcard TTS Engine (独立设置) ===
            Spacer(Modifier.height(12.dp))
            val context = LocalContext.current
            val flashcardPrefs = remember { context.getSharedPreferences("moreader_config", android.content.Context.MODE_PRIVATE) }
            var flashcardProviderStr by remember { mutableStateOf(flashcardPrefs.getString("flashcard_tts_provider", "system") ?: "system") }
            val flashcardProvider = try { TTSProviderType.valueOf(flashcardProviderStr) } catch (e: IllegalArgumentException) { TTSProviderType.SYSTEM }

            Text(
                androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_flashcard_engine),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_flashcard_engine_desc),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TTSProviderType.entries.forEach { provider ->
                    FilterChip(
                        selected = flashcardProvider == provider,
                        onClick = {
                            flashcardPrefs.edit().putString("flashcard_tts_provider", provider.name).apply()
                            flashcardProviderStr = provider.name
                        },
                        label = {
                            Text(
                                androidx.compose.ui.res.stringResource(
                                    when (provider) {
                                        TTSProviderType.EDGE_TTS -> com.moyue.app.R.string.tts_provider_edge
                                        TTSProviderType.AI_VOICE -> com.moyue.app.R.string.tts_provider_ai
                                        TTSProviderType.CUSTOM_TTS -> com.moyue.app.R.string.tts_provider_custom
                                        TTSProviderType.SYSTEM -> com.moyue.app.R.string.tts_provider_system
                                    }
                                ),
                                fontSize = 12.sp,
                            )
                        },
                    )
                }
            }

            // Speed — compact inline row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(
                    androidx.compose.ui.res.stringResource(
                        com.moyue.app.R.string.tts_speed,
                        String.format("%.1f", ttsSpeed),
                    ),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Slider(
                    value = ttsSpeed,
                    onValueChange = onSpeedChange,
                    valueRange = 0.5f..2.0f,
                    steps = 14,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            // === Provider-specific config — only show selected provider ===
            if (currentProvider == TTSProviderType.EDGE_TTS) {
                val localEp = remember(edgeEndpoint) { mutableStateOf(edgeEndpoint) }
                val localVoice = remember(edgeVoice) { mutableStateOf(edgeVoice) }
                var showVoiceMenu by remember { mutableStateOf(false) }
                val context = LocalContext.current
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = localEp.value,
                        onValueChange = { localEp.value = it; onEdgeConfigChange(it, localVoice.value) },
                        label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_server_url), fontSize = 11.sp) },
                        singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        textStyle = TextStyle(fontSize = 12.sp),
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = localVoice.value.let { voiceId ->
                                EDGE_VOICES.find { it.id == voiceId }?.displayName(context) ?: voiceId
                            },
                            onValueChange = {}, readOnly = true,
                            label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_voice), fontSize = 11.sp) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 12.sp),
                            trailingIcon = {
                                IconButton(onClick = { showVoiceMenu = true }) {
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                        )
                        DropdownMenu(
                            expanded = showVoiceMenu,
                            onDismissRequest = { showVoiceMenu = false },
                            modifier = Modifier.heightIn(max = 300.dp),
                        ) {
                            groupedEdgeVoices().forEach { (localeName, voices) ->
                                DropdownMenuItem(
                                    text = { Text(localeName, fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                                    onClick = {},
                                    enabled = false,
                                )
                                voices.forEach { voice ->
                                    val isSelected = voice.id == localVoice.value
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                (if (isSelected) "✓ " else "    ") + voice.displayName(context),
                                                fontSize = 12.sp,
                                            )
                                        },
                                        onClick = {
                                            localVoice.value = voice.id
                                            onEdgeConfigChange(localEp.value, voice.id)
                                            showVoiceMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (currentProvider == TTSProviderType.AI_VOICE) {
                val localEp = remember(aiEndpoint) { mutableStateOf(aiEndpoint) }
                val localKey = remember(aiApiKey) { mutableStateOf(aiApiKey) }
                val localModel = remember(aiModel) { mutableStateOf(aiModel) }
                val localVoice = remember(aiVoice) { mutableStateOf(aiVoice) }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = localEp.value, onValueChange = { localEp.value = it; onAIVoiceConfigChange(it, localKey.value, localModel.value, localVoice.value) },
                        label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_label_base_url), fontSize = 11.sp) },
                        singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 12.sp),
                    )
                    OutlinedTextField(
                        value = localKey.value, onValueChange = { localKey.value = it; onAIVoiceConfigChange(localEp.value, it, localModel.value, localVoice.value) },
                        label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_label_api_key), fontSize = 11.sp) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 12.sp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = localModel.value, onValueChange = { localModel.value = it; onAIVoiceConfigChange(localEp.value, localKey.value, it, localVoice.value) },
                        label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_model), fontSize = 11.sp) },
                        singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 12.sp),
                    )
                    OutlinedTextField(
                        value = localVoice.value, onValueChange = { localVoice.value = it; onAIVoiceConfigChange(localEp.value, localKey.value, localModel.value, it) },
                        label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_voice), fontSize = 11.sp) },
                        singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 12.sp),
                    )
                }
            }

            if (currentProvider == TTSProviderType.CUSTOM_TTS) {
                Text(
                    androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_custom_tts_hint),
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(4.dp))
                val localEp = remember(customEndpoint) { mutableStateOf(customEndpoint) }
                val localKey = remember(customApiKey) { mutableStateOf(customApiKey) }
                val localModel = remember(customModel) { mutableStateOf(customModel) }
                val localVoice = remember(customVoice) { mutableStateOf(customVoice) }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = localEp.value, onValueChange = { localEp.value = it; onCustomTTSConfigChange(it, localKey.value, localModel.value, localVoice.value) },
                        label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_label_base_url), fontSize = 11.sp) },
                        singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 12.sp),
                    )
                    OutlinedTextField(
                        value = localKey.value, onValueChange = { localKey.value = it; onCustomTTSConfigChange(localEp.value, it, localModel.value, localVoice.value) },
                        label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_label_api_key), fontSize = 11.sp) },
                        singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 12.sp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = localModel.value, onValueChange = { localModel.value = it; onCustomTTSConfigChange(localEp.value, localKey.value, it, localVoice.value) },
                        label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_label_model), fontSize = 11.sp) },
                        singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 12.sp),
                    )
                    OutlinedTextField(
                        value = localVoice.value, onValueChange = { localVoice.value = it; onCustomTTSConfigChange(localEp.value, localKey.value, localModel.value, it) },
                        label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_label_voice), fontSize = 11.sp) },
                        singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 12.sp),
                    )
                }
            }

            if (currentProvider == TTSProviderType.SYSTEM) {
                val context = LocalContext.current
                OutlinedCard(
                    onClick = {
                        try {
                            val intent = android.content.Intent("com.android.settings.TTS_SETTINGS")
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_system_settings),
                                fontWeight = FontWeight.Medium, fontSize = 13.sp,
                            )
                            Text(
                                androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_system_settings_desc),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        }
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                    }
                }
            }

            // === AI 翻译设置 (LLM) — collapsible ===
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            SectionHeader(
                title = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.ai_translate_settings),
                expanded = showLLMConfig,
                onToggle = { showLLMConfig = !showLLMConfig },
                help = {
                    var showCloudAiHelp by remember { mutableStateOf(false) }
                    TextButton(onClick = { showCloudAiHelp = true }, contentPadding = PaddingValues(4.dp)) {
                        Text("?", fontSize = 12.sp, color = Color(0xFFE53935))
                    }
                    if (showCloudAiHelp) {
                        HelpDialog(
                            title = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_btn_cloud_ai),
                            onDismiss = { showCloudAiHelp = false },
                        ) {
                            HelpText(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_cloud_ai_intro))
                            HelpSection("☁️ " + androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_cloud_ai_default_title))
                            HelpText(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_cloud_ai_default))
                            HelpSection("📌 " + androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_cloud_ai_get_key_title))
                            HelpText(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_cloud_ai_steps))
                            HelpHighlight(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_cloud_ai_bonus))
                            HelpSection("💡 " + androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_cloud_ai_alternative_title))
                            HelpText(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_cloud_ai_alternative))
                        }
                    }
                },
            )
            AnimatedVisibility(visible = showLLMConfig, enter = expandVertically(), exit = shrinkVertically()) {
                Column(modifier = Modifier.padding(start = 22.dp)) {
                    // 翻译引擎选择 — 云端/本地
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = translateEngine == com.moyue.app.data.models.TranslateEngine.CLOUD,
                            onClick = { onTranslateEngineChange(com.moyue.app.data.models.TranslateEngine.CLOUD) },
                            label = { Text("☁️ " + androidx.compose.ui.res.stringResource(com.moyue.app.R.string.local_ai_engine_cloud), fontSize = 12.sp) },
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = translateEngine == com.moyue.app.data.models.TranslateEngine.LOCAL,
                            onClick = { onTranslateEngineChange(com.moyue.app.data.models.TranslateEngine.LOCAL) },
                            label = { Text("📱 " + androidx.compose.ui.res.stringResource(com.moyue.app.R.string.local_ai_engine_local), fontSize = 12.sp) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(6.dp))

                    val defaultEndpoint = "https://api.deepseek.com"
                    val defaultModel = "deepseek-chat"

                    val llmEp = remember(llmConfig.endpoint) { mutableStateOf(llmConfig.endpoint.ifEmpty { defaultEndpoint }) }
                    val llmKey = remember(llmConfig.apiKey) { mutableStateOf(llmConfig.apiKey) }
                    val llmModel = remember(llmConfig.model) { mutableStateOf(llmConfig.model.ifEmpty { defaultModel }) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedTextField(
                            value = llmEp.value,
                            onValueChange = { llmEp.value = it },
                            label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.endpoint_url), fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = TextStyle(fontSize = 12.sp),
                        )
                        OutlinedTextField(
                            value = llmKey.value,
                            onValueChange = { llmKey.value = it },
                            label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_api_key_only_hint), fontSize = 11.sp) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.weight(1f),
                            textStyle = TextStyle(fontSize = 12.sp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = llmModel.value,
                            onValueChange = { llmModel.value = it },
                            label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.model_name), fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = TextStyle(fontSize = 12.sp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = {
                                onLLMConfigChange(LLMConfig("custom", llmKey.value, llmEp.value, llmModel.value))
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.save), fontSize = 12.sp)
                        }
                    }
                }
            }

            // === 本地 AI 设置 — collapsible ===
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            SectionHeader(
                title = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.local_ai_section),
                expanded = showLocalAiConfig,
                onToggle = { showLocalAiConfig = !showLocalAiConfig },
                help = {
                    var showLocalAiHelp by remember { mutableStateOf(false) }
                    TextButton(onClick = { showLocalAiHelp = true }, contentPadding = PaddingValues(4.dp)) {
                        Text("?", fontSize = 12.sp, color = Color(0xFFE53935))
                    }
                    if (showLocalAiHelp) {
                        HelpDialog(
                            title = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_btn_local_ai),
                            onDismiss = { showLocalAiHelp = false },
                        ) {
                            HelpText(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_local_ai_intro))
                            HelpSection("📌 " + androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_local_ai_steps_title))
                            HelpText(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_local_ai_steps))
                            HelpSection("📥 " + androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_local_ai_model_title))
                            HelpHighlight("⭐ " + androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_local_ai_recommended))
                            HelpText(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_local_ai_light))
                            HelpSection("⚡ " + androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_local_ai_speed_title))
                            HelpText(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_local_ai_speed))
                        }
                    }
                },
            )
            AnimatedVisibility(visible = showLocalAiConfig, enter = expandVertically(), exit = shrinkVertically()) {
                Column(modifier = Modifier.padding(start = 22.dp)) {
                    // Engine toggle
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = translateEngine == com.moyue.app.data.models.TranslateEngine.CLOUD,
                            onClick = { onTranslateEngineChange(com.moyue.app.data.models.TranslateEngine.CLOUD) },
                            label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.local_ai_engine_cloud), fontSize = 12.sp) },
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = translateEngine == com.moyue.app.data.models.TranslateEngine.LOCAL,
                            onClick = { onTranslateEngineChange(com.moyue.app.data.models.TranslateEngine.LOCAL) },
                            label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.local_ai_engine_local), fontSize = 12.sp) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(6.dp))

                    // GPU toggle
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            androidx.compose.ui.res.stringResource(com.moyue.app.R.string.local_ai_gpu_accel),
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = localAiGpuLayers > 0,
                            onCheckedChange = { onGpuLayersChange(if (it) 999 else 0) },
                        )
                    }
                    Text(
                        androidx.compose.ui.res.stringResource(if (localAiGpuLayers > 0) com.moyue.app.R.string.local_ai_gpu_on else com.moyue.app.R.string.local_ai_gpu_off),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(4.dp))

                    // Model status
                    if (localAiModelName.isNotEmpty() && localAiModelName != "No model loaded") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("✅ $localAiModelName", fontSize = 12.sp, color = Color(0xFF4CAF50), modifier = Modifier.weight(1f))
                            Button(
                                onClick = { onLocalAiModelUnload() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.local_ai_unload), fontSize = 11.sp)
                            }
                        }
                    } else {
                        Text(
                            androidx.compose.ui.res.stringResource(com.moyue.app.R.string.local_ai_no_model),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                    Spacer(Modifier.height(4.dp))

                    // File picker
                    val modelPicker = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument(),
                    ) { uri -> uri?.let { onLocalAiModelSelect(it) } }
                    OutlinedButton(onClick = { modelPicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (localAiModelName.isNotEmpty() && localAiModelName != "No model loaded")
                                androidx.compose.ui.res.stringResource(com.moyue.app.R.string.local_ai_change_model)
                            else
                                androidx.compose.ui.res.stringResource(com.moyue.app.R.string.local_ai_select_model),
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        androidx.compose.ui.res.stringResource(com.moyue.app.R.string.local_ai_recommend),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )

                    // Log viewer
                    var showLogs by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { showLogs = true },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) {
                        Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.local_ai_view_logs), fontSize = 11.sp)
                    }

                    if (showLogs) {
                        val logs = getLocalAiLogs()
                        var logCopied by remember { mutableStateOf(false) }
                        val context = androidx.compose.ui.platform.LocalContext.current
                        AlertDialog(
                            onDismissRequest = { showLogs = false },
                            title = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.setting_view_logs), fontSize = 14.sp) },
                            text = {
                                val scrollState = rememberScrollState()
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = logs.ifEmpty { androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_log_empty) },
                                        fontSize = 10.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 260.dp)
                                            .verticalScroll(scrollState),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                cm.setPrimaryClip(android.content.ClipData.newPlainText("AI Log", logs))
                                                logCopied = true
                                            },
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text(
                                                if (logCopied) "✅ " + androidx.compose.ui.res.stringResource(com.moyue.app.R.string.copied)
                                                else androidx.compose.ui.res.stringResource(com.moyue.app.R.string.copy_log),
                                                fontSize = 11.sp,
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = { clearLocalAiLogs(); logCopied = false },
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text("🗑️ ${androidx.compose.ui.res.stringResource(com.moyue.app.R.string.delete)}", fontSize = 11.sp)
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showLogs = false }) {
                                    Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.local_ai_close))
                                }
                            },
                        )
                    }
                }
            }

            // === Theme selection ===
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text(
                androidx.compose.ui.res.stringResource(com.moyue.app.R.string.theme),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ReaderTheme.entries.forEach { theme ->
                    FilterChip(
                        selected = currentTheme == theme,
                        onClick = { onThemeChange(theme) },
                        label = {
                            Text(
                                androidx.compose.ui.res.stringResource(
                                    when (theme) {
                                        ReaderTheme.LIGHT -> com.moyue.app.R.string.theme_name_light
                                        ReaderTheme.PARCHMENT -> com.moyue.app.R.string.theme_name_parchment
                                        ReaderTheme.GRAY -> com.moyue.app.R.string.theme_name_gray
                                        ReaderTheme.DARK -> com.moyue.app.R.string.theme_name_dark
                                        ReaderTheme.SLATE -> com.moyue.app.R.string.theme_name_slate
                                    }
                                ),
                                fontSize = 10.sp,
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color(android.graphics.Color.parseColor(theme.bgColor)),
                            labelColor = Color(android.graphics.Color.parseColor(theme.textColor)),
                            selectedContainerColor = Color(android.graphics.Color.parseColor(theme.bgColor)),
                        ),
                    )
                }
            }

            // === Recording ===
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilledTonalButton(
                    onClick = onRecordingClick,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("🎙️ ${androidx.compose.ui.res.stringResource(com.moyue.app.R.string.recording_entry)}", fontSize = 12.sp)
                }
                FilledTonalButton(
                    onClick = onBrowseRecordingsClick,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("📂 ${androidx.compose.ui.res.stringResource(com.moyue.app.R.string.recording_browse)}", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ============================================================
// Help dialog helpers (unchanged)
// ============================================================

@Composable
private fun HelpDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(end = 4.dp)
            ) {
                content()
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_close))
            }
        },
    )
}

@Composable
private fun HelpSection(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun HelpText(text: String) {
    Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun HelpBullet(text: String, indent: Boolean = true) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = if (indent) 12.dp else 0.dp)) {
        Text("•  ", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        Text(text, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun HelpHighlight(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
    ) {
        Text(text, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(10.dp))
    }
}

@Composable
private fun HelpLink(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp, bottom = 2.dp)) {
        Text("🔗  ", fontSize = 11.sp)
        SelectionContainer {
            Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
}
