package com.moyue.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyue.app.R
import com.moyue.app.data.models.LLMConfig
import com.moyue.app.data.models.ReaderTheme
import com.moyue.app.data.models.TTSProviderType
import com.moyue.app.tts.*

private fun themeNameRes(theme: ReaderTheme): Int {
    return when (theme) {
        ReaderTheme.LIGHT -> R.string.theme_name_light
        ReaderTheme.PARCHMENT -> R.string.theme_name_parchment
        ReaderTheme.GRAY -> R.string.theme_name_gray
        ReaderTheme.DARK -> R.string.theme_name_dark
        ReaderTheme.SLATE -> R.string.theme_name_slate
    }
}

// 试听文本：Edge 按语言选句；其他引擎统一用中文测试句（墨阅以中文阅读为主）
private fun previewTextForVoice(voiceId: String, isEdge: Boolean): String {
    if (isEdge) {
        return when {
            voiceId.startsWith("zh-") -> "你好，我是你的朗读伴侣。希望在接下来的阅读时光里，为你带来温暖与舒适的陪伴。"
            voiceId.startsWith("ja-") -> "こんにちは。私の声はいかがでしょうか？素敵な読书の時間をお届けします。"
            voiceId.startsWith("ko-") -> "안녕하세요. 오늘부터 당신의 독서 여정에 함께할 목소리입니다. 마음에 드셨으면 좋겠습니다。"
            else -> "Hello! It is a pleasure to read with you. I hope my voice brings your favorite books and stories to life."
        }
    }
    return "你好，我是你的朗读伴侣。希望在接下来的阅读时光里，为你带来温暖与舒适的陪伴。"
}

/**
 * 试听按钮：直接复用各引擎真实的 TTSProvider 实例（speak）
 */
@Composable
private fun PreviewButton(
    isPreviewing: MutableState<Boolean>,
    previewRef: MutableState<TTSProvider?>,
    makeProvider: () -> TTSProvider,
    previewText: String,
) {
    FilledTonalButton(
        onClick = {
            if (isPreviewing.value) {
                previewRef.value?.stop()
                previewRef.value = null
                isPreviewing.value = false
            } else {
                isPreviewing.value = true
                val provider = makeProvider()
                previewRef.value = provider
                provider.speak(previewText, 1.0f, object : TTSListener {
                    override fun onStart() {}
                    override fun onDone() { isPreviewing.value = false; previewRef.value = null }
                    override fun onError(message: String) { isPreviewing.value = false; previewRef.value = null }
                })
            }
        },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
    ) {
        if (isPreviewing.value) {
            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.voice_picker_stop), fontSize = 12.sp)
        } else {
            Text("🔊 " + stringResource(R.string.voice_picker_preview), fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    customEndpoint: String = "",
    customApiKey: String = "dummy",
    customModel: String = "moss-tts-nano",
    customVoice: String = "Lingyu",
    // System TTS
    systemTtsVoice: String = "",
    llmConfig: LLMConfig,
    // Local AI (保留参数向后兼容，UI已移除做减法)
    translateEngine: com.moyue.app.data.models.TranslateEngine = com.moyue.app.data.models.TranslateEngine.CLOUD,
    localAiModelName: String = "",
    localAiGpuLayers: Int = 0,
    currentTheme: ReaderTheme = ReaderTheme.LIGHT,
    currentTextBrightness: Int = 100,
    landscapeDrawer: Boolean = false,
    onProviderChange: (TTSProviderType) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onEdgeConfigChange: (endpoint: String, voice: String) -> Unit,
    onAIVoiceConfigChange: (endpoint: String, apiKey: String, model: String, voice: String) -> Unit,
    onCustomTTSConfigChange: (endpoint: String, apiKey: String, model: String, voice: String) -> Unit,
    onSystemVoiceChange: (String) -> Unit = {},
    onLLMConfigChange: (LLMConfig) -> Unit,
    onTranslateEngineChange: (com.moyue.app.data.models.TranslateEngine) -> Unit = {},
    onLocalAiModelSelect: (android.net.Uri) -> Unit = {},
    onLocalAiModelUnload: () -> Unit = {},
    getLocalAiLogs: () -> String = { "" },
    clearLocalAiLogs: () -> Unit = {},
    onGpuLayersChange: (Int) -> Unit = {},
    onThemeChange: (ReaderTheme) -> Unit = {},
    onTextBrightnessChange: (Int) -> Unit = {},
    isEinkMode: Boolean = false,
    onEinkModeChange: (Boolean) -> Unit = {},
    onRecordingClick: () -> Unit = {},
    onBrowseRecordingsClick: () -> Unit = {},
    onClose: () -> Unit,
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    Surface(
        modifier = if (landscapeDrawer) {
            Modifier.width(420.dp).fillMaxHeight().imePadding()
        } else {
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .imePadding()
        },
        shape = if (landscapeDrawer) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            // ── 顶部 Header ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.reader_settings_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                    )
                }
            }

            // ── 三选项卡 TabRow (语音 / 大模型 / 显示) ──
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = {
                        Text(
                            stringResource(R.string.settings_tab_voice),
                            fontSize = 13.sp,
                            fontWeight = if (selectedTabIndex == 0) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    icon = {
                        Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = {
                        Text(
                            stringResource(R.string.settings_tab_llm),
                            fontSize = 13.sp,
                            fontWeight = if (selectedTabIndex == 1) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    icon = {
                        Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    text = {
                        Text(
                            stringResource(R.string.settings_tab_display),
                            fontSize = 13.sp,
                            fontWeight = if (selectedTabIndex == 2) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    icon = {
                        Icon(Icons.Default.Palette, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
            }

            // ── 选项卡对应的内容区 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true)
            ) {
                when (selectedTabIndex) {
                    0 -> VoiceSettingsTab(
                        currentProvider = currentProvider,
                        ttsSpeed = ttsSpeed,
                        edgeEndpoint = edgeEndpoint,
                        edgeVoice = edgeVoice,
                        aiEndpoint = aiEndpoint,
                        aiApiKey = aiApiKey,
                        aiModel = aiModel,
                        aiVoice = aiVoice,
                        customEndpoint = customEndpoint,
                        customApiKey = customApiKey,
                        customModel = customModel,
                        customVoice = customVoice,
                        systemTtsVoice = systemTtsVoice,
                        onProviderChange = onProviderChange,
                        onSpeedChange = onSpeedChange,
                        onEdgeConfigChange = onEdgeConfigChange,
                        onAIVoiceConfigChange = onAIVoiceConfigChange,
                        onCustomTTSConfigChange = onCustomTTSConfigChange,
                        onSystemVoiceChange = onSystemVoiceChange,
                        onRecordingClick = onRecordingClick,
                        onBrowseRecordingsClick = onBrowseRecordingsClick,
                    )
                    1 -> LlmSettingsTab(
                        llmConfig = llmConfig,
                        onLLMConfigChange = onLLMConfigChange,
                    )
                    2 -> DisplaySettingsTab(
                        currentTheme = currentTheme,
                        currentTextBrightness = currentTextBrightness,
                        isEinkMode = isEinkMode,
                        onThemeChange = onThemeChange,
                        onTextBrightnessChange = onTextBrightnessChange,
                        onEinkModeChange = onEinkModeChange,
                    )
                }
            }
        }
    }
}

// ============================================================
// 1. 语音设置选项卡 (Voice Settings)
// ============================================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceSettingsTab(
    currentProvider: TTSProviderType,
    ttsSpeed: Float,
    edgeEndpoint: String,
    edgeVoice: String,
    aiEndpoint: String,
    aiApiKey: String,
    aiModel: String,
    aiVoice: String,
    customEndpoint: String,
    customApiKey: String,
    customModel: String,
    customVoice: String,
    systemTtsVoice: String,
    onProviderChange: (TTSProviderType) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onEdgeConfigChange: (endpoint: String, voice: String) -> Unit,
    onAIVoiceConfigChange: (endpoint: String, apiKey: String, model: String, voice: String) -> Unit,
    onCustomTTSConfigChange: (endpoint: String, apiKey: String, model: String, voice: String) -> Unit,
    onSystemVoiceChange: (String) -> Unit,
    onRecordingClick: () -> Unit,
    onBrowseRecordingsClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // === 引擎选择与帮助 ===
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.tts_engine),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
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
                    title = stringResource(R.string.help_btn_tts),
                    onDismiss = { showTtsHelp = false },
                ) {
                    HelpText(stringResource(R.string.help_tts_intro))
                    HelpSection(stringResource(R.string.help_tts_edge_title))
                    HelpBullet(stringResource(R.string.help_tts_edge_bullet1))
                    HelpBullet(stringResource(R.string.help_tts_edge_bullet2))
                    HelpBullet(stringResource(R.string.help_tts_edge_bullet3))
                    HelpSection(stringResource(R.string.help_tts_ai_title))
                    HelpBullet(stringResource(R.string.help_tts_ai_bullet1))
                    HelpBullet(stringResource(R.string.help_tts_ai_bullet2))
                    HelpBullet(stringResource(R.string.help_tts_ai_bullet3))
                    HelpText(stringResource(R.string.help_tts_ai_example))
                    HelpSection(stringResource(R.string.help_tts_custom_title))
                    HelpBullet(stringResource(R.string.help_tts_custom_bullet1))
                    HelpBullet(stringResource(R.string.help_tts_custom_bullet2))
                    HelpSection(stringResource(R.string.help_tts_speed_title))
                    HelpBullet(stringResource(R.string.help_tts_speed_bullet1))
                    HelpBullet(stringResource(R.string.help_tts_speed_bullet2))
                }
            }
        }

        // 引擎 FilterChips
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
                            stringResource(
                                when (provider) {
                                    TTSProviderType.EDGE_TTS -> R.string.tts_provider_edge
                                    TTSProviderType.AI_VOICE -> R.string.tts_provider_ai
                                    TTSProviderType.CUSTOM_TTS -> R.string.tts_provider_custom
                                    TTSProviderType.SYSTEM -> R.string.tts_provider_system
                                }
                            ),
                            fontSize = 12.sp,
                        )
                    },
                )
            }
        }

        // Speed 语速滑块
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(
                stringResource(
                    R.string.tts_speed,
                    String.format("%.1f", ttsSpeed),
                ),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
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

        // === Provider-specific config ===
        if (currentProvider == TTSProviderType.EDGE_TTS) {
            val localEp = remember(edgeEndpoint) { mutableStateOf(edgeEndpoint) }
            val localVoice = remember(edgeVoice) { mutableStateOf(edgeVoice) }
            var showVoicePicker by remember { mutableStateOf(false) }
            val isPreviewing = remember { mutableStateOf(false) }
            val previewRef = remember { mutableStateOf<TTSProvider?>(null as TTSProvider?) }
            val context = LocalContext.current

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = localEp.value,
                    onValueChange = { localEp.value = it; onEdgeConfigChange(it, localVoice.value) },
                    label = { Text(stringResource(R.string.tts_server_url), fontSize = 11.sp) },
                    singleLine = true, modifier = Modifier.weight(1.2f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    textStyle = TextStyle(fontSize = 12.sp),
                )
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = EdgeVoiceManager.getVoices(context).find { it.id == localVoice.value }?.displayName(context) ?: localVoice.value,
                        onValueChange = {}, readOnly = true,
                        label = { Text(stringResource(R.string.tts_voice), fontSize = 11.sp) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontSize = 12.sp),
                        trailingIcon = {
                            IconButton(onClick = { showVoicePicker = true }) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    )
                }
            }
            // Preview button row
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                PreviewButton(
                    isPreviewing = isPreviewing,
                    previewRef = previewRef,
                    makeProvider = { EdgeTTSProvider(localEp.value, localVoice.value) },
                    previewText = previewTextForVoice(localVoice.value, isEdge = true),
                )
            }
            if (showVoicePicker) {
                VoicePickerDialog(
                    endpoint = localEp.value,
                    currentVoiceId = localVoice.value,
                    onVoiceSelected = { voiceId ->
                        localVoice.value = voiceId
                        onEdgeConfigChange(localEp.value, voiceId)
                        showVoicePicker = false
                    },
                    onDismiss = { showVoicePicker = false },
                )
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
                    label = { Text(stringResource(R.string.tts_label_base_url), fontSize = 11.sp) },
                    singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 12.sp),
                )
                OutlinedTextField(
                    value = localKey.value, onValueChange = { localKey.value = it; onAIVoiceConfigChange(localEp.value, it, localModel.value, localVoice.value) },
                    label = { Text(stringResource(R.string.tts_label_api_key), fontSize = 11.sp) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 12.sp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = localModel.value, onValueChange = { localModel.value = it; onAIVoiceConfigChange(localEp.value, localKey.value, it, localVoice.value) },
                    label = { Text(stringResource(R.string.tts_model), fontSize = 11.sp) },
                    singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 12.sp),
                )
                OutlinedTextField(
                    value = localVoice.value, onValueChange = { localVoice.value = it; onAIVoiceConfigChange(localEp.value, localKey.value, localModel.value, it) },
                    label = { Text(stringResource(R.string.tts_voice), fontSize = 11.sp) },
                    singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 12.sp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                val isPreviewing = remember { mutableStateOf(false) }
                val previewRef = remember { mutableStateOf<TTSProvider?>(null as TTSProvider?) }
                PreviewButton(
                    isPreviewing = isPreviewing,
                    previewRef = previewRef,
                    makeProvider = { AIVoiceTTSProvider(localEp.value, localKey.value, localModel.value, localVoice.value) },
                    previewText = previewTextForVoice(localVoice.value, isEdge = false),
                )
            }
        }

        if (currentProvider == TTSProviderType.CUSTOM_TTS) {
            Text(
                stringResource(R.string.tts_custom_tts_hint),
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
                    label = { Text(stringResource(R.string.tts_label_base_url), fontSize = 11.sp) },
                    singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 12.sp),
                )
                OutlinedTextField(
                    value = localKey.value, onValueChange = { localKey.value = it; onCustomTTSConfigChange(localEp.value, it, localModel.value, localVoice.value) },
                    label = { Text(stringResource(R.string.tts_label_api_key), fontSize = 11.sp) },
                    singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 12.sp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = localModel.value, onValueChange = { localModel.value = it; onCustomTTSConfigChange(localEp.value, localKey.value, it, localVoice.value) },
                    label = { Text(stringResource(R.string.tts_label_model), fontSize = 11.sp) },
                    singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 12.sp),
                )
                OutlinedTextField(
                    value = localVoice.value, onValueChange = { localVoice.value = it; onCustomTTSConfigChange(localEp.value, localKey.value, localModel.value, it) },
                    label = { Text(stringResource(R.string.tts_label_voice), fontSize = 11.sp) },
                    singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 12.sp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                val isPreviewing = remember { mutableStateOf(false) }
                val previewRef = remember { mutableStateOf<TTSProvider?>(null as TTSProvider?) }
                PreviewButton(
                    isPreviewing = isPreviewing,
                    previewRef = previewRef,
                    makeProvider = { CustomTTSProvider(localEp.value, localKey.value, localModel.value, localVoice.value) },
                    previewText = previewTextForVoice(localVoice.value, isEdge = false),
                )
            }
        }

        if (currentProvider == TTSProviderType.SYSTEM) {
            val context = LocalContext.current
            var showSysVoiceMenu by remember { mutableStateOf(false) }
            val sysVoices = remember { mutableStateListOf<Pair<String, String>>() }
            LaunchedEffect(Unit) {
                sysVoices.clear()
                val engineVoices = com.moyue.app.tts.SystemTTSProvider.getCurrentVoices()
                sysVoices.addAll(engineVoices.map { it.name to it.displayName })
            }
            val sysEngine = com.moyue.app.tts.SystemTTSProvider.getCurrentEngineName()

            Text(
                stringResource(R.string.tts_system_engine) + ": ${sysEngine.ifEmpty { "—" }}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(4.dp))

            Box(modifier = Modifier.fillMaxWidth()) {
                val displayText = sysVoices.firstOrNull { it.first == systemTtsVoice }?.second
                    ?: stringResource(R.string.tts_system_default_voice)
                OutlinedTextField(
                    value = displayText,
                    onValueChange = {}, readOnly = true,
                    label = { Text(stringResource(R.string.tts_voice), fontSize = 11.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 12.sp),
                    trailingIcon = {
                        IconButton(onClick = { showSysVoiceMenu = true }) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    },
                )
                DropdownMenu(
                    expanded = showSysVoiceMenu,
                    onDismissRequest = { showSysVoiceMenu = false },
                    modifier = Modifier.heightIn(max = 300.dp),
                ) {
                    if (sysVoices.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tts_system_voices_loading), fontSize = 12.sp) },
                            onClick = { showSysVoiceMenu = false },
                            enabled = false,
                        )
                    } else {
                        val grouped = sysVoices.groupBy { (_, display) ->
                            val idx = display.lastIndexOf('(')
                            if (idx > 0) display.substring(idx) else ""
                        }
                        grouped.forEach { (locale, voices) ->
                            DropdownMenuItem(
                                text = { Text(locale.ifEmpty { "other" }, fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                                onClick = {},
                                enabled = false,
                            )
                            voices.forEach { (name, display) ->
                                DropdownMenuItem(
                                    text = { Text((if (name == systemTtsVoice) "✓ " else "    ") + display, fontSize = 12.sp) },
                                    onClick = { onSystemVoiceChange(name); showSysVoiceMenu = false },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                val isPreviewing = remember { mutableStateOf(false) }
                val previewRef = remember { mutableStateOf<TTSProvider?>(null as TTSProvider?) }
                PreviewButton(
                    isPreviewing = isPreviewing,
                    previewRef = previewRef,
                    makeProvider = {
                        SystemTTSProvider(context).also { p ->
                            if (systemTtsVoice.isNotEmpty()) p.setVoice(systemTtsVoice)
                        }
                    },
                    previewText = previewTextForVoice(systemTtsVoice, isEdge = false),
                )
            }
            Spacer(Modifier.height(4.dp))
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
                            stringResource(R.string.tts_system_settings),
                            fontWeight = FontWeight.Medium, fontSize = 13.sp,
                        )
                        Text(
                            stringResource(R.string.tts_system_settings_desc),
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

        // === 录制操作按钮 ===
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilledTonalButton(
                onClick = onRecordingClick,
                modifier = Modifier.weight(1f),
            ) {
                Text("🎙️ ${stringResource(R.string.recording_entry)}", fontSize = 12.sp, maxLines = 1)
            }
            FilledTonalButton(
                onClick = onBrowseRecordingsClick,
                modifier = Modifier.weight(1f),
            ) {
                Text("📂 ${stringResource(R.string.recording_browse)}", fontSize = 12.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ============================================================
// 2. 大模型设置选项卡 (AI Model Settings - 纯粹在线做减法)
// ============================================================
@Composable
private fun LlmSettingsTab(
    llmConfig: LLMConfig,
    onLLMConfigChange: (LLMConfig) -> Unit,
) {
    val defaultEndpoint = "https://api.deepseek.com/v1"
    val defaultModel = "deepseek-chat"

    val llmEp = remember(llmConfig.endpoint) { mutableStateOf(llmConfig.endpoint.ifEmpty { defaultEndpoint }) }
    val llmKey = remember(llmConfig.apiKey) { mutableStateOf(llmConfig.apiKey) }
    val llmModel = remember(llmConfig.model) { mutableStateOf(llmConfig.model.ifEmpty { defaultModel }) }
    val llmTargetLang = remember(llmConfig.targetLang) { mutableStateOf(llmConfig.targetLang.ifEmpty { "Chinese" }) }
    var keyVisible by remember { mutableStateOf(false) }
    var saveFeedback by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // 标题与帮助
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(R.string.ai_model_settings_title),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(4.dp))
            var showCloudAiHelp by remember { mutableStateOf(false) }
            TextButton(onClick = { showCloudAiHelp = true }, contentPadding = PaddingValues(4.dp)) {
                Text("?", fontSize = 12.sp, color = Color(0xFFE53935))
            }
            if (showCloudAiHelp) {
                HelpDialog(
                    title = stringResource(R.string.help_btn_cloud_ai),
                    onDismiss = { showCloudAiHelp = false },
                ) {
                    HelpText(stringResource(R.string.help_cloud_ai_intro))
                    HelpSection("☁️ " + stringResource(R.string.help_cloud_ai_default_title))
                    HelpText(stringResource(R.string.help_cloud_ai_default))
                    HelpSection("📌 " + stringResource(R.string.help_cloud_ai_get_key_title))
                    HelpText(stringResource(R.string.help_cloud_ai_steps))
                    HelpHighlight(stringResource(R.string.help_cloud_ai_bonus))
                    HelpSection("💡 " + stringResource(R.string.help_cloud_ai_alternative_title))
                    HelpText(stringResource(R.string.help_cloud_ai_alternative))
                }
            }
        }

        Text(
            stringResource(R.string.ai_model_role_desc),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // 常用预设快捷填充：2×2 网格，确保文字不换行
        Text("Presets:", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SuggestionChip(
                    onClick = {
                        llmEp.value = "https://api.openai.com/v1"
                        llmModel.value = "gpt-4o-mini"
                        saveFeedback = false
                    },
                    label = { Text("OpenAI", fontSize = 12.sp, maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
                SuggestionChip(
                    onClick = {
                        llmEp.value = "https://generativelanguage.googleapis.com/v1beta/openai"
                        llmModel.value = "gemini-2.0-flash"
                        saveFeedback = false
                    },
                    label = { Text("Google", fontSize = 12.sp, maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SuggestionChip(
                    onClick = {
                        llmEp.value = "https://api.deepseek.com/v1"
                        llmModel.value = "deepseek-chat"
                        saveFeedback = false
                    },
                    label = { Text("DeepSeek", fontSize = 12.sp, maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
                SuggestionChip(
                    onClick = {
                        llmEp.value = ""
                        llmModel.value = ""
                        llmKey.value = ""
                        saveFeedback = false
                    },
                    label = { Text("Custom", fontSize = 12.sp, maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Endpoint
        OutlinedTextField(
            value = llmEp.value,
            onValueChange = { llmEp.value = it; saveFeedback = false },
            label = { Text(stringResource(R.string.endpoint_url), fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontSize = 12.sp),
        )

        Spacer(Modifier.height(6.dp))

        // API Key (带密码显示/隐藏)
        OutlinedTextField(
            value = llmKey.value,
            onValueChange = { llmKey.value = it; saveFeedback = false },
            label = { Text(stringResource(R.string.tts_api_key_only_hint), fontSize = 11.sp) },
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        imageVector = if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontSize = 12.sp),
        )

        Spacer(Modifier.height(6.dp))

        // Model Name & Target Lang
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = llmModel.value,
                onValueChange = { llmModel.value = it; saveFeedback = false },
                label = { Text(stringResource(R.string.model_name), fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1.2f),
                textStyle = TextStyle(fontSize = 12.sp),
            )
            OutlinedTextField(
                value = llmTargetLang.value,
                onValueChange = { llmTargetLang.value = it; saveFeedback = false },
                label = { Text(stringResource(R.string.ai_target_language_label), fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(fontSize = 12.sp),
            )
        }

        Spacer(Modifier.height(6.dp))

        // Language Quick Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                "Chinese" to "中文",
                "English" to "English",
                "Japanese" to "日本語",
                "Korean" to "한국어",
                "French" to "Français",
                "Spanish" to "Español",
                "German" to "Deutsch"
            ).forEach { (langCode, langTitle) ->
                val selected = llmTargetLang.value.equals(langCode, ignoreCase = true)
                FilterChip(
                    selected = selected,
                    onClick = { llmTargetLang.value = langCode; saveFeedback = false },
                    label = { Text(langTitle, fontSize = 11.sp) }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Save Button
        Button(
            onClick = {
                onLLMConfigChange(LLMConfig("custom", llmKey.value, llmEp.value, llmModel.value, llmTargetLang.value))
                saveFeedback = true
            },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (saveFeedback) "✓ " + stringResource(R.string.sync_config_saved) else stringResource(R.string.save),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ============================================================
// 3. 显示与外观选项卡 (Display & Appearance - 展开式主题胶囊)
// ============================================================
@Composable
private fun DisplaySettingsTab(
    currentTheme: ReaderTheme,
    currentTextBrightness: Int,
    isEinkMode: Boolean,
    onThemeChange: (ReaderTheme) -> Unit,
    onTextBrightnessChange: (Int) -> Unit,
    onEinkModeChange: (Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        // === 1. E-Ink Mode ===
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.setting_eink_mode),
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.setting_eink_mode_desc),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            Switch(
                checked = isEinkMode,
                onCheckedChange = onEinkModeChange,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 10.dp))

        // === 2. 主题选择：直接展示所有主题卡片，无折叠 ===
        Text(
            stringResource(R.string.theme),
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))

        val themes = ReaderTheme.entries
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (i in 0 until themes.size step 2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (j in i until minOf(i + 2, themes.size)) {
                        val theme = themes[j]
                        val isSelected = currentTheme == theme
                        val themeBg = Color(android.graphics.Color.parseColor(theme.bgColor))
                        val themeText = Color(android.graphics.Color.parseColor(theme.textColor))

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = themeBg,
                            border = BorderStroke(
                                if (isSelected) 2.dp else 1.dp,
                                if (isSelected) {
                                    if (isEinkMode) Color.Black else MaterialTheme.colorScheme.primary
                                } else {
                                    themeText.copy(alpha = 0.18f)
                                }
                            ),
                            shadowElevation = if (isSelected) 4.dp else 1.dp,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onThemeChange(theme) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 精致色板：左半背景色 + 右半文字色
                                Box(
                                    modifier = Modifier
                                        .size(width = 28.dp, height = 22.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .border(0.8.dp, themeText.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                                ) {
                                    Box(Modifier.fillMaxSize().background(themeBg))
                                    Box(
                                        Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(0.45f)
                                            .align(Alignment.CenterEnd)
                                            .background(themeText.copy(alpha = 0.85f))
                                    )
                                    Box(
                                        Modifier
                                            .width(14.dp)
                                            .height(2.dp)
                                            .align(Alignment.Center)
                                            .background(themeText.copy(alpha = 0.45f))
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(themeNameRes(theme)),
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = themeText,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isSelected) {
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Palette,
                                        contentDescription = null,
                                        tint = if (isEinkMode) Color.Black else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                    if (i + 1 >= themes.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        // === 3. Text Brightness (深色主题下调节文字亮度) ===
        if (currentTheme.isDark) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.text_brightness),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    modifier = Modifier.width(42.dp),
                )
                Slider(
                    value = currentTextBrightness.toFloat(),
                    onValueChange = { onTextBrightnessChange(it.toInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f).height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
                Text(
                    "${currentTextBrightness}%",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.width(38.dp).wrapContentWidth(Alignment.End),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ============================================================
// 帮助弹窗辅助组件
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
                Text(stringResource(R.string.help_close))
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
    Spacer(Modifier.height(4.dp))
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(8.dp),
        )
    }
}
