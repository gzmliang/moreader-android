package com.moyue.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.moyue.ai.data.AiCacheRepository
import com.moyue.ai.model.AiConfig
import com.moyue.ai.service.LlmClient
import com.moyue.app.R
import kotlinx.coroutines.launch

data class LanguageOption(val code: String, val displayName: String)

@Composable
fun AiSettingsDialog(
    repository: AiCacheRepository,
    onDismiss: () -> Unit,
    onSaved: (AiConfig) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val initialConfig = remember { repository.getAiConfig() }

    var selectedProvider by remember { mutableStateOf(initialConfig.provider) }
    var baseUrl by remember { mutableStateOf(initialConfig.baseUrl) }
    var apiKey by remember { mutableStateOf(initialConfig.apiKey) }
    var model by remember { mutableStateOf(initialConfig.model) }
    var sourceLang by remember { mutableStateOf(initialConfig.sourceLang.ifBlank { "Auto" }) }
    var targetLang by remember { mutableStateOf(initialConfig.targetLang.ifBlank { "Chinese" }) }

    // 自定义配置独立记忆（防止切换服务商时覆盖用户手打的 Base URL 与自建模型）
    var customBaseUrl by remember {
        mutableStateOf(
            if (initialConfig.provider == "Custom") initialConfig.baseUrl
            else repository.getCustomBaseUrl().ifBlank { "http://192.168.199.101:3000/v1" }
        )
    }
    var customApiKey by remember {
        mutableStateOf(
            if (initialConfig.provider == "Custom") initialConfig.apiKey
            else repository.getCustomApiKey()
        )
    }
    var customModel by remember {
        mutableStateOf(
            if (initialConfig.provider == "Custom") initialConfig.model
            else repository.getCustomModel().ifBlank { "gpt-4o-mini" }
        )
    }

    var apiKeyVisible by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    // 服务商定义与推荐模型
    data class ProviderItem(
        val id: String,
        val nameRes: Int,
        val defaultUrl: String,
        val defaultModel: String,
        val popularModels: List<String>
    )

    val providerList = listOf(
        ProviderItem("DeepSeek", R.string.ai_provider_deepseek, "https://api.deepseek.com/v1", "deepseek-chat", listOf("deepseek-chat", "deepseek-reasoner")),
        ProviderItem("SiliconFlow", R.string.ai_provider_siliconflow, "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct", listOf("Qwen/Qwen2.5-7B-Instruct", "deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-72B-Instruct")),
        ProviderItem("OpenAI", R.string.ai_provider_openai, "https://api.openai.com/v1", "gpt-4o-mini", listOf("gpt-4o-mini", "gpt-4o")),
        ProviderItem("OpenRouter", R.string.ai_provider_openrouter, "https://openrouter.ai/api/v1", "anthropic/claude-3.5-haiku", listOf("anthropic/claude-3.5-haiku", "openai/gpt-4o-mini")),
        ProviderItem("Gemini", R.string.ai_provider_gemini, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash", listOf("gemini-2.0-flash")),
        ProviderItem("Moonshot", R.string.ai_provider_moonshot, "https://api.moonshot.cn/v1", "moonshot-v1-8k", listOf("moonshot-v1-8k", "moonshot-v1-32k")),
        ProviderItem("Custom", R.string.ai_provider_custom, "", "", emptyList())
    )

    fun onSelectProvider(newProviderId: String) {
        if (selectedProvider == "Custom") {
            customBaseUrl = baseUrl
            customApiKey = apiKey
            customModel = model
            repository.saveCustomConfig(customBaseUrl, customApiKey, customModel)
        }
        selectedProvider = newProviderId
        when (newProviderId) {
            "DeepSeek" -> {
                baseUrl = "https://api.deepseek.com/v1"
                model = "deepseek-chat"
            }
            "SiliconFlow" -> {
                baseUrl = "https://api.siliconflow.cn/v1"
                model = "Qwen/Qwen2.5-7B-Instruct"
            }
            "OpenAI" -> {
                baseUrl = "https://api.openai.com/v1"
                model = "gpt-4o-mini"
            }
            "OpenRouter" -> {
                baseUrl = "https://openrouter.ai/api/v1"
                model = "anthropic/claude-3.5-haiku"
            }
            "Gemini" -> {
                baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai"
                model = "gemini-2.0-flash"
            }
            "Moonshot" -> {
                baseUrl = "https://api.moonshot.cn/v1"
                model = "moonshot-v1-8k"
            }
            "Custom" -> {
                baseUrl = customBaseUrl.ifBlank { "http://192.168.199.101:3000/v1" }
                apiKey = customApiKey
                model = customModel.ifBlank { "gpt-4o-mini" }
            }
        }
    }

    // 目标语言预设列表（丰富多国语言）
    val targetLanguages = listOf(
        LanguageOption("Chinese", "🇨🇳 简体中文 (Chinese)"),
        LanguageOption("Traditional Chinese", "🇭🇰 繁体中文 (Traditional Chinese)"),
        LanguageOption("English", "🇺🇸 英语 (English)"),
        LanguageOption("Japanese", "🇯🇵 日本語 (Japanese)"),
        LanguageOption("Korean", "🇰🇷 한국어 (Korean)"),
        LanguageOption("French", "🇫🇷 Français (French)"),
        LanguageOption("German", "🇩🇪 Deutsch (German)"),
        LanguageOption("Spanish", "🇪🇸 Español (Spanish)"),
        LanguageOption("Russian", "🇷🇺 Русский (Russian)"),
        LanguageOption("Italian", "🇮🇹 Italiano (Italian)"),
        LanguageOption("Portuguese", "🇵🇹 Português (Portuguese)"),
        LanguageOption("Vietnamese", "🇻🇳 Tiếng Việt (Vietnamese)"),
        LanguageOption("Thai", "🇹🇭 ไทย (Thai)"),
        LanguageOption("Arabic", "🇸🇦 العربية (Arabic)")
    )

    // 原著语言选项
    val sourceLanguages = listOf(
        LanguageOption("Auto", stringResource(R.string.ai_lang_auto_detect)),
        LanguageOption("Chinese", "🇨🇳 中文 (Chinese)"),
        LanguageOption("English", "🇺🇸 英语 (English)"),
        LanguageOption("Japanese", "🇯🇵 日本語 (Japanese)"),
        LanguageOption("Korean", "🇰🇷 한국어 (Korean)"),
        LanguageOption("French", "🇫🇷 法语 (French)"),
        LanguageOption("German", "🇩🇪 德语 (German)"),
        LanguageOption("Spanish", "🇪🇸 西班牙语 (Spanish)"),
        LanguageOption("Russian", "🇷🇺 俄语 (Russian)")
    )

    val currentProviderItem = providerList.find { it.id == selectedProvider } ?: providerList.last()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Text(
                    text = stringResource(R.string.ai_settings_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 1. 服务商预设 — 下拉菜单
                Text(
                    text = stringResource(R.string.ai_settings_provider_label),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                var providerExpanded by remember { mutableStateOf(false) }
                Box {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { providerExpanded = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(currentProviderItem.nameRes),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    DropdownMenu(
                        expanded = providerExpanded,
                        onDismissRequest = { providerExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.85f)
                    ) {
                        providerList.forEach { p ->
                            val isSelected = (p.id == selectedProvider)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(p.nameRes),
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    onSelectProvider(p.id)
                                    providerExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 2. API Base URL
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = {
                        baseUrl = it
                        if (selectedProvider == "Custom") {
                            customBaseUrl = it
                        }
                    },
                    label = { Text(stringResource(R.string.ai_settings_base_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 3. API Key
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        if (selectedProvider == "Custom") {
                            customApiKey = it
                        }
                    },
                    label = { Text(stringResource(R.string.ai_settings_api_key)) },
                    singleLine = true,
                    visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                imageVector = if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 4. Model Name + 常用推荐模型快捷标签
                OutlinedTextField(
                    value = model,
                    onValueChange = {
                        model = it
                        if (selectedProvider == "Custom") {
                            customModel = it
                        }
                    },
                    label = { Text(stringResource(R.string.ai_settings_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (currentProviderItem.popularModels.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        currentProviderItem.popularModels.forEach { popModel ->
                            val isCurrentModel = (popModel == model)
                            FilterChip(
                                selected = isCurrentModel,
                                onClick = {
                                    model = popModel
                                    if (selectedProvider == "Custom") customModel = popModel
                                },
                                label = { Text(popModel, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 5. 目标翻译语言 (Target Language) — 重点下拉！
                Text(
                    text = stringResource(R.string.ai_settings_target_lang),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                var targetMenuExpanded by remember { mutableStateOf(false) }
                val currentTargetLabel = targetLanguages.find { it.code.equals(targetLang, ignoreCase = true) }?.displayName ?: targetLang
                Box {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { targetMenuExpanded = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = currentTargetLabel,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    DropdownMenu(
                        expanded = targetMenuExpanded,
                        onDismissRequest = { targetMenuExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.85f)
                    ) {
                        targetLanguages.forEach { item ->
                            val isSelected = item.code.equals(targetLang, ignoreCase = true)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = item.displayName,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    targetLang = item.code
                                    targetMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 6. 原著语言 (Source Language) — 下拉
                Text(
                    text = stringResource(R.string.ai_settings_source_lang),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                var sourceMenuExpanded by remember { mutableStateOf(false) }
                val currentSourceLabel = sourceLanguages.find { it.code.equals(sourceLang, ignoreCase = true) }?.displayName ?: sourceLang
                Box {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { sourceMenuExpanded = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = currentSourceLabel,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    DropdownMenu(
                        expanded = sourceMenuExpanded,
                        onDismissRequest = { sourceMenuExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.85f)
                    ) {
                        sourceLanguages.forEach { item ->
                            val isSelected = item.code.equals(sourceLang, ignoreCase = true)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = item.displayName,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    sourceLang = item.code
                                    sourceMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                // 7. 测试状态反馈（提炼易懂文本）
                if (testStatus != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = testStatus ?: "",
                        fontSize = 12.sp,
                        color = if (testStatus?.startsWith("✓") == true) Color(0xFF10B981) else MaterialTheme.colorScheme.error,
                        lineHeight = 16.sp
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 8. 底部操作按钮
                val invalidKeyHint = stringResource(R.string.ai_error_key_invalid)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val currentConfig = AiConfig(
                                provider = selectedProvider,
                                baseUrl = baseUrl,
                                apiKey = apiKey,
                                model = model,
                                sourceLang = sourceLang,
                                targetLang = targetLang
                            )
                            isTesting = true
                            testStatus = null
                            coroutineScope.launch {
                                val result = LlmClient().testConnection(currentConfig)
                                isTesting = false
                                result.fold(
                                    onSuccess = { testStatus = "✓ Success! (${it.take(40)})" },
                                    onFailure = { err ->
                                        val msg = err.localizedMessage ?: ""
                                        testStatus = if (msg.contains("401") || msg.contains("Token is invalid") || msg.contains("Invalid token")) {
                                            "✗ $invalidKeyHint"
                                        } else {
                                            "✗ Error: ${msg.take(80)}"
                                        }
                                    }
                                )
                            }
                        },
                        enabled = !isTesting && apiKey.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isTesting) stringResource(R.string.ai_settings_testing) else stringResource(R.string.ai_settings_test_btn))
                    }

                    Button(
                        onClick = {
                            if (selectedProvider == "Custom") {
                                customBaseUrl = baseUrl
                                customApiKey = apiKey
                                customModel = model
                                repository.saveCustomConfig(customBaseUrl, customApiKey, customModel)
                            }
                            val newConfig = AiConfig(
                                provider = selectedProvider,
                                baseUrl = baseUrl,
                                apiKey = apiKey,
                                model = model,
                                sourceLang = sourceLang,
                                targetLang = targetLang
                            )
                            repository.saveAiConfig(newConfig)
                            onSaved(newConfig)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.ai_settings_save_btn))
                    }
                }
            }
        }
    }
}
