package com.moyue.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.moyue.app.data.models.LLMConfig
import com.moyue.app.data.models.ReaderTheme
import com.moyue.app.data.models.TTSProviderType

// Common voice presets — now uses full EdgeVoice data with locale grouping
// EDGE_VOICES defined in EdgeVoiceData.kt

private val AI_VOICE_MODELS = listOf(
    "fnlp/MOSS-TTSD-v0.5" to "MOSS-TTSD v0.5 (中英双语)",
    "FunAudioLLM/CosyVoice2-0.5B" to "CosyVoice2 0.5B (中英双语)",
    "tts-1" to "OpenAI tts-1",
    "tts-1-hd" to "OpenAI tts-1-hd",
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
    currentTheme: ReaderTheme = ReaderTheme.LIGHT,
    onProviderChange: (TTSProviderType) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onEdgeConfigChange: (endpoint: String, voice: String) -> Unit,
    onAIVoiceConfigChange: (endpoint: String, apiKey: String, model: String, voice: String) -> Unit,
    onCustomTTSConfigChange: (endpoint: String, apiKey: String, model: String, voice: String) -> Unit,
    onLLMConfigChange: (LLMConfig) -> Unit,
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

            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_engine), fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
                            voices.forEach { voice ->
                                DropdownMenuItem(
                                    text = { Text(voice.displayName(), fontSize = 13.sp) },
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
                Text("OpenAI 兼容 TTS", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("适用于 MOSS-TTS-Nano、CosyVoice 等本地部署", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Spacer(Modifier.height(6.dp))

                var localEp by remember(customEndpoint) { mutableStateOf(customEndpoint) }
                var localKey by remember(customApiKey) { mutableStateOf(customApiKey) }
                var localModel by remember(customModel) { mutableStateOf(customModel) }
                var localVoice by remember(customVoice) { mutableStateOf(customVoice) }

                // 使用紧凑的双列布局
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = localEp, onValueChange = { localEp = it; onCustomTTSConfigChange(it, localKey, localModel, localVoice) },
                        label = { Text("Base URL") }, singleLine = true, modifier = Modifier.weight(1f),
                        placeholder = { Text("http://192.168.199.101:18083") },
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp))
                    OutlinedTextField(value = localKey, onValueChange = { localKey = it; onCustomTTSConfigChange(localEp, it, localModel, localVoice) },
                        label = { Text("API Key") }, singleLine = true, modifier = Modifier.weight(1f),
                        placeholder = { Text("dummy") },
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp))
                }
                Spacer(Modifier.height(4.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = localModel, onValueChange = { localModel = it; onCustomTTSConfigChange(localEp, localKey, it, localVoice) },
                        label = { Text("模型") }, singleLine = true, modifier = Modifier.weight(1f),
                        placeholder = { Text("moss-tts-nano") },
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp))
                    OutlinedTextField(value = localVoice, onValueChange = { localVoice = it; onCustomTTSConfigChange(localEp, localKey, localModel, it) },
                        label = { Text("音色") }, singleLine = true, modifier = Modifier.weight(1f),
                        placeholder = { Text("Lingyu") },
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
                    label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(value = localKey, onValueChange = { localKey = it; onAIVoiceConfigChange(localEp, it, localModel, localVoice) },
                    label = { Text("API Key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))

                // Model dropdown
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_model_selection), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                var modelExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = modelExpanded, onExpandedChange = { modelExpanded = it }) {
                    val modelLabel = AI_VOICE_MODELS.firstOrNull { it.first == localModel }?.second ?: localModel
                    OutlinedTextField(value = modelLabel, onValueChange = {}, readOnly = true,
                        label = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.tts_model)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(), singleLine = true)
                    ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                        AI_VOICE_MODELS.forEach { (id, name) ->
                            DropdownMenuItem(text = { Text(name, fontSize = 13.sp) },
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
                        val (female, male) = voices.partition { it.second.contains("女") }
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
            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.ai_translate_settings), fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                label = { Text("API Key (仅需填写此项)") }, singleLine = true, 
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
