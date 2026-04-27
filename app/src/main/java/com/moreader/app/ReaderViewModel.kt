package com.moreader.app.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moreader.app.data.BookRepository
import com.moreader.app.data.models.*
import com.moreader.app.tts.*
import com.moreader.app.translate.TranslationService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ReaderUiState(
    val book: Book? = null,
    val chapters: List<Chapter> = emptyList(),
    val toc: List<TocEntry> = emptyList(),
    val currentChapterIndex: Int = 0,
    val currentHtml: String? = null,
    val isLoading: Boolean = true,
    val loadingMessage: String = "",
    val error: String? = null,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val fontSize: Int = 18,
    val selectedText: String? = null,
    val translationResult: String? = null,
    val isTranslating: Boolean = false,
    val translationMode: String = "translate",
    // TTS
    val ttsProvider: TTSProviderType = TTSProviderType.EDGE_TTS,
    val isTtsPlaying: Boolean = false,
    val isTtsPaused: Boolean = false,
    val ttsSpeed: Float = 1.0f,
    val ttsParagraphs: List<String> = emptyList(),
    val ttsCurrentIdx: Int = -1,       // 当前高亮索引（考虑了偏移）
    val ttsPlayIdx: Int = -1,          // 当前实际朗读的段落索引（用于恢复）
    val ttsHighlightOffset: Int = -1,  // 高亮偏移量（负 = 高亮提前，正 = 高亮延迟），默认-1
    val ttsDebugLog: String = "",
    val showTtsDebugLog: Boolean = false,
    val llmConfig: LLMConfig = LLMConfig(),
    val edgeTtsEndpoint: String = "http://powerplus.blogsyte.com:5001",
    val edgeTtsVoice: String = "zh-CN-XiaoxiaoNeural",
    val aiVoiceEndpoint: String = "https://api.siliconflow.cn/v1",
    val aiVoiceApiKey: String = "",
    val aiVoiceModel: String = "fnlp/MOSS-TTSD-v0.5",
    val aiVoiceId: String = "fnlp/MOSS-TTSD-v0.5:anna",
    val showTocPanel: Boolean = false,
    val showTtsSettingsPanel: Boolean = false,
    val showTranslationPanel: Boolean = false,
    val showSelectionMenu: Boolean = false,
)

class ReaderViewModel(
    application: Application,
    private val repository: BookRepository,
    private val translationService: TranslationService = TranslationService(),
) : AndroidViewModel(application) {

    companion object { private const val TAG = "ReaderVM" }

    private val prefs = application.getSharedPreferences("moreader_config", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        ReaderUiState(
            edgeTtsEndpoint = prefs.getString("edge_endpoint", "http://powerplus.blogsyte.com:5001") ?: "http://powerplus.blogsyte.com:5001",
            edgeTtsVoice = prefs.getString("edge_voice", "zh-CN-XiaoxiaoNeural") ?: "zh-CN-XiaoxiaoNeural",
            aiVoiceEndpoint = prefs.getString("ai_endpoint", "https://api.siliconflow.cn/v1") ?: "https://api.siliconflow.cn/v1",
            aiVoiceApiKey = prefs.getString("ai_apikey", "") ?: "",
            aiVoiceModel = prefs.getString("ai_model", "fnlp/MOSS-TTSD-v0.5") ?: "fnlp/MOSS-TTSD-v0.5",
            aiVoiceId = prefs.getString("ai_voice_id", "fnlp/MOSS-TTSD-v0.5:anna") ?: "fnlp/MOSS-TTSD-v0.5:anna",
            llmConfig = LLMConfig(
                provider = prefs.getString("llm_provider", "custom") ?: "custom",
                apiKey = prefs.getString("llm_apikey", "") ?: "",
                endpoint = prefs.getString("llm_endpoint", "") ?: "",
                model = prefs.getString("llm_model", "") ?: "",
            ),
            ttsSpeed = prefs.getFloat("tts_speed", 1.0f),
            ttsHighlightOffset = prefs.getInt("tts_highlight_offset", -1),
        )
    )
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var currentTTSProvider: TTSProvider? = null
    // Preloaded audio bytes: index → ByteArray
    private val audioCache = mutableMapOf<Int, ByteArray>()
    // Flag to stop the current play chain
    private var playChainActive = false

    private fun log(msg: String) {
        Log.d(TAG, msg)
        _uiState.update { it.copy(ttsDebugLog = it.ttsDebugLog + msg + "\n") }
    }

    private fun extractParagraphsFromHtml(html: String): List<String> {
        val doc = org.jsoup.Jsoup.parse(html)
        val paragraphs = doc.select("p, h1, h2, h3, h4, h5, h6").map { it.text().trim() }.filter { it.length >= 2 }
        if (paragraphs.isEmpty()) { val t = doc.body()?.text()?.trim() ?: ""; return if (t.length >= 2) listOf(t) else emptyList() }
        return paragraphs
    }

    // ===== Book =====
    fun loadBook(bookId: String) { viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true, loadingMessage = getApplication<android.app.Application>().getString(com.moreader.app.R.string.load_book)) }
        val book = repository.getBook(bookId) ?: run { _uiState.update { it.copy(isLoading = false, error = "Book not found") }; return@launch }
        _uiState.update { it.copy(loadingMessage = getApplication<android.app.Application>().getString(com.moreader.app.R.string.parse_chapters)) }
        val chapters = repository.parseSpine(bookId); val toc = repository.parseToc(bookId)
        if (chapters.isEmpty()) { _uiState.update { it.copy(book = book, isLoading = false, error = getApplication<android.app.Application>().getString(com.moreader.app.R.string.error_parse_failed)) }; return@launch }
        _uiState.update { it.copy(book = book, chapters = chapters, toc = toc, currentChapterIndex = book.currentChapterIndex.coerceIn(0, maxOf(0, chapters.size - 1)), isLoading = false, loadingMessage = "") }
        loadChapterContent()
    } }
    private suspend fun loadChapterContent() {
        val s = _uiState.value; val b = s.book ?: return; if (s.chapters.isEmpty()) return
        _uiState.update { it.copy(isLoading = true, loadingMessage = getApplication<android.app.Application>().getString(com.moreader.app.R.string.load_content)) }
        val ch = s.chapters.getOrNull(s.currentChapterIndex) ?: run { _uiState.update { it.copy(isLoading = false, error = getApplication<android.app.Application>().getString(com.moreader.app.R.string.error_chapter_out_of_range)) }; return }
        val html = repository.getChapterContent(b.id, ch.href) ?: run { _uiState.update { it.copy(isLoading = false, error = getApplication<android.app.Application>().getString(com.moreader.app.R.string.error_read_failed)) }; return }
        val pl = extractParagraphsFromHtml(html)
        _uiState.update { it.copy(currentHtml = html, isLoading = false, loadingMessage = "", ttsParagraphs = pl, ttsCurrentIdx = -1) }
    }
    fun nextChapter() { val s = _uiState.value; if (s.currentChapterIndex < s.chapters.size - 1) { killPlayChain(); _uiState.update { it.copy(currentChapterIndex = it.currentChapterIndex + 1, isLoading = true, currentHtml = null, ttsParagraphs = emptyList(), ttsCurrentIdx = -1, isTtsPaused = false) }; viewModelScope.launch { loadChapterContent(); saveProgress() } } }
    fun prevChapter() { val s = _uiState.value; if (s.currentChapterIndex > 0) { killPlayChain(); _uiState.update { it.copy(currentChapterIndex = it.currentChapterIndex - 1, isLoading = true, currentHtml = null, ttsParagraphs = emptyList(), ttsCurrentIdx = -1, isTtsPaused = false) }; viewModelScope.launch { loadChapterContent(); saveProgress() } } }
    fun navigateToChapter(href: String) { val chs = _uiState.value.chapters; val cl = href.substringBefore('#'); val idx = chs.indexOfFirst { it.href == cl || it.href.endsWith(cl.removePrefix("OEBPS/")) || cl.endsWith(it.href.removePrefix("OEBPS/")) }; if (idx >= 0) { killPlayChain(); _uiState.update { it.copy(currentChapterIndex = idx, isLoading = true, currentHtml = null, showTocPanel = false, ttsParagraphs = emptyList(), ttsCurrentIdx = -1, isTtsPaused = false) }; viewModelScope.launch { loadChapterContent(); saveProgress() } } }
    private suspend fun saveProgress() { val s = _uiState.value; val b = s.book ?: return; val c = s.chapters.getOrNull(s.currentChapterIndex) ?: return; repository.updateProgress(b.id, c.href, s.currentChapterIndex, b.currentProgress, null) }
    fun setTheme(t: ReaderTheme) { _uiState.update { it.copy(theme = t) } }
    fun setFontSize(s: Int) { _uiState.update { it.copy(fontSize = s.coerceIn(14, 28)) } }
    fun onTextSelected(t: String) { _uiState.update { it.copy(selectedText = t, showSelectionMenu = true) } }
    fun dismissSelectionMenu() { _uiState.update { it.copy(showSelectionMenu = false) } }
    fun toggleTtsDebugLog() { _uiState.update { it.copy(showTtsDebugLog = !it.showTtsDebugLog) } }
    fun translate(mode: String = "translate") { val t = _uiState.value.selectedText ?: return; val c = _uiState.value.llmConfig; if (c.apiKey.isEmpty()) { _uiState.update { it.copy(translationResult = getApplication<android.app.Application>().getString(com.moreader.app.R.string.configure_api_key_first), showTranslationPanel = true) }; return }; _uiState.update { it.copy(isTranslating = true, translationMode = mode, showTranslationPanel = true, translationResult = null) }; viewModelScope.launch { val r = translationService.translate(c, t, mode) { ch -> _uiState.update { it.copy(translationResult = (it.translationResult ?: "") + ch) } }; r.onFailure { e -> _uiState.update { it.copy(isTranslating = false, translationResult = getApplication<android.app.Application>().getString(com.moreader.app.R.string.translation_failed, e.message ?: "")) } }.onSuccess { _uiState.update { it.copy(isTranslating = false) } } } }
    fun dismissTranslationPanel() { _uiState.update { it.copy(showTranslationPanel = false, translationResult = null) } }

    // ===== TTS =====

    /** Get or create provider — always re-create when config may have changed */
    private fun recreateProvider(): TTSProvider? {
        val s = _uiState.value
        currentTTSProvider?.destroy()
        currentTTSProvider = null
        return when (s.ttsProvider) {
            TTSProviderType.EDGE_TTS -> { EdgeTTSProvider(s.edgeTtsEndpoint, s.edgeTtsVoice).also { currentTTSProvider = it } }
            TTSProviderType.AI_VOICE -> { AIVoiceTTSProvider(s.aiVoiceEndpoint, s.aiVoiceApiKey, s.aiVoiceModel, s.aiVoiceId).also { currentTTSProvider = it } }
            else -> null
        }
    }

    /** Get current provider without re-creating */
    private fun getProvider(): TTSProvider? = currentTTSProvider

    /** Preload paragraphs [startIdx, endIdx) into audioCache */
    private fun preloadRange(startIdx: Int, endIdx: Int) {
        val s = _uiState.value
        val paragraphs = s.ttsParagraphs
        val ttsSpeed = s.ttsSpeed
        if (startIdx >= paragraphs.size) return
        val actualEnd = minOf(endIdx, paragraphs.size)
        viewModelScope.launch(Dispatchers.IO) {
            for (i in startIdx until actualEnd) {
                if (!playChainActive) break
                if (audioCache.containsKey(i)) continue
                val text = paragraphs[i]; if (text.length < 2) { audioCache[i] = ByteArray(0); continue }
                val bytes = when (val p = getProvider()) {
                    is EdgeTTSProvider -> p.fetchAudio(text, ttsSpeed)
                    is AIVoiceTTSProvider -> p.fetchAudio(text, ttsSpeed)
                    else -> null
                }
                if (bytes != null && bytes.isNotEmpty()) { audioCache[i] = bytes }
            }
        }
    }

    /**
     * Main play chain: plays paragraph at `idx`, then recursively plays next.
     *
     * Highlight rule: when speaking paragraph `idx`, highlight = `idx - 1`.
     * This compensates for the natural delay between highlight update and audio start.
     */
    private fun playOne(idx: Int) {
        if (!playChainActive) return
        val s = _uiState.value
        val paragraphs = s.ttsParagraphs
        if (idx < 0 || idx >= paragraphs.size) { _uiState.update { it.copy(isTtsPlaying = false, ttsCurrentIdx = -1, ttsPlayIdx = -1) }; return }

        // Highlight = current index - ttsHighlightOffset (user-adjustable, negative = earlier)
        val offset = s.ttsHighlightOffset
        val highlightIdx = (idx - offset).coerceIn(0, maxOf(0, paragraphs.size - 1))
        _uiState.update { it.copy(ttsCurrentIdx = highlightIdx, ttsPlayIdx = idx) }

        val text = paragraphs[idx]
        val speed = s.ttsSpeed

        // Try to get audio from cache first
        val cached = audioCache.remove(idx)

        val listener = object : TTSListener {
            override fun onStart() {}
            override fun onDone() { playOne(idx + 1) }
            override fun onError(msg: String) { log(getApplication<android.app.Application>().getString(com.moreader.app.R.string.tts_log_engine_error, idx, msg)); playOne(idx + 1) }
        }

        if (cached != null && cached.isNotEmpty()) {
            // Play cached audio directly (zero network delay!)
            when (val p = getProvider()) {
                is EdgeTTSProvider -> p.playRaw(cached, listener)
                is AIVoiceTTSProvider -> p.playRaw(cached, listener)
            }
        } else {
            // Request live
            val p = recreateProvider()
            if (p == null) { _uiState.update { it.copy(isTtsPlaying = false, ttsCurrentIdx = -1, ttsPlayIdx = -1) }; return }
            p.speak(text, speed, listener)
        }

        // Preload next paragraphs in background
        preloadRange(idx + 1, idx + 6)
    }

    fun readChapter() {
        log(getApplication<android.app.Application>().getString(com.moreader.app.R.string.tts_log_full_read))
        playChainActive = true
        val s = _uiState.value
        val paragraphs = if (s.ttsParagraphs.isEmpty()) {
            val html = s.currentHtml ?: run { log(getApplication<android.app.Application>().getString(com.moreader.app.R.string.tts_log_empty_html)); return }
            val pl = extractParagraphsFromHtml(html); if (pl.isEmpty()) { log(getApplication<android.app.Application>().getString(com.moreader.app.R.string.tts_log_no_paragraphs)); return }
            _uiState.update { it.copy(ttsParagraphs = pl) }; pl
        } else s.ttsParagraphs
        if (paragraphs.isEmpty()) return

        killPlayChain(); audioCache.clear()
        currentTTSProvider?.destroy(); currentTTSProvider = null
        _uiState.update { it.copy(isTtsPlaying = true, isTtsPaused = false, ttsCurrentIdx = 0, ttsPlayIdx = 0) }
        playChainActive = true

        // Preload first 5 paragraphs
        recreateProvider()
        preloadRange(0, 6)

        playOne(0)
    }

    fun readSelection(text: String) {
        killPlayChain()
        _uiState.update { it.copy(isTtsPlaying = true, isTtsPaused = false) }
        _uiState.update { it.copy(ttsCurrentIdx = 0) }
        val p = recreateProvider() ?: run { _uiState.update { it.copy(isTtsPlaying = false) }; return }
        p.speak(text, _uiState.value.ttsSpeed, object : TTSListener {
            override fun onStart() {}
            override fun onDone() { _uiState.update { it.copy(isTtsPlaying = false, ttsCurrentIdx = -1, ttsPlayIdx = -1) } }
            override fun onError(msg: String) { _uiState.update { it.copy(isTtsPlaying = false, ttsCurrentIdx = -1, ttsPlayIdx = -1) } }
        })
    }

    fun ttsPause() { log(getApplication<android.app.Application>().getString(com.moreader.app.R.string.tts_log_pause, _uiState.value.ttsPlayIdx)); playChainActive = false; currentTTSProvider?.stop(); _uiState.update { it.copy(isTtsPaused = true, isTtsPlaying = false) } }
    fun ttsResume() { log(getApplication<android.app.Application>().getString(com.moreader.app.R.string.tts_log_resume)); val s = _uiState.value; val playIdx = s.ttsPlayIdx; if (s.ttsParagraphs.isEmpty() || playIdx < 0) { log(getApplication<android.app.Application>().getString(com.moreader.app.R.string.tts_log_invalid_restore, playIdx)); return }; playChainActive = true; _uiState.update { it.copy(isTtsPaused = false, isTtsPlaying = true) }; playOne(playIdx) }
    private fun killPlayChain() { playChainActive = false; currentTTSProvider?.stop(); audioCache.clear() }
    fun ttsStop() { log(getApplication<android.app.Application>().getString(com.moreader.app.R.string.tts_log_stop)); killPlayChain(); _uiState.update { it.copy(isTtsPlaying = false, isTtsPaused = false, ttsCurrentIdx = -1, ttsPlayIdx = -1) } }
    fun togglePlayPause() { val s = _uiState.value; when { s.isTtsPlaying -> ttsPause(); s.isTtsPaused -> ttsResume(); else -> readChapter() } }

    fun setTTSProvider(type: TTSProviderType) { log(getApplication<android.app.Application>().getString(com.moreader.app.R.string.tts_log_switch_engine, type.name)); killPlayChain(); currentTTSProvider?.destroy(); currentTTSProvider = null; _uiState.update { it.copy(ttsProvider = type) } }
    fun setTTSSpeed(s: Float) {
        _uiState.update { it.copy(ttsSpeed = s.coerceIn(0.5f, 2.0f)) }
        prefs.edit().putFloat("tts_speed", s.coerceIn(0.5f, 2.0f)).apply()
    }
    fun increaseHighlightOffset() { _uiState.update { it.copy(ttsHighlightOffset = it.ttsHighlightOffset + 1) }; log(getApplication<android.app.Application>().getString(com.moreader.app.R.string.tts_log_offset_inc, _uiState.value.ttsHighlightOffset)); prefs.edit().putInt("tts_highlight_offset", _uiState.value.ttsHighlightOffset).apply() }
    fun decreaseHighlightOffset() { _uiState.update { it.copy(ttsHighlightOffset = it.ttsHighlightOffset - 1) }; log(getApplication<android.app.Application>().getString(com.moreader.app.R.string.tts_log_offset_dec, _uiState.value.ttsHighlightOffset)); prefs.edit().putInt("tts_highlight_offset", _uiState.value.ttsHighlightOffset).apply() }
    fun updateEdgeTTSConfig(endpoint: String, voice: String) { log(getApplication<android.app.Application>().getString(com.moreader.app.R.string.tts_log_edge_config, voice)); killPlayChain(); currentTTSProvider?.destroy(); currentTTSProvider = null; _uiState.update { it.copy(edgeTtsEndpoint = endpoint, edgeTtsVoice = voice) }; prefs.edit().putString("edge_endpoint", endpoint).putString("edge_voice", voice).apply() }
    fun updateAIVoiceConfig(endpoint: String, apiKey: String, model: String, voice: String) { log(getApplication<android.app.Application>().getString(com.moreader.app.R.string.tts_log_ai_config, voice)); killPlayChain(); currentTTSProvider?.destroy(); currentTTSProvider = null; _uiState.update { it.copy(aiVoiceEndpoint = endpoint, aiVoiceApiKey = apiKey, aiVoiceModel = model, aiVoiceId = voice) }; prefs.edit().putString("ai_endpoint", endpoint).putString("ai_apikey", apiKey).putString("ai_model", model).putString("ai_voice_id", voice).apply() }
    fun updateLLMConfig(c: LLMConfig) { _uiState.update { it.copy(llmConfig = c) }; prefs.edit().putString("llm_provider", c.provider).putString("llm_apikey", c.apiKey).putString("llm_endpoint", c.endpoint).putString("llm_model", c.model).apply() }
    fun toggleTocPanel() { _uiState.update { it.copy(showTocPanel = !it.showTocPanel) } }
    fun toggleTtsSettingsPanel() { _uiState.update { it.copy(showTtsSettingsPanel = !it.showTtsSettingsPanel) } }
    override fun onCleared() { super.onCleared(); killPlayChain(); currentTTSProvider?.destroy() }
}
