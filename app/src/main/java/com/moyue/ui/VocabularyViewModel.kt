package com.moyue.app.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import com.moyue.app.data.BookRepository
import com.moyue.app.data.models.LLMConfig
import com.moyue.app.data.models.TTSProviderType
import com.moyue.app.data.models.Vocabulary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
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

    init {
        refreshPlanNames()
    }

    private var audioPlayer: MediaPlayer? = null
    private var systemTts: TextToSpeech? = null
    private var systemTtsReady = false
    private var pendingSystemSpeak: String? = null
    private var systemTtsInitLock = java.util.concurrent.locks.ReentrantLock()
    private val _isSpeakingWord = MutableStateFlow<Long?>(null)
    val isSpeakingWord: StateFlow<Long?> = _isSpeakingWord.asStateFlow()

    // Plan management — persisted via SharedPreferences so created plans survive restarts
    companion object { const val DEFAULT_PLAN = "默认" }
    private val prefsKey = "vocab_notebook_plans"
    private lateinit var prefs: SharedPreferences
    private fun initPrefs(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.getSharedPreferences("moreader_vocab", Context.MODE_PRIVATE)
            val saved = prefs.getStringSet(prefsKey, setOf(DEFAULT_PLAN)) ?: setOf(DEFAULT_PLAN)
            _createdPlans.value = saved
            refreshPlanNames()
        }
    }

    /** Load plan list from SharedPreferences — call once on screen init */
    fun loadSharedPrefsPlans(context: Context) {
        initPrefs(context)
    }

    private val _currentPlan = MutableStateFlow(DEFAULT_PLAN)
    val currentPlan: StateFlow<String> = _currentPlan.asStateFlow()

    private val _createdPlans = MutableStateFlow(setOf(DEFAULT_PLAN))

    private val _planNames = MutableStateFlow(listOf(DEFAULT_PLAN))
    val planNames: StateFlow<List<String>> = _planNames.asStateFlow()

    private fun refreshPlanNames() {
        viewModelScope.launch {
            val dbPlans = repository.getPlanNamesOnce()
            _planNames.value = (dbPlans.toSet() + _createdPlans.value).sorted()
        }
    }

    val vocabulary: StateFlow<List<Vocabulary>> = _currentPlan.flatMapLatest { plan ->
        repository.getVocabularyByPlan(plan)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun switchPlan(plan: String) { _currentPlan.value = plan }

    fun createPlan(context: Context, name: String) {
        initPrefs(context)
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val newSet = _createdPlans.value + trimmed
        _createdPlans.value = newSet
        prefs.edit().putStringSet(prefsKey, newSet).apply()
        _currentPlan.value = trimmed
        refreshPlanNames()
    }

    fun deletePlan(context: Context, name: String) {
        if (name == DEFAULT_PLAN) return
        initPrefs(context)
        viewModelScope.launch {
            repository.deleteVocabularyByPlan(name)
            val newSet = _createdPlans.value - name
            _createdPlans.value = newSet
            prefs.edit().putStringSet(prefsKey, newSet).apply()
            if (_currentPlan.value == name) _currentPlan.value = DEFAULT_PLAN
            refreshPlanNames()
        }
    }

    /**
     * Add a custom word (typed by user) to vocabulary without requiring a book.
     * Returns true if added, false if already exists.
     */
    fun addCustomWord(word: String, callback: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val trimmed = word.trim()
            if (trimmed.isEmpty()) { callback(false, "请输入单词"); return@launch }
            val exists = repository.isWordExists(trimmed)
            if (exists) {
                callback(false, "单词已在生词本中")
                return@launch
            }
            val vocab = Vocabulary(
                word = trimmed,
                plan = _currentPlan.value,
                createdAt = System.currentTimeMillis()
            )
            repository.insertVocabulary(vocab)
            callback(true, "已添加：$trimmed")
        }
    }

    fun deleteVocabulary(id: Long) {
        viewModelScope.launch {
            repository.deleteVocabulary(id)
        }
    }

    private fun ensureSystemTts(context: Context): TextToSpeech? {
        systemTtsInitLock.lock()
        try {
            if (systemTts == null) {
                systemTts = TextToSpeech(context) { status ->
                    systemTtsReady = (status == TextToSpeech.SUCCESS)
                    if (systemTtsReady) {
                        pendingSystemSpeak?.let { text ->
                            pendingSystemSpeak = null
                            speakWithSystemTts(text)
                        }
                    }
                }
            }
            return systemTts
        } finally {
            systemTtsInitLock.unlock()
        }
    }

    private fun speakWithSystemTts(text: String) {
        if (!systemTtsReady) {
            pendingSystemSpeak = text
            return
        }
        // Auto-detect language: Chinese chars -> Chinese TTS, else English TTS
        val hasChinese = text.any { it in '一'..'鿿' }
        systemTts?.language = if (hasChinese) java.util.Locale.CHINESE else java.util.Locale.US
        systemTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onDone(utteranceId: String?) {}
            override fun onError(utteranceId: String?) {}
            override fun onStart(utteranceId: String?) {}
        })
        systemTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vocab_tts")
    }

    /** Speak a word using the flashcard-specific TTS provider */
    fun speakWord(vocabId: Long, word: String, context: Context) {
        // Stop any current playback
        audioPlayer?.apply { if (isPlaying) stop(); release() }
        audioPlayer = null
        // Stop any pending system TTS
        systemTts?.stop()
        _isSpeakingWord.value = null

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val prefs = context.getSharedPreferences("moreader_config", Context.MODE_PRIVATE)
                    val providerType = prefs.getString("flashcard_tts_provider", "edge_tts") ?: "edge_tts"
                    val ttsType = try { TTSProviderType.valueOf(providerType) } catch (e: IllegalArgumentException) { TTSProviderType.EDGE_TTS }

                    when (ttsType) {
                        TTSProviderType.SYSTEM -> {
                            withContext(Dispatchers.Main) {
                                _isSpeakingWord.value = vocabId
                                ensureSystemTts(context)
                                speakWithSystemTts(word)
                                // Clear after a short delay (System TTS doesn't notify completion easily)
                                kotlinx.coroutines.delay(2000)
                                _isSpeakingWord.value = null
                            }
                            return@withContext
                        }
                        TTSProviderType.EDGE_TTS -> {
                            val endpoint = prefs.getString("edge_endpoint", "http://192.168.199.159:5001") ?: "http://192.168.199.159:5001"
                            var voice = prefs.getString("edge_voice", "zh-CN-XiaoxiaoNeural") ?: "zh-CN-XiaoxiaoNeural"
                            val isChinese = word.any { it in '一'..'鿿' }
                            if (isChinese && !voice.startsWith("zh-")) voice = "zh-CN-XiaoxiaoNeural"
                            else if (!isChinese && voice.startsWith("zh-")) voice = "en-US-GuyNeural"
                            val apiKey = prefs.getString("edge_apikey", "") ?: ""
                            fetchAndPlay(vocabId, word, context, fetchEdgeTTS(endpoint, voice, apiKey, word))
                        }
                        TTSProviderType.CUSTOM_TTS -> {
                            val endpoint = prefs.getString("custom_endpoint", "http://192.168.199.101:18083") ?: "http://192.168.199.101:18083"
                            val apiKey = prefs.getString("custom_apikey", "dummy") ?: "dummy"
                            val model = prefs.getString("custom_model", "moss-tts-nano") ?: "moss-tts-nano"
                            val voice = prefs.getString("custom_voice", "Lingyu") ?: "Lingyu"
                            fetchAndPlay(vocabId, word, context, fetchCustomTTS(endpoint, apiKey, model, voice, word))
                        }
                        TTSProviderType.AI_VOICE -> {
                            val endpoint = prefs.getString("ai_endpoint", "https://api.siliconflow.cn/v1") ?: "https://api.siliconflow.cn/v1"
                            val apiKey = prefs.getString("ai_apikey", "") ?: ""
                            val model = prefs.getString("ai_model", "fnlp/MOSS-TTSD-v0.5") ?: "fnlp/MOSS-TTSD-v0.5"
                            val voice = prefs.getString("ai_voice_id", "fnlp/MOSS-TTSD-v0.5:anna") ?: "fnlp/MOSS-TTSD-v0.5:anna"
                            fetchAndPlay(vocabId, word, context, fetchAITTS(endpoint, apiKey, model, voice, word))
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _isSpeakingWord.value = null
                    }
                }
            }
        }
    }

    private suspend fun fetchAndPlay(vocabId: Long, word: String, context: Context, audioBytes: ByteArray?) {
        if (audioBytes != null && audioBytes.isNotEmpty()) {
            withContext(Dispatchers.Main) {
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
            withContext(Dispatchers.Main) {
                _isSpeakingWord.value = null
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
        systemTts?.stop()
        systemTts?.shutdown()
        systemTts = null
    }

    /** Detect if word is primarily Chinese */
    private fun isChinese(text: String): Boolean {
        return text.any { it in '一'..'鿿' }
    }

    /** Detect if word is a single Chinese character */
    private fun isSingleChar(text: String): Boolean {
        return text.length == 1 && text[0] in '一'..'鿿'
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
                            onComplete(false, context.getString(com.moyue.app.R.string.error_vocab_config_api_key))
                        }
                        return@withContext
                    }
                    if (config.endpoint.isEmpty()) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onComplete(false, context.getString(com.moyue.app.R.string.error_vocab_config_endpoint))
                        }
                        return@withContext
                    }

                    val chinese = isChinese(word)
                    val singleChar = isSingleChar(word)
                    
                    val systemPrompt = if (chinese) {
                        "你是专业的汉语词典助手，擅长《现代汉语词典》风格的释义。"
                    } else {
                        "You are a professional bilingual dictionary assistant, skilled in Oxford-style English definitions."
                    }

                    val prompt = if (singleChar) {
                        """你是专业双语词典助手。请为以下**单个汉字**提供简明双语释义。

原字：【$word】

⚠️ 重要规则：
1. 必须同时提供中文释义 AND 英文释义
2. 拼音必须准确（带声调）
3. 组词给2个最常见的搭配

请严格按以下 JSON 格式返回（不要输出 JSON 以外的任何文字）：
{
  "pronunciation": "拼音（如 xué）",
  "partOfSpeech": "词性（如 名词 / 动词 / 形容词）",
  "chineseDef": "1. 中文释义1\n2. 中文释义2",
  "englishDef": "1. English definition 1\n2. English definition 2",
  "wordForms": ["组词1（如 学习）", "组词2（如 学校）"],
  "example": {"text": "中文例句", "translation": "English translation"}
}

示例（字"学"）：
{
  "pronunciation": "xué",
  "partOfSpeech": "动词",
  "chineseDef": "1. 效法、模仿他人而获得知识或技能\n2. 研究、钻研",
  "englishDef": "1. To study; to learn\n2. To imitate; to follow as a model",
  "wordForms": ["学习（study; learn）", "学校（school）"],
  "example": {"text": "他正在学习汉语。", "translation": "He is learning Chinese."}
}"""
                    } else if (chinese) {
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
                            onComplete(false, context.getString(com.moyue.app.R.string.error_vocab_fetch_fail_http, response.code, responseBody?.take(100) ?: ""))
                        }
                        return@withContext
                    }

                    if (responseBody.isNullOrEmpty()) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onComplete(false, context.getString(com.moyue.app.R.string.error_vocab_fetch_fail_empty))
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
                                    onComplete(true, context.getString(com.moyue.app.R.string.error_vocab_fetch_success))
                                }
                                return@withContext
                            }
                        }
                    }

                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onComplete(false, context.getString(com.moyue.app.R.string.error_vocab_fetch_parse_fail))
                    }
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: e.javaClass.simpleName
                onComplete(false, context.getString(com.moyue.app.R.string.error_vocab_fetch_fail_generic, errorMsg))
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
                    onComplete(false, context.getString(com.moyue.app.R.string.error_vocab_empty_list))
                    return@launch
                }

                val fileName: String
                val content: String
                val mimeType: String

                when (format.lowercase()) {
                    "md", "markdown" -> {
                        fileName = "moreader_vocabulary_${System.currentTimeMillis()}.md"
                        content = generateMarkdown(vocabList, context)
                        mimeType = "text/markdown"
                    }
                    else -> {
                        fileName = "moreader_vocabulary_${System.currentTimeMillis()}.csv"
                        content = generateCsv(vocabList, context)
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
                    putExtra(Intent.EXTRA_SUBJECT, context.getString(com.moyue.app.R.string.vocab_export_subject))
                    putExtra(Intent.EXTRA_TEXT, context.getString(com.moyue.app.R.string.vocab_export_body, vocabList.size))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(shareIntent, context.getString(com.moyue.app.R.string.vocab_export_chooser_title))
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)

                onComplete(true, context.getString(com.moyue.app.R.string.vocab_export_success, vocabList.size))

            } catch (e: Exception) {
                onComplete(false, context.getString(com.moyue.app.R.string.error_vocab_export_fail, e.message ?: ""))
            }
        }
    }

    private fun generateCsv(vocabList: List<Vocabulary>, context: Context): String {
        return buildString {
            appendLine(context.getString(com.moyue.app.R.string.vocab_export_csv_header))
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

    private fun generateMarkdown(vocabList: List<Vocabulary>, context: Context): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return buildString {
            appendLine(context.getString(com.moyue.app.R.string.vocab_export_md_title))
            appendLine()
            appendLine(context.getString(com.moyue.app.R.string.vocab_export_md_time, dateFormat.format(Date())))
            appendLine(context.getString(com.moyue.app.R.string.vocab_export_md_count, vocabList.size))
            appendLine()
            appendLine("---")
            appendLine()

            vocabList.forEachIndexed { index, vocab ->
                appendLine("## ${index + 1}. ${vocab.word}")
                appendLine()

                if (vocab.pronunciation != null) {
                    appendLine(context.getString(com.moyue.app.R.string.vocab_export_md_pronunciation, vocab.pronunciation))
                }

                if (vocab.partOfSpeech != null) {
                    appendLine(context.getString(com.moyue.app.R.string.vocab_export_md_pos, vocab.partOfSpeech))
                }

                if (vocab.definition != null) {
                    appendLine(context.getString(com.moyue.app.R.string.vocab_export_md_def, vocab.definition))
                }

                if (vocab.example != null) {
                    appendLine()
                    appendLine(context.getString(com.moyue.app.R.string.vocab_export_md_example, vocab.example))
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
