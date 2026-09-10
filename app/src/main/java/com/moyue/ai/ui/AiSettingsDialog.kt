package com.moyue.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.moyue.ai.data.AiCacheRepository
import com.moyue.ai.model.AiConfig
import com.moyue.ai.service.LlmClient
import com.moyue.app.R
import kotlinx.coroutines.launch

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
    var sourceLang by remember { mutableStateOf(initialConfig.sourceLang) }
    var targetLang by remember { mutableStateOf(initialConfig.targetLang) }

    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    val providers = listOf("OpenAI", "DeepSeek", "SiliconFlow", "OpenRouter", "Custom")

    fun onSelectProvider(name: String) {
        selectedProvider = name
        when (name) {
            "OpenAI" -> {
                baseUrl = "https://api.openai.com/v1"
                model = "gpt-4o-mini"
            }
            "DeepSeek" -> {
                baseUrl = "https://api.deepseek.com/v1"
                model = "deepseek-chat"
            }
            "SiliconFlow" -> {
                baseUrl = "https://api.siliconflow.cn/v1"
                model = "Qwen/Qwen2.5-7B-Instruct"
            }
            "OpenRouter" -> {
                baseUrl = "https://openrouter.ai/api/v1"
                model = "anthropic/claude-3.5-haiku"
            }
            "Custom" -> {
                if (baseUrl.isBlank()) baseUrl = "http://192.168.199.101:3000/v1"
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
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

                Spacer(modifier = Modifier.height(16.dp))

                // Provider selection chips
                Text(
                    text = stringResource(R.string.ai_settings_provider_label),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    providers.take(3).forEach { p ->
                        val selected = (p == selectedProvider)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onSelectProvider(p) }
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
                                Text(
                                    text = p,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    providers.drop(3).forEach { p ->
                        val selected = (p == selectedProvider)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onSelectProvider(p) }
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
                                Text(
                                    text = p,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Base URL
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.ai_settings_base_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // API Key
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.ai_settings_api_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Model Name
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(R.string.ai_settings_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Languages
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sourceLang,
                        onValueChange = { sourceLang = it },
                        label = { Text(stringResource(R.string.ai_settings_source_lang)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = targetLang,
                        onValueChange = { targetLang = it },
                        label = { Text(stringResource(R.string.ai_settings_target_lang)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Test Status Feedback
                if (testStatus != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = testStatus ?: "",
                        fontSize = 12.sp,
                        color = if (testStatus?.startsWith("✓") == true) Color(0xFF10B981) else MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Action buttons
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
                                    onSuccess = { testStatus = "✓ Success! (${it.take(50)})" },
                                    onFailure = { testStatus = "✗ Error: ${it.localizedMessage}" }
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
