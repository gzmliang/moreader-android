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

    /** Detect if word is primarily Chinese */
    private fun isChinese(text: String): Boolean {
        return text.any { it in '\u4e00'..'\u9fff' }
    }

    /** Parse AI response JSON for English word */
    private data class DefinitionResult(
        val pronunciation: String?,
        val partOfSpeech: String?,
        val chineseDef: String?,
        val englishDef: String?,
        val wordForms: String?,
        val exampleJson: String?
    )

    private fun parseEnglishResponse(content: String): DefinitionResult {
        // Expected JSON:
        // {
        //   "pronunciation": "/rɪˈzɪliəns/",
        //   "partOfSpeech": "n.",
        //   "chineseDef": "1. 恢复力；弹力\n2. 适应力",
        //   "englishDef": "1. The capacity to recover quickly from difficulties",
        //   "wordForms": ["resilient (adj.) 有弹性的", "resiliently (adv.) 有适应力地"],
        //   "example": {"text": "She showed great resilience after the setback.", "translation": "她在挫折后表现出了强大的适应力。"}
        // }
        return try {
            val cleaned = content.replace("```json", "").replace("```", "").trim()
            val json = JSONObject(cleaned)
            val pronunciation = json.optString("pronunciation", "").takeIf { it.isNotEmpty() }
            val partOfSpeech = json.optString("partOfSpeech", "").takeIf { it.isNotEmpty() }
            val chineseDef = json.optString("chineseDef", "").takeIf { it.isNotEmpty() }
            val englishDef = json.optString("englishDef", "").takeIf { it.isNotEmpty() }
            
            val wordForms = try {
                val arr = json.optJSONArray("wordForms")
                if (arr != null) {
                    val list = (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotEmpty() }
                    if (list.isNotEmpty()) org.json.JSONArray(list).toString() else null
                } else null
            } catch (e: Exception) { null }
            
            val exampleJson = try {
                val ex = json.optJSONObject("example")
                if (ex != null && ex.has("text")) ex.toString() else null
            } catch (e: Exception) { null }
            
            DefinitionResult(pronunciation, partOfSpeech, chineseDef, englishDef, wordForms, exampleJson)
        } catch (e: Exception) {
            // Fallback: try old regex format
            DefinitionResult(
                extractField(content, "音标"),
                extractField(content, "词性"),
                extractField(content, "释义"),
                null,
                null,
                null
            )
        }
    }

    private fun parseChineseResponse(content: String): DefinitionResult {
        // Expected JSON:
        // {
        //   "pronunciation": "jiān rèn",
        //   "partOfSpeech": "adj.",
        //   "chineseDef": "1. 坚固而有韧性，不易折断\n2. 比喻意志坚强",
        //   "englishDef": "tough and tenacious; resilient",
        //   "wordForms": ["坚韧不拔", "坚韧性"],
        //   "example": {"text": "他的意志十分坚韧。", "translation": "He has a resilient will."}
        // }
        return try {
            val cleaned = content.replace("```json", "").replace("```", "").trim()
            val json = JSONObject(cleaned)
            val pronunciation = json.optString("pronunciation", "").takeIf { it.isNotEmpty() }
            val partOfSpeech = json.optString("partOfSpeech", "").takeIf { it.isNotEmpty() }
            val chineseDef = json.optString("chineseDef", "").takeIf { it.isNotEmpty() }
            val englishDef = json.optString("englishDef", "").takeIf { it.isNotEmpty() }
            
            val wordForms = try {
                val arr = json.optJSONArray("wordForms")
                if (arr != null) {
                    val list = (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotEmpty() }
                    if (list.isNotEmpty()) org.json.JSONArray(list).toString() else null
                } else null
            } catch (e: Exception) { null }
            
            val exampleJson = try {
                val ex = json.optJSONObject("example")
                if (ex != null && ex.has("text")) ex.toString() else null
            } catch (e: Exception) { null }
            
            DefinitionResult(pronunciation, partOfSpeech, chineseDef, englishDef, wordForms, exampleJson)
        } catch (e: Exception) {
            DefinitionResult(null, null, null, null, null, null)
        }
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

                    val chinese = isChinese(word)
                    
                    val systemPrompt = if (chinese) {
                        "你是专业的汉语词典助手，擅长《现代汉语词典》风格的释义。"
                    } else {
                        "You are a professional bilingual dictionary assistant, skilled in Oxford-style English definitions."
                    }

                    val prompt = if (chinese) {
                        """你是专业双语词典助手。请为以下**中文词语**提供《现代汉语词典》风格的双语释义。

原词：【$word】

⚠️ 重要规则：
1. 必须在 JSON 中**同时提供中文释义 AND 英文释义**，缺一不可
2. 中文释义参照《现代汉语词典》风格，简洁准确
3. 英文释义用简明英语解释该词含义
4. 所有字段必须填写，不能为空

请严格按以下 JSON 格式返回（不要输出 JSON 以外的任何文字）：
{
  "pronunciation": "拼音（如 jiān rèn）",
  "partOfSpeech": "词性（如 形容词 / 动词 / 名词 / 副词）",
  "chineseDef": "1. 中文释义1\n2. 中文释义2",
  "englishDef": "1. English definition 1\n2. English definition 2",
  "wordForms": ["组词1（如 坚韧不拔）", "组词2（如 坚韧性）"],
  "example": {"text": "中文例句", "translation": "English translation of the example"}
}

示例（词语"坚韧"）：
{
  "pronunciation": "jiān rèn",
  "partOfSpeech": "形容词",
  "chineseDef": "1. 坚固而有韧性，不易折断\n2. 比喻意志坚强，不屈不挠",
  "englishDef": "1. Firm and tough; not easily broken\n2. Strong-willed and unyielding",
  "wordForms": ["坚韧不拔（firm and indomitable）", "坚韧性（toughness; resilience）"],
  "example": {"text": "他的意志十分坚韧。", "translation": "He has a very resilient will."}
}"""
                    } else {
                        """You are a professional bilingual dictionary assistant. Please provide an Oxford-style bilingual definition for this **English word**.

Original word: 【$word】

⚠️ IMPORTANT RULES:
1. You MUST provide BOTH Chinese definition AND English definition — neither can be empty
2. Chinese definition: clear, accurate Chinese translation of the word's meaning
3. English definition: Oxford-style definition in English
4. All fields must be filled, none can be empty

Please respond strictly in the following JSON format (no text outside JSON):
{
  "pronunciation": "IPA phonetic (e.g., /rɪˈzɪliəns/)",
  "partOfSpeech": "part of speech (e.g., noun / verb / adjective / adverb)",
  "chineseDef": "1. 中文释义1\n2. 中文释义2",
  "englishDef": "1. English definition 1\n2. English definition 2",
  "wordForms": ["derivative1 (词性) 中文意思", "derivative2 (词性) 中文意思"],
  "example": {"text": "English example sentence", "translation": "中文翻译"}
}

Example (word "resilience"):
{
  "pronunciation": "/rɪˈzɪliəns/",
  "partOfSpeech": "noun",
  "chineseDef": "1. 恢复力；弹力\n2. 适应力；复原力",
  "englishDef": "1. The capacity to recover quickly from difficulties; toughness\n2. The ability of a substance or object to spring back into shape; elasticity",
  "wordForms": ["resilient (adj.) 有弹性的；适应力强的", "resiliently (adv.) 有适应力地"],
  "example": {"text": "She showed great resilience after the setback.", "translation": "她在挫折后展现出了强大的适应力。"}
}"""
                    }

                    val messages = JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                        put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                    }

                    val body = JSONObject().apply {
                        put("model", config.model.ifEmpty { "gpt-3.5-turbo" })
                        put("messages", messages)
                        put("temperature", 0.3)
                        put("max_tokens", 800)
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
                            val result = if (chinese) parseChineseResponse(content) else parseEnglishResponse(content)
                            if (result != null && (result.chineseDef != null || result.englishDef != null)) {
                                repository.updateVocabularyStructured(
                                    vocabId,
                                    result.pronunciation,
                                    result.partOfSpeech,
                                    result.chineseDef,
                                    result.englishDef,
                                    result.wordForms,
                                    result.exampleJson
                                )
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    onComplete(true, "获取释义成功")
                                }
                                return@withContext
                            }
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
