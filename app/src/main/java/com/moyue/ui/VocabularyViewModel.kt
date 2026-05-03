package com.moyue.app.ui

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import com.moyue.app.data.BookRepository
import com.moyue.app.data.models.LLMConfig
import com.moyue.app.data.models.TTSProviderType
import com.moyue.app.data.models.Vocabulary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private var audioPlayer: MediaPlayer? = null
    private val _isSpeakingWord = MutableStateFlow<Long?>(null)
    val isSpeakingWord: StateFlow<Long?> = _isSpeakingWord.asStateFlow()

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

    /** Speak a word using the app's configured TTS provider (Edge TTS / Custom TTS) */
    fun speakWord(vocabId: Long, word: String, context: Context) {
        // Stop any current playback
        audioPlayer?.apply { if (isPlaying) stop(); release() }
        audioPlayer = null
        _isSpeakingWord.value = null

        viewModelScope.launch {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val prefs = context.getSharedPreferences("moreader_config", Context.MODE_PRIVATE)
                    val providerType = prefs.getString("tts_provider", "edge_tts") ?: "edge_tts"
                    val ttsType = try { TTSProviderType.valueOf(providerType) } catch (e: IllegalArgumentException) { TTSProviderType.EDGE_TTS }

                    val audioBytes = when (ttsType) {
                        TTSProviderType.EDGE_TTS -> {
                            val endpoint = prefs.getString("edge_endpoint", "http://powerplus.blogsyte.com:5001") ?: "http://powerplus.blogsyte.com:5001"
                            val voice = prefs.getString("edge_voice", "zh-CN-XiaoxiaoNeural") ?: "zh-CN-XiaoxiaoNeural"
                            val apiKey = prefs.getString("edge_apikey", "") ?: ""
                            fetchEdgeTTS(endpoint, voice, apiKey, word)
                        }
                        TTSProviderType.CUSTOM_TTS -> {
                            val endpoint = prefs.getString("custom_endpoint", "http://192.168.199.101:18083") ?: "http://192.168.199.101:18083"
                            val apiKey = prefs.getString("custom_apikey", "dummy") ?: "dummy"
                            val model = prefs.getString("custom_model", "moss-tts-nano") ?: "moss-tts-nano"
                            val voice = prefs.getString("custom_voice", "Lingyu") ?: "Lingyu"
                            fetchCustomTTS(endpoint, apiKey, model, voice, word)
                        }
                        TTSProviderType.AI_VOICE -> {
                            val endpoint = prefs.getString("ai_endpoint", "https://api.siliconflow.cn/v1") ?: "https://api.siliconflow.cn/v1"
                            val apiKey = prefs.getString("ai_apikey", "") ?: ""
                            val model = prefs.getString("ai_model", "fnlp/MOSS-TTSD-v0.5") ?: "fnlp/MOSS-TTSD-v0.5"
                            val voice = prefs.getString("ai_voice_id", "fnlp/MOSS-TTSD-v0.5:anna") ?: "fnlp/MOSS-TTSD-v0.5:anna"
                            fetchAITTS(endpoint, apiKey, model, voice, word)
                        }
                        else -> fetchEdgeTTS("http://powerplus.blogsyte.com:5001", "zh-CN-XiaoxiaoNeural", "", word)
                    }

                    if (audioBytes != null && audioBytes.isNotEmpty()) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            try {
                                _isSpeakingWord.value = vocabId
                                val tempFile = File.createTempFile("vocab_tts_", ".mp3", context.cacheDir)
                                tempFile.writeBytes(audioBytes)
                                audioPlayer = MediaPlayer().apply {
                                    setDataSource(tempFile.absolutePath)
                                    setOnCompletionListener {
                                        audioPlayer?.release()
                                        audioPlayer = null
                                        _isSpeakingWord.value = null
                                        tempFile.delete()
                                    }
                                    setOnErrorListener { _, _, _ ->
                                        audioPlayer?.release()
                                        audioPlayer = null
                                        _isSpeakingWord.value = null
                                        tempFile.delete()
                                        true
                                    }
                                    prepare()
                                    start()
                                }
                            } catch (e: Exception) {
                                _isSpeakingWord.value = null
                            }
                        }
                    } else {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            _isSpeakingWord.value = null
                        }
                    }
                } catch (e: Exception) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        _isSpeakingWord.value = null
                    }
                }
            }
        }
    }

    private suspend fun fetchEdgeTTS(endpoint: String, voice: String, apiKey: String, text: String): ByteArray? {
        return try {
            val json = JSONObject().apply {
                put("text", text)
                put("voice", voice)
                put("rate", "+0%")
                put("pitch", "+0Hz")
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${endpoint.removeSuffix("/")}/tts")
                .post(body)
                .apply { if (apiKey.isNotEmpty()) addHeader("X-API-Key", apiKey) }
                .build()
            val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) response.body?.bytes() else null
        } catch (e: Exception) { null }
    }

    private suspend fun fetchCustomTTS(endpoint: String, apiKey: String, model: String, voice: String, text: String): ByteArray? {
        return try {
            val json = JSONObject().apply {
                put("model", model)
                put("input", text)
                put("voice", voice)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${endpoint.removeSuffix("/")}/audio/speech")
                .post(body)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()
            val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) response.body?.bytes() else null
        } catch (e: Exception) { null }
    }

    private suspend fun fetchAITTS(endpoint: String, apiKey: String, model: String, voice: String, text: String): ByteArray? {
        return try {
            val json = JSONObject().apply {
                put("model", model)
                put("input", text)
                put("voice", voice)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${endpoint.removeSuffix("/")}/audio/speech")
                .post(body)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()
            val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) response.body?.bytes() else null
        } catch (e: Exception) { null }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer?.apply { if (isPlaying) stop(); release() }
        audioPlayer = null
    }

    fun fetchDefinition(vocabId: Long, word: String, context: Context, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val config = getStoredLLMConfig(context)
                    if (config.apiKey.isEmpty()) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onComplete(false, "请先在设置中配置 AI API Key")
                        }
                        return@withContext
                    }
                    if (config.endpoint.isEmpty()) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onComplete(false, "请先配置 AI 服务端点 (Endpoint)")
                        }
                        return@withContext
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
                        put("model", config.model.ifEmpty { "gpt-3.5-turbo" })
                        put("messages", messages)
                        put("temperature", 0.3)
                        put("max_tokens", 500)
                        put("stream", false)
                    }

                    val client = OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build()

                    val endpoint = config.endpoint.trimEnd('/')
                    val url = if (endpoint.contains("/chat/completions")) endpoint else "$endpoint/chat/completions"

                    val request = Request.Builder()
                        .url(url)
                        .post(body.toString().toRequestBody("application/json".toMediaType()))
                        .addHeader("Authorization", "Bearer ${config.apiKey}")
                        .addHeader("Content-Type", "application/json")
                        .build()

                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string()

                    if (!response.isSuccessful) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onComplete(false, "获取释义失败 (HTTP ${response.code}): ${responseBody?.take(100) ?: "无响应"}")
                        }
                        return@withContext
                    }

                    if (responseBody.isNullOrEmpty()) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onComplete(false, "获取释义失败：服务器返回空响应")
                        }
                        return@withContext
                    }

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
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                onComplete(true, "获取释义成功")
                            }
                            return@withContext
                        }
                    }

                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onComplete(false, "获取释义失败：AI 返回格式无法解析")
                    }
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: e.javaClass.simpleName
                onComplete(false, "获取释义失败: $errorMsg")
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
