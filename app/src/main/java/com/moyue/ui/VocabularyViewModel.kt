package com.moyue.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import com.moyue.app.data.BookRepository
import com.moyue.app.data.models.LLMConfig
import com.moyue.app.data.models.Vocabulary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class VocabularyViewModel(
    private val repository: BookRepository
) : ViewModel() {

    val vocabulary: StateFlow<List<Vocabulary>> = repository.getAllVocabulary()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun deleteVocabulary(id: Long) {
        viewModelScope.launch {
            repository.deleteVocabulary(id)
        }
    }

    fun fetchDefinition(vocabId: Long, word: String, context: Context, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val config = getStoredLLMConfig(context)
                if (config.apiKey.isEmpty()) {
                    onComplete(false, "请先配置 API Key")
                    return@launch
                }

                val prompt = """你是词典助手。请为以下单词提供详细信息：

单词：$word

请按以下格式输出：
**音标**：[IPA音标]
**词性**：名词/动词/形容词等
**释义**：简明的中文释义
**例句**：一个简单例句及其中文翻译

直接输出内容，不要解释。"""

                val messages = JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", "你是专业的英语词典助手") })
                    put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                }

                val body = JSONObject().apply {
                    put("model", config.model)
                    put("messages", messages)
                    put("temperature", 0.3)
                    put("max_tokens", 500)
                    put("stream", false)
                }

                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url("${config.endpoint.trimEnd('/')}/chat/completions")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer ${config.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        val json = JSONObject(responseBody)
                        val choices = json.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val content = choices.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")
                            if (!content.isNullOrEmpty()) {
                                val pronunciation = extractField(content, "音标")
                                val partOfSpeech = extractField(content, "词性")
                                val definition = extractField(content, "释义")
                                val example = extractField(content, "例句")

                                repository.updateVocabularyDefinition(vocabId, pronunciation, partOfSpeech, definition, example)
                                onComplete(true, "获取释义成功")
                                return@launch
                            }
                        }
                    }
                }
                onComplete(false, "获取释义失败")
            } catch (e: Exception) {
                onComplete(false, "获取释义失败: ${e.message}")
            }
        }
    }

    private fun getStoredLLMConfig(context: Context): LLMConfig {
        val prefs = context.getSharedPreferences("moreader_config", Context.MODE_PRIVATE)
        return LLMConfig(
            provider = prefs.getString("llm_provider", "custom") ?: "custom",
            apiKey = prefs.getString("llm_apikey", "") ?: "",
            endpoint = prefs.getString("llm_endpoint", "") ?: "",
            model = prefs.getString("llm_model", "") ?: ""
        )
    }

    private fun extractField(content: String, field: String): String? {
        val pattern = "\\*\\*$field\\*\\*[:：]\\s*(.+?)(?=\\n\\*\\*|$)"
        val regex = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.DOTALL)
        val matcher = regex.matcher(content)
        return if (matcher.find()) {
            matcher.group(1)?.trim()?.replace("[", "")?.replace("]", "")
        } else null
    }

    fun exportVocabulary(context: Context, format: String, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val vocabList = vocabulary.value
                if (vocabList.isEmpty()) {
                    onComplete(false, "单词本为空")
                    return@launch
                }

                val fileName: String
                val content: String
                val mimeType: String

                when (format.lowercase()) {
                    "md", "markdown" -> {
                        fileName = "moreader_vocabulary_${System.currentTimeMillis()}.md"
                        content = generateMarkdown(vocabList)
                        mimeType = "text/markdown"
                    }
                    else -> {
                        fileName = "moreader_vocabulary_${System.currentTimeMillis()}.csv"
                        content = generateCsv(vocabList)
                        mimeType = "text/csv"
                    }
                }

                val file = File(context.cacheDir, fileName)
                file.writeText(content, Charsets.UTF_8)

                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "墨阅单词本导出")
                    putExtra(Intent.EXTRA_TEXT, "共导出 ${vocabList.size} 个单词")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(shareIntent, "分享单词本")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)

                onComplete(true, "成功导出 ${vocabList.size} 个单词")

            } catch (e: Exception) {
                onComplete(false, "导出失败: ${e.message}")
            }
        }
    }

    private fun generateCsv(vocabList: List<Vocabulary>): String {
        return buildString {
            appendLine("单词,音标,词性,释义,例句,添加时间")
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            vocabList.forEach { vocab ->
                val word = escapeCsv(vocab.word)
                val pronunciation = escapeCsv(vocab.pronunciation ?: "")
                val partOfSpeech = escapeCsv(vocab.partOfSpeech ?: "")
                val definition = escapeCsv(vocab.definition ?: "")
                val example = escapeCsv(vocab.example ?: "")
                val date = dateFormat.format(Date(vocab.createdAt))
                appendLine("$word,$pronunciation,$partOfSpeech,$definition,$example,$date")
            }
        }
    }

    private fun generateMarkdown(vocabList: List<Vocabulary>): String {
        return buildString {
            appendLine("# 墨阅单词本")
            appendLine()
            appendLine("导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}")
            appendLine("单词数量: ${vocabList.size}")
            appendLine()
            appendLine("---")
            appendLine()

            vocabList.forEachIndexed { index, vocab ->
                appendLine("## ${index + 1}. ${vocab.word}")
                appendLine()

                if (vocab.pronunciation != null) {
                    appendLine("**音标**: ${vocab.pronunciation}")
                }

                if (vocab.partOfSpeech != null) {
                    appendLine("**词性**: ${vocab.partOfSpeech}")
                }

                if (vocab.definition != null) {
                    appendLine("**释义**: ${vocab.definition}")
                }

                if (vocab.example != null) {
                    appendLine()
                    appendLine("**例句**: ${vocab.example}")
                }

                appendLine()
                appendLine("---")
                appendLine()
            }
        }
    }

    private fun escapeCsv(value: String): String {
        return when {
            value.contains(",") || value.contains("\"") || value.contains("\n") -> {
                "\"${value.replace("\"", "\"\"")}\""
            }
            else -> value
        }
    }
}

class VocabularyViewModelFactory(
    private val repository: BookRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VocabularyViewModel::class.java)) {
            return VocabularyViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
