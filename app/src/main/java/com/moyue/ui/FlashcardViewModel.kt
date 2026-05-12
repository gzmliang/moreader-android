package com.moyue.app.ui

import android.content.Context
import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.moyue.app.data.BookRepository
import com.moyue.app.data.FlashcardDataStore
import com.moyue.app.data.FlashcardDataStore.Flashcard
import com.moyue.app.data.FlashcardDataStore.Companion.INTERVALS
import com.moyue.app.data.models.TTSProviderType
import com.moyue.app.data.models.Vocabulary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class FlashcardUiState(
    val allFlashcards: List<Flashcard> = emptyList(),
    val dueCount: Int = 0,
    // Review mode
    val isReviewMode: Boolean = false,
    val dueFlashcards: List<Flashcard> = emptyList(),
    val currentCardIndex: Int = 0,
    val isFlipped: Boolean = false,
    val sessionReviewed: Int = 0,
    val sessionRemembered: Int = 0,
    val reviewComplete: Boolean = false,
)

class FlashcardViewModel(
    private val dataStore: FlashcardDataStore,
    private val repository: BookRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FlashcardUiState())
    val uiState: StateFlow<FlashcardUiState> = _uiState.asStateFlow()

    private var audioPlayer: MediaPlayer? = null

    init {
        refreshAll()
    }

    fun refreshAll() {
        viewModelScope.launch {
            val all = dataStore.getAllFlashcards()
            val dueCount = dataStore.getDueFlashcards().size
            _uiState.update { it.copy(allFlashcards = all, dueCount = dueCount) }
        }
    }

    /** Import a single vocabulary word to flashcard */
    suspend fun importFromVocabulary(vocab: Vocabulary): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (dataStore.isWordExists(vocab.word)) return@withContext false

        // Parse example JSON if available
        var exampleText: String? = null
        var exampleTranslation: String? = null
        try {
            if (!vocab.exampleJson.isNullOrEmpty()) {
                val ex = JSONObject(vocab.exampleJson)
                exampleText = ex.optString("text", null)
                exampleTranslation = ex.optString("translation", null)
            }
        } catch (_: Exception) {}

        val card = Flashcard(
            id = dataStore.generateId(),
            word = vocab.word,
            pronunciation = vocab.pronunciation,
            partOfSpeech = vocab.partOfSpeech,
            chineseDef = vocab.chineseDef,
            englishDef = vocab.englishDef,
            exampleText = exampleText,
            exampleTranslation = exampleTranslation,
            dueDate = System.currentTimeMillis(), // due immediately
        )
        dataStore.addFlashcard(card)
        refreshAll()
        return@withContext true
    }

    /** Batch import vocabulary words */
    suspend fun batchImportFromVocabulary(vocabs: List<Vocabulary>): Pair<Int, Int> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        var imported = 0
        var skipped = 0
        vocabs.forEach { vocab ->
            if (!dataStore.isWordExists(vocab.word)) {
                var exampleText: String? = null
                var exampleTranslation: String? = null
                try {
                    if (!vocab.exampleJson.isNullOrEmpty()) {
                        val ex = JSONObject(vocab.exampleJson)
                        exampleText = ex.optString("text", null)
                        exampleTranslation = ex.optString("translation", null)
                    }
                } catch (_: Exception) {}

                val card = Flashcard(
                    id = dataStore.generateId(),
                    word = vocab.word,
                    pronunciation = vocab.pronunciation,
                    partOfSpeech = vocab.partOfSpeech,
                    chineseDef = vocab.chineseDef,
                    englishDef = vocab.englishDef,
                    exampleText = exampleText,
                    exampleTranslation = exampleTranslation,
                    dueDate = System.currentTimeMillis(),
                )
                dataStore.addFlashcard(card)
                imported++
            } else {
                skipped++
            }
        }
        refreshAll()
        return@withContext Pair(imported, skipped)
    }

    fun removeFlashcard(id: Long) {
        viewModelScope.launch {
            dataStore.deleteFlashcard(id)
            refreshAll()
        }
    }

    fun resetFlashcard(id: Long) {
        viewModelScope.launch {
            val all = dataStore.getAllFlashcards()
            val card = all.find { it.id == id } ?: return@launch
            val updated = card.copy(
                intervalMinutes = 0, repetition = 0,
                dueDate = System.currentTimeMillis()
            )
            dataStore.updateFlashcard(updated)
            refreshAll()
        }
    }

    /** Reset all flashcards back to new card state */
    fun resetAllFlashcards() {
        viewModelScope.launch {
            val all = dataStore.getAllFlashcards()
            val now = System.currentTimeMillis()
            all.forEach { card ->
                val updated = card.copy(intervalMinutes = 0, repetition = 0, dueDate = now)
                dataStore.updateFlashcard(updated)
            }
            refreshAll()
        }
    }

    // ========================
    // Review Mode
    // ========================

    fun startReview() {
        viewModelScope.launch {
            val dueCards = dataStore.getDueFlashcards()
            _uiState.update {
                it.copy(
                    isReviewMode = true,
                    dueFlashcards = dueCards,
                    currentCardIndex = 0,
                    isFlipped = false,
                    sessionReviewed = 0,
                    sessionRemembered = 0,
                    reviewComplete = false
                )
            }
        }
    }

    fun exitReview() {
        refreshAll()
        _uiState.update {
            it.copy(
                isReviewMode = false,
                currentCardIndex = 0,
                isFlipped = false,
                reviewComplete = false
            )
        }
    }

    fun flipCard() {
        _uiState.update { it.copy(isFlipped = !it.isFlipped) }
    }

    fun nextCard() {
        val state = _uiState.value
        if (state.currentCardIndex + 1 < state.dueFlashcards.size) {
            _uiState.update {
                it.copy(currentCardIndex = it.currentCardIndex + 1, isFlipped = false)
            }
        } else {
            _uiState.update { it.copy(reviewComplete = true) }
        }
    }

    /** Mark card as remembered (spaced repetition) */
    fun markRemembered() {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.dueFlashcards.isEmpty() || state.currentCardIndex >= state.dueFlashcards.size) return@launch
            val card = state.dueFlashcards[state.currentCardIndex]

            val newRepetition = card.repetition + 1
            val intervalIndex = newRepetition.coerceAtMost(INTERVALS.lastIndex)
            val newInterval = INTERVALS[intervalIndex]
            val newDueDate = System.currentTimeMillis() + newInterval * 60 * 1000

            val updated = card.copy(
                intervalMinutes = newInterval,
                repetition = newRepetition,
                dueDate = newDueDate,
            )
            dataStore.updateFlashcard(updated)

            _uiState.update {
                it.copy(sessionReviewed = it.sessionReviewed + 1, sessionRemembered = it.sessionRemembered + 1)
            }
            nextCard()
        }
    }

    /** Mark card as forgotten (reset interval) */
    fun markForgotten() {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.dueFlashcards.isEmpty() || state.currentCardIndex >= state.dueFlashcards.size) return@launch
            val card = state.dueFlashcards[state.currentCardIndex]

            val newDueDate = System.currentTimeMillis() + 1 * 60 * 1000
            val updated = card.copy(
                intervalMinutes = 1,
                repetition = 0,
                dueDate = newDueDate,
            )
            dataStore.updateFlashcard(updated)

            _uiState.update { it.copy(sessionReviewed = it.sessionReviewed + 1) }
            nextCard()
        }
    }

    // ========================
    // TTS
    // ========================

    fun speakWord(word: String, context: Context) {
        audioPlayer?.apply { if (isPlaying) stop(); release() }
        audioPlayer = null

        viewModelScope.launch {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val prefs = context.getSharedPreferences("moreader_config", Context.MODE_PRIVATE)
                    val providerType = prefs.getString("tts_provider", "edge_tts") ?: "edge_tts"
                    val ttsType = try { TTSProviderType.valueOf(providerType) } catch (e: IllegalArgumentException) { TTSProviderType.EDGE_TTS }

                    val audioBytes = when (ttsType) {
                        TTSProviderType.EDGE_TTS -> {
                            val endpoint = prefs.getString("edge_endpoint", "http://powerplus.blogsyte.com:5001") ?: "http://powerplus.blogsyte.com:5001"
                            val voice = prefs.getString("edge_voice", "en-US-JennyNeural") ?: "en-US-JennyNeural"
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
                        else -> fetchEdgeTTS("http://powerplus.blogsyte.com:5001", "en-US-JennyNeural", "", word)
                    }

                    if (audioBytes != null && audioBytes.isNotEmpty()) {
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            try {
                                val tempFile = File.createTempFile("flashcard_tts_", ".mp3", context.cacheDir)
                                tempFile.writeBytes(audioBytes)
                                audioPlayer = MediaPlayer().apply {
                                    setDataSource(tempFile.absolutePath)
                                    setOnCompletionListener { audioPlayer?.release(); audioPlayer = null; tempFile.delete() }
                                    setOnErrorListener { _, _, _ -> audioPlayer?.release(); audioPlayer = null; tempFile.delete(); true }
                                    prepare()
                                    start()
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun fetchEdgeTTS(endpoint: String, voice: String, apiKey: String, text: String): ByteArray? {
        return try {
            val json = JSONObject().apply { put("text", text); put("voice", voice); put("rate", "+0%"); put("pitch", "+0Hz") }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("${endpoint.removeSuffix("/")}/tts").post(body)
                .apply { if (apiKey.isNotEmpty()) addHeader("X-API-Key", apiKey) }.build()
            val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) response.body?.bytes() else null
        } catch (_: Exception) { null }
    }

    private suspend fun fetchCustomTTS(endpoint: String, apiKey: String, model: String, voice: String, text: String): ByteArray? {
        return try {
            val json = JSONObject().apply { put("model", model); put("input", text); put("voice", voice) }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("${endpoint.removeSuffix("/")}/audio/speech").post(body)
                .addHeader("Authorization", "Bearer $apiKey").addHeader("Content-Type", "application/json").build()
            val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) response.body?.bytes() else null
        } catch (_: Exception) { null }
    }

    private suspend fun fetchAITTS(endpoint: String, apiKey: String, model: String, voice: String, text: String): ByteArray? {
        return try {
            val json = JSONObject().apply { put("model", model); put("input", text); put("voice", voice) }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("${endpoint.removeSuffix("/")}/audio/speech").post(body)
                .addHeader("Authorization", "Bearer $apiKey").addHeader("Content-Type", "application/json").build()
            val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) response.body?.bytes() else null
        } catch (_: Exception) { null }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer?.apply { if (isPlaying) stop(); release() }
        audioPlayer = null
    }
}

class FlashcardViewModelFactory(
    private val dataStore: FlashcardDataStore,
    private val repository: BookRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FlashcardViewModel::class.java)) {
            return FlashcardViewModel(dataStore, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
