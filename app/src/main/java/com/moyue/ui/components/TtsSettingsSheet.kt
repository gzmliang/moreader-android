package com.moyue.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyue.app.data.models.LLMConfig
import com.moyue.app.data.models.ReaderTheme
import com.moyue.app.data.models.TTSProviderType
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

// Common voice presets — now uses full EdgeVoice data with locale grouping
// EDGE_VOICES defined in EdgeVoiceData.kt

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
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(max = 580.dp),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_settings), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.close)) }
            }
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_engine), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                var showTtsHelp by remember { mutableStateOf(false) }
                TextButton(onClick = { showTtsHelp = true }, contentPadding = PaddingValues(8.dp)) {
                    Text("❓", fontSize = 13.sp)
                }
                if (showTtsHelp) {
                    HelpDialog(
                        title = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_btn_tts),
                        onDismiss = { showTtsHelp = false }
                    ) {
                        HelpText("墨阅提供多种 TTS 朗读引擎，按场景自由选择。")
                        HelpSection("☁️ Edge TTS（免费推荐）")
                        HelpBullet("微软免费语音合成，音质清晰自然")
                        HelpBullet("需联网，无需 API Key，即开即用")
                        HelpBullet("支持多国语言，男女声可选")
                        HelpSection("🤖 AI Voice（云端 TTS）")
                        HelpBullet("使用云端 TTS 服务，如硅基流动 SiliconFlow")
                        HelpBullet("支持 MOSS-TTSD、CosyVoice2 等高质模型")
                        HelpBullet("需配置服务端地址和 API Key")
                        HelpText("例：SiliconFlow → https://api.siliconflow.cn")
                        HelpSection("🔧 自定义 TTS")
                        HelpBullet("兼容 OpenAI TTS 接口格式")
                        HelpBullet("可对接任意 OpenAI 兼容的 TTS 服务")
                        HelpSection("⚙️ 语速调节")
                        HelpBullet("拖动滑块调整朗读速度")
                        HelpBullet("0.5x = 慢速  ·  1.0x = 正常  ·  2.0x = 快速")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 排除SYSTEM，因为Android 16上有兼容问题
                val available = TTSProviderType.entries.filter { it != TTSProviderType.SYSTEM }
                available.forEach { provider ->
                    FilterChip(selected = currentProvider == provider,
                        onClick = { onProviderChange(provider) },
                        label = { Text(androidx.compose.ui.res.stringResource(when (provider) {
                            TTSProviderType.EDGE_TTS -> com.moyue.app.R.string.tts_provider_edge
                            TTSProviderType.AI_VOICE -> com.moyue.app.R.string.tts_provider_ai
                            TTSProviderType.CUSTOM_TTS -> com.moyue.app.R.string.tts_provider_custom
                            TTSProviderType.SYSTEM -> com.moyue.app.R.string.tts_provider_system
                        }), fontSize = 12.sp) })
                }
            }
            Spacer(Modifier.height(16.dp))

            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_speed, String.format("%.1f", ttsSpeed)), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Slider(value = ttsSpeed, onValueChange = onSpeedChange, valueRange = 0.5f..2.0f, steps = 14, modifier = Modifier.fillMaxWidth())

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // Edge TTS
            if (currentProvider == TTSProviderType.EDGE_TTS) {
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_provider_edge), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))

                var localEp by remember(edgeEndpoint) { mutableStateOf(edgeEndpoint) }
                var localVoice by remember(edgeVoice) { mutableStateOf(edgeVoice) }

                OutlinedTextField(value = localEp, onValueChange = { localEp = it; onEdgeConfigChange(it, localVoice) },
                    label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_server_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))

                Spacer(Modifier.height(8.dp))
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_voice_selection), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))

                // Dropdown for edge voices
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = localVoice,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_voice)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        singleLine = true,
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        val grouped = groupedEdgeVoices()
                        grouped.forEach { (localeName, voices) ->
                            Text(localeName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            val ctx = androidx.compose.ui.platform.LocalContext.current
                            voices.forEach { voice ->
                                DropdownMenuItem(
                                    text = { Text(voice.displayName(ctx), fontSize = 13.sp) },
                                    onClick = { localVoice = voice.id; onEdgeConfigChange(localEp, voice.id); expanded = false },
                                )
                            }
                        }
                        // Also allow custom input
                        DropdownMenuItem(
                            text = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_custom_voice_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) },
                            onClick = { expanded = false },
                        )
                    }
                }
            }

            // Custom TTS (OpenAI-compatible)
            if (currentProvider == TTSProviderType.CUSTOM_TTS) {
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_custom_tts_title), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_custom_tts_hint), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Spacer(Modifier.height(6.dp))

                var localEp by remember(customEndpoint) { mutableStateOf(customEndpoint) }
                var localKey by remember(customApiKey) { mutableStateOf(customApiKey) }
                var localModel by remember(customModel) { mutableStateOf(customModel) }
                var localVoice by remember(customVoice) { mutableStateOf(customVoice) }

                // 使用紧凑的双列布局
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = localEp, onValueChange = { localEp = it; onCustomTTSConfigChange(it, localKey, localModel, localVoice) },
                        label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_label_base_url)) }, singleLine = true, modifier = Modifier.weight(1f),
                        placeholder = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_hint_base_url)) },
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp))
                    OutlinedTextField(value = localKey, onValueChange = { localKey = it; onCustomTTSConfigChange(localEp, it, localModel, localVoice) },
                        label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_label_api_key)) }, singleLine = true, modifier = Modifier.weight(1f),
                        placeholder = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_hint_api_key)) },
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp))
                }
                Spacer(Modifier.height(4.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = localModel, onValueChange = { localModel = it; onCustomTTSConfigChange(localEp, localKey, it, localVoice) },
                        label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_label_model)) }, singleLine = true, modifier = Modifier.weight(1f),
                        placeholder = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_hint_model)) },
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp))
                    OutlinedTextField(value = localVoice, onValueChange = { localVoice = it; onCustomTTSConfigChange(localEp, localKey, localModel, it) },
                        label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_label_voice)) }, singleLine = true, modifier = Modifier.weight(1f),
                        placeholder = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_hint_voice)) },
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp))
                }
            }

            // AI Voice
            if (currentProvider == TTSProviderType.AI_VOICE) {
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_provider_ai), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))

                var localEp by remember(aiEndpoint) { mutableStateOf(aiEndpoint) }
                var localKey by remember(aiApiKey) { mutableStateOf(aiApiKey) }
                var localModel by remember(aiModel) { mutableStateOf(aiModel) }
                var localVoice by remember(aiVoice) { mutableStateOf(aiVoice) }

                OutlinedTextField(value = localEp, onValueChange = { localEp = it; onAIVoiceConfigChange(it, localKey, localModel, localVoice) },
                    label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_label_base_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(value = localKey, onValueChange = { localKey = it; onAIVoiceConfigChange(localEp, it, localModel, localVoice) },
                    label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_label_api_key)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))

                // Model dropdown
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_model_selection), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                var modelExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = modelExpanded, onExpandedChange = { modelExpanded = it }) {
                    val modelLabelRes = AI_VOICE_MODELS.firstOrNull { it.first == localModel }?.second
                    val modelLabel = if (modelLabelRes != null) androidx.compose.ui.res.stringResource(modelLabelRes) else localModel
                    OutlinedTextField(value = modelLabel, onValueChange = {}, readOnly = true,
                        label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_model)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(), singleLine = true)
                    ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                        AI_VOICE_MODELS.forEach { (id, nameRes) ->
                            DropdownMenuItem(text = { Text(androidx.compose.ui.res.stringResource(nameRes), fontSize = 13.sp) },
                                onClick = {
                                    localModel = id
                                    // Auto-select first voice for this model
                                    val voices = aiVoicesForModel(id)
                                    if (voices.isNotEmpty()) {
                                        localVoice = voices.first().first
                                        onAIVoiceConfigChange(localEp, localKey, id, voices.first().first)
                                    } else {
                                        onAIVoiceConfigChange(localEp, localKey, id, localVoice)
                                    }
                                    modelExpanded = false
                                })
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Voice dropdown
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_voice_selection), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                val voices = remember(localModel) { aiVoicesForModel(localModel) }
                var voiceExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = voiceExpanded, onExpandedChange = { voiceExpanded = it }) {
                    val voiceLabel = voices.firstOrNull { it.first == localVoice }?.second ?: localVoice
                    OutlinedTextField(value = voiceLabel, onValueChange = {}, readOnly = true,
                        label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_voice)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(), singleLine = true)
                    ExposedDropdownMenu(expanded = voiceExpanded, onDismissRequest = { voiceExpanded = false }) {
                        // Group by gender
                        // Group by gender — Edge voices use "女"/"男", AI voices use "♀"/"♂"
                        val (female, male) = voices.partition { v -> v.second.contains("♀") || v.second.contains("女") }
                        Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.voice_female), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        female.forEach { (id, name) ->
                            DropdownMenuItem(text = { Text(name, fontSize = 13.sp) },
                                onClick = { localVoice = id; onAIVoiceConfigChange(localEp, localKey, localModel, id); voiceExpanded = false })
                        }
                        Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.voice_male), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        male.forEach { (id, name) ->
                            DropdownMenuItem(text = { Text(name, fontSize = 13.sp) },
                                onClick = { localVoice = id; onAIVoiceConfigChange(localEp, localKey, localModel, id); voiceExpanded = false })
                        }
                    }
                }
            }

            // LLM Config - AI翻译设置
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.ai_translate_settings), fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                var showCloudAiHelp by remember { mutableStateOf(false) }
                TextButton(onClick = { showCloudAiHelp = true }, contentPadding = PaddingValues(8.dp)) {
                    Text("❓", fontSize = 12.sp)
                }
                if (showCloudAiHelp) {
                    HelpDialog(
                        title = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_btn_cloud_ai),
                        onDismiss = { showCloudAiHelp = false }
                    ) {
                        HelpText("云端翻译使用在线 API，速度快、翻译质量高。")
                        HelpSection("☁️ 默认配置（DeepSeek）")
                        HelpBullet("API 地址：api.deepseek.com")
                        HelpBullet("模型：deepseek-chat")
                        HelpBullet("只需填写 API Key 即可使用")
                        HelpSection("📌 如何获取 DeepSeek API Key")
                        HelpText("1. 访问 platform.deepseek.com")
                        HelpText("2. 用手机号或邮箱注册账号")
                        HelpText("3. 登录后点击右上角头像 → 「API Keys」")
                        HelpText("4. 点击「创建 API Key」，复制保存")
                        HelpText("5. 粘贴到下方「API Key」输入框")
                        HelpHighlight("🎁 注册即送 5 元额度，约可调用 10 万次翻译")
                        HelpSection("💡 其他兼容 API")
                        HelpBullet("通义千问：dashscope.aliyuncs.com（百炼平台）")
                        HelpBullet("Moonshot：api.moonshot.cn")
                        HelpBullet("SiliconFlow：api.siliconflow.cn")
                        HelpText("只需修改 Base URL 和 API Key 即可切换")
                    }
                }
            }
            Spacer(Modifier.height(6.dp))

            // 默认使用 DeepSeek API
            val defaultEndpoint = "https://api.deepseek.com"
            val defaultModel = "deepseek-chat"
            
            var llmEp by remember(llmConfig.endpoint) { mutableStateOf(llmConfig.endpoint.ifEmpty { defaultEndpoint }) }
            var llmKey by remember(llmConfig.apiKey) { mutableStateOf(llmConfig.apiKey) }
            var llmModel by remember(llmConfig.model) { mutableStateOf(llmConfig.model.ifEmpty { defaultModel }) }

            // Base URL - 显示默认值
            OutlinedTextField(
                value = llmEp, 
                onValueChange = { llmEp = it },
                label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.endpoint_url)) }, 
                singleLine = true, 
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(defaultEndpoint) },
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
            )
            Spacer(Modifier.height(4.dp))

            // API Key - 用户只需要填这个
            OutlinedTextField(value = llmKey, onValueChange = { llmKey = it },
                label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_api_key_only_hint)) }, singleLine = true, 
                visualTransformation = PasswordVisualTransformation(), 
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp))
            Spacer(Modifier.height(4.dp))

            // 模型 - 显示默认值
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = llmModel, onValueChange = { llmModel = it },
                    label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.model_name)) }, 
                    singleLine = true, 
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(defaultModel) },
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp))
                Spacer(Modifier.width(6.dp))
                Button(onClick = { onLLMConfigChange(LLMConfig("custom", llmKey, llmEp, llmModel)) },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { 
                    Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.save), fontSize = 12.sp) 
                }
            }
            Spacer(Modifier.height(12.dp))

            // === Local AI Section ===
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.local_ai_section), fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                var showLocalAiHelp by remember { mutableStateOf(false) }
                TextButton(onClick = { showLocalAiHelp = true }, contentPadding = PaddingValues(8.dp)) {
                    Text("❓", fontSize = 12.sp)
                }
                if (showLocalAiHelp) {
                    HelpDialog(
                        title = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.help_btn_local_ai),
                        onDismiss = { showLocalAiHelp = false }
                    ) {
                        HelpText("本地 AI 翻译无需联网即可查词翻译，数据完全在本地。")
                        HelpSection("📌 使用步骤")
                        HelpText("1. 切换为「📱 本地模型」")
                        HelpText("2. 点击下方按钮选择模型文件")
                        HelpText("3. 阅读时长按选中文本，点「AI翻译」即可")
                        HelpSection("📥 推荐模型（.gguf 格式）")
                        HelpHighlight("⭐ Qwen2.5-1.5B-Instruct-Q4_K_M.gguf\n约 1GB，翻译质量稳定，查词典准确")
                        HelpLink("huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF")
                        HelpLink("modelscope.cn/models/Qwen/Qwen2.5-1.5B-Instruct-GGUF")
                        HelpText("国内镜像可选 ModelScope（魔搭）")
                        HelpSection("⚡ 速度参考（一加12 骁龙8 Gen3）")
                        HelpBullet("0.5B：首字 0.7-1.3s，翻译 4-8s")
                        HelpBullet("1.5B：首字 1.3-2.1s，翻译 6-15s")
                        HelpText("模型越大翻译越准确，建议用 1.5B 兜底")
                    }
                }
            }
            Spacer(Modifier.height(6.dp))

            // Engine toggle
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Spacer(Modifier.height(8.dp))

            // GPU acceleration toggle
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.local_ai_gpu_accel), fontSize = 12.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = localAiGpuLayers > 0,
                    onCheckedChange = { onGpuLayersChange(if (it) 999 else 0) }
                )
            }
            Text(androidx.compose.ui.res.stringResource(
                if (localAiGpuLayers > 0) com.moyue.app.R.string.local_ai_gpu_on
                else com.moyue.app.R.string.local_ai_gpu_off
            ), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.local_ai_gpu_hint), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
            Spacer(Modifier.height(4.dp))

            // Model status
            if (localAiModelName.isNotEmpty() && localAiModelName != "No model loaded") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("✅ $localAiModelName", fontSize = 12.sp, color = Color(0xFF4CAF50), modifier = Modifier.weight(1f))
                    Button(onClick = { onLocalAiModelUnload() }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.local_ai_unload), fontSize = 11.sp)
                    }
                }
            } else {
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.local_ai_no_model), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
            Spacer(Modifier.height(4.dp))

            // File picker for GGUF
            val modelPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri -> uri?.let { onLocalAiModelSelect(it) } }
            OutlinedButton(onClick = { modelPicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(androidx.compose.ui.res.stringResource(
                    if (localAiModelName.isNotEmpty() && localAiModelName != "No model loaded")
                        com.moyue.app.R.string.local_ai_change_model
                    else
                        com.moyue.app.R.string.local_ai_select_model
                ))
            }
            Spacer(Modifier.height(4.dp))
            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.local_ai_recommend), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))

            // Log viewer
            var showLogs by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { showLogs = true }, modifier = Modifier.weight(1f)) {
                    Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.local_ai_view_logs), fontSize = 11.sp)
                }
            }

            if (showLogs) {
                val logs = getLocalAiLogs()
                var logCopied by remember { mutableStateOf(false) }
                val context = androidx.compose.ui.platform.LocalContext.current
                AlertDialog(
                    onDismissRequest = { showLogs = false },
                    title = { Text("📋 本地AI日志", fontSize = 14.sp) },
                    text = {
                        val scrollState = rememberScrollState()
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = logs.ifEmpty { "暂无日志" },
                                fontSize = 10.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(scrollState)
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = {
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("AI日志", logs))
                                    logCopied = true
                                }, modifier = Modifier.weight(1f)) {
                                    Text(if (logCopied) "✅ 已复制" else "📋 复制日志", fontSize = 11.sp)
                                }
                                OutlinedButton(onClick = { clearLocalAiLogs(); logCopied = false }, modifier = Modifier.weight(1f)) {
                                    Text("🗑️ 清空", fontSize = 11.sp)
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
            Spacer(Modifier.height(8.dp))

            // Theme selection - 紧凑布局
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.theme), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            // 分两行显示主题，更紧凑
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                ReaderTheme.entries.take(3).forEach { theme ->
                    FilterChip(
                        selected = currentTheme == theme,
                        onClick = { onThemeChange(theme) },
                        label = { Text(androidx.compose.ui.res.stringResource(when (theme) {
                            ReaderTheme.LIGHT -> com.moyue.app.R.string.theme_name_light
                            ReaderTheme.PARCHMENT -> com.moyue.app.R.string.theme_name_parchment
                            ReaderTheme.GRAY -> com.moyue.app.R.string.theme_name_gray
                            ReaderTheme.DARK -> com.moyue.app.R.string.theme_name_dark
                            ReaderTheme.SLATE -> com.moyue.app.R.string.theme_name_slate
                        }), fontSize = 10.sp) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color(android.graphics.Color.parseColor(theme.bgColor)),
                            labelColor = Color(android.graphics.Color.parseColor(theme.textColor)),
                            selectedContainerColor = Color(android.graphics.Color.parseColor(theme.bgColor)),
                        ),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                ReaderTheme.entries.drop(3).forEach { theme ->
                    FilterChip(
                        selected = currentTheme == theme,
                        onClick = { onThemeChange(theme) },
                        label = { Text(androidx.compose.ui.res.stringResource(when (theme) {
                            ReaderTheme.LIGHT -> com.moyue.app.R.string.theme_name_light
                            ReaderTheme.PARCHMENT -> com.moyue.app.R.string.theme_name_parchment
                            ReaderTheme.GRAY -> com.moyue.app.R.string.theme_name_gray
                            ReaderTheme.DARK -> com.moyue.app.R.string.theme_name_dark
                            ReaderTheme.SLATE -> com.moyue.app.R.string.theme_name_slate
                        }), fontSize = 10.sp) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color(android.graphics.Color.parseColor(theme.bgColor)),
                            labelColor = Color(android.graphics.Color.parseColor(theme.textColor)),
                            selectedContainerColor = Color(android.graphics.Color.parseColor(theme.bgColor)),
                        ),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Reusable help dialog with composable content */
@Composable
private fun HelpDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
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

/** Styled section header */
@Composable
private fun HelpSection(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

/** Styled body paragraph */
@Composable
private fun HelpText(text: String) {
    Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface)
}

/** Indented bullet point */
@Composable
private fun HelpBullet(text: String, indent: Boolean = true) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = if (indent) 12.dp else 0.dp)) {
        Text("•  ", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        Text(text, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
    }
}

/** Highlighted recommendation box */
@Composable
private fun HelpHighlight(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    ) {
        Text(text, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(10.dp))
    }
}

/** Clickable link style */
@Composable
private fun HelpLink(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp, bottom = 2.dp)) {
        Text("🔗  ", fontSize = 11.sp)
        SelectionContainer {
            Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
}
