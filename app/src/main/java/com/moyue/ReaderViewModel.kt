package com.moyue.app.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moyue.app.data.BookRepository
import com.moyue.app.data.models.*
import com.moyue.app.tts.*
import com.moyue.app.translate.TranslationService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.Locale
import org.json.JSONObject

data class SelectionInfo(
    val text: String,
    val paraIdx: Int,
    val startOffset: Int,
    val endParaIdx: Int,
    val endOffset: Int,
)

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
    val selectionInfo: SelectionInfo? = null,  // Selection with paragraph/offset info
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
    val ttsHighlightOffset: Int = 0,  // 高亮偏移量（负 = 高亮提前，正 = 高亮延迟），默认0
    val ttsDebugLog: String = "",
    val showTtsDebugLog: Boolean = false,
    // Fullscreen mode
    val isFullscreen: Boolean = false,
    val llmConfig: LLMConfig = LLMConfig(),
    val edgeTtsEndpoint: String = "http://powerplus.blogsyte.com:5001",
    val edgeTtsVoice: String = "zh-CN-XiaoxiaoNeural",
    val aiVoiceEndpoint: String = "https://api.siliconflow.cn/v1",
    val aiVoiceApiKey: String = "",
    val aiVoiceModel: String = "fnlp/MOSS-TTSD-v0.5",
    val aiVoiceId: String = "fnlp/MOSS-TTSD-v0.5:anna",
    // Custom TTS (OpenAI-compatible)
    val customTtsEndpoint: String = "http://192.168.199.101:18083",
    val customTtsApiKey: String = "dummy",
    val customTtsModel: String = "moss-tts-nano",
    val customTtsVoice: String = "Lingyu",
    val showTocPanel: Boolean = false,
    val showTtsSettingsPanel: Boolean = false,
    val showTranslationPanel: Boolean = false,
    val showSelectionMenu: Boolean = false,
    // Bookmark
    val showBookmarkToast: Boolean = false,
    val bookmarkToastMsg: String = "",
    val showBookmarkPanel: Boolean = false,
    val currentParagraphIndex: Int = 0,       // 当前阅读/朗读的段落
    val scrollToParagraph: Int = -1,           // 需要滚动到的段落索引（-1 表示无）
    val highlightToRemove: Highlight? = null,    // Signal to remove a highlight in WebView
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
            ttsHighlightOffset = prefs.getInt("tts_highlight_offset", 0),
        )
    )
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var currentTTSProvider: TTSProvider? = null
    // Track last provider type to avoid unnecessary recreation (critical for System TTS!)
    private var lastProviderType: TTSProviderType? = null
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
        // 注意：这里不能过滤，必须与 WebView 中的 querySelectorAll 结果一致
        // WebView 中给所有 p,h1-h6 元素添加了点击监听，索引必须对应
        val paragraphs = doc.select("p, h1, h2, h3, h4, h5, h6").map { it.text().trim() }
        // 如果过滤后为空，返回整个 body 文本
        if (paragraphs.isEmpty() || paragraphs.all { it.isEmpty() }) { 
            val t = doc.body()?.text()?.trim() ?: "" 
            return if (t.length >= 2) listOf(t) else emptyList() 
        }
        return paragraphs
    }

    // ===== Book =====
    fun loadBook(bookId: String) { viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true, loadingMessage = getApplication<android.app.Application>().getString(com.moyue.app.R.string.load_book)) }
        val book = repository.getBook(bookId) ?: run { _uiState.update { it.copy(isLoading = false, error = "Book not found") }; return@launch }
        _uiState.update { it.copy(loadingMessage = getApplication<android.app.Application>().getString(com.moyue.app.R.string.parse_chapters)) }
        val chapters = repository.parseSpine(bookId); val toc = repository.parseToc(bookId)
        if (chapters.isEmpty()) { _uiState.update { it.copy(book = book, isLoading = false, error = getApplication<android.app.Application>().getString(com.moyue.app.R.string.error_parse_failed)) }; return@launch }
        _uiState.update { it.copy(book = book, chapters = chapters, toc = toc, currentChapterIndex = book.currentChapterIndex.coerceIn(0, maxOf(0, chapters.size - 1)), isLoading = false, loadingMessage = "") }
        loadChapterContent()
    } }
    private suspend fun loadChapterContent() {
        val s = _uiState.value; val b = s.book ?: return; if (s.chapters.isEmpty()) return
        _uiState.update { it.copy(isLoading = true, loadingMessage = getApplication<android.app.Application>().getString(com.moyue.app.R.string.load_content)) }
        val ch = s.chapters.getOrNull(s.currentChapterIndex) ?: run { _uiState.update { it.copy(isLoading = false, error = getApplication<android.app.Application>().getString(com.moyue.app.R.string.error_chapter_out_of_range)) }; return }
        val html = repository.getChapterContent(b.id, ch.href) ?: run { _uiState.update { it.copy(isLoading = false, error = getApplication<android.app.Application>().getString(com.moyue.app.R.string.error_read_failed)) }; return }
        val pl = extractParagraphsFromHtml(html)
        _uiState.update { it.copy(currentHtml = html, isLoading = false, loadingMessage = "", ttsParagraphs = pl, ttsCurrentIdx = -1) }
    }
    fun nextChapter() { val s = _uiState.value; if (s.currentChapterIndex < s.chapters.size - 1) { killPlayChain(); _uiState.update { it.copy(currentChapterIndex = it.currentChapterIndex + 1, isLoading = true, currentHtml = null, ttsParagraphs = emptyList(), ttsCurrentIdx = -1, isTtsPaused = false) }; viewModelScope.launch { loadChapterContent(); saveProgress() } } }
    fun prevChapter() { val s = _uiState.value; if (s.currentChapterIndex > 0) { killPlayChain(); _uiState.update { it.copy(currentChapterIndex = it.currentChapterIndex - 1, isLoading = true, currentHtml = null, ttsParagraphs = emptyList(), ttsCurrentIdx = -1, isTtsPaused = false) }; viewModelScope.launch { loadChapterContent(); saveProgress() } } }
    fun navigateToChapter(href: String) { 
        val chs = _uiState.value.chapters
        val cl = href.substringBefore('#').trim()
        
        // Try multiple matching strategies
        var idx = chs.indexOfFirst { it.href == cl }
        if (idx < 0) idx = chs.indexOfFirst { it.href.endsWith(cl) }
        if (idx < 0) idx = chs.indexOfFirst { cl.endsWith(it.href) }
        if (idx < 0) {
            val targetFile = cl.substringAfterLast('/')
            idx = chs.indexOfFirst { it.href.substringAfterLast('/') == targetFile }
        }
        if (idx < 0) {
            val targetNoExt = cl.substringAfterLast('/').substringBeforeLast('.')
            idx = chs.indexOfFirst { 
                it.href.substringAfterLast('/').substringBeforeLast('.') == targetNoExt 
            }
        }
        
        if (idx >= 0) { 
            killPlayChain()
            _uiState.update { it.copy(currentChapterIndex = idx, isLoading = true, currentHtml = null, showTocPanel = false, ttsParagraphs = emptyList(), ttsCurrentIdx = -1, isTtsPaused = false) }
            viewModelScope.launch { loadChapterContent(); saveProgress() }
        }
    }
    private suspend fun saveProgress() { val s = _uiState.value; val b = s.book ?: return; val c = s.chapters.getOrNull(s.currentChapterIndex) ?: return; repository.updateProgress(b.id, c.href, s.currentChapterIndex, b.currentProgress, null) }
    fun setTheme(t: ReaderTheme) { _uiState.update { it.copy(theme = t) } }
    fun setFontSize(s: Int) { _uiState.update { it.copy(fontSize = s.coerceIn(14, 28)) } }
    fun onTextSelected(infoJson: String) {
        try {
            val obj = JSONObject(infoJson)
            val text = obj.optString("text", "")
            val info = SelectionInfo(
                text = text,
                paraIdx = obj.optInt("paraIdx", -1),
                startOffset = obj.optInt("startOffset", -1),
                endParaIdx = obj.optInt("endParaIdx", -1),
                endOffset = obj.optInt("endOffset", -1),
            )
            _uiState.update { it.copy(selectedText = info.text, selectionInfo = info, showSelectionMenu = true) }
        } catch (e: Exception) {
            // Fallback for plain text (legacy)
            _uiState.update { it.copy(selectedText = infoJson, selectionInfo = null, showSelectionMenu = true) }
        }
    }
    fun onLinkClicked(url: String) { 
        // Handle internal EPUB link clicks
        val cleanUrl = url.trim()
        log("Link clicked: $cleanUrl")
        
        // Extract path and anchor
        val path = cleanUrl.substringBefore('#')
        val anchor = cleanUrl.substringAfter('#', "")
        
        // Find matching chapter
        val chs = _uiState.value.chapters
        var idx = chs.indexOfFirst { it.href == path }
        if (idx < 0) idx = chs.indexOfFirst { it.href.endsWith(path) }
        if (idx < 0) idx = chs.indexOfFirst { path.endsWith(it.href) }
        if (idx < 0) {
            val targetFile = path.substringAfterLast('/')
            idx = chs.indexOfFirst { it.href.substringAfterLast('/') == targetFile }
        }
        if (idx < 0) {
            val targetNoExt = path.substringAfterLast('/').substringBeforeLast('.')
            idx = chs.indexOfFirst { 
                it.href.substringAfterLast('/').substringBeforeLast('.') == targetNoExt 
            }
        }
        
        if (idx >= 0) {
            killPlayChain()
            _uiState.update { it.copy(currentChapterIndex = idx, isLoading = true, currentHtml = null, ttsParagraphs = emptyList(), ttsCurrentIdx = -1, isTtsPaused = false) }
            viewModelScope.launch { 
                loadChapterContent()
                // TODO: Scroll to anchor after content loaded
                saveProgress() 
            }
        } else {
            log("Could not find chapter for link: $cleanUrl")
        }
    }
    fun dismissSelectionMenu() { _uiState.update { it.copy(showSelectionMenu = false) } }
    fun toggleTtsDebugLog() { _uiState.update { it.copy(showTtsDebugLog = !it.showTtsDebugLog) } }
    fun translate(mode: String = "translate") { val t = _uiState.value.selectedText ?: return; val c = _uiState.value.llmConfig; if (c.apiKey.isEmpty()) { _uiState.update { it.copy(translationResult = getApplication<android.app.Application>().getString(com.moyue.app.R.string.configure_api_key_first), showTranslationPanel = true) }; return }; _uiState.update { it.copy(isTranslating = true, translationMode = mode, showTranslationPanel = true, translationResult = null) }; viewModelScope.launch { val r = translationService.translate(c, t, mode) { ch -> _uiState.update { it.copy(translationResult = (it.translationResult ?: "") + ch) } }; r.onFailure { e -> _uiState.update { it.copy(isTranslating = false, translationResult = getApplication<android.app.Application>().getString(com.moyue.app.R.string.translation_failed, e.message ?: "")) } }.onSuccess { _uiState.update { it.copy(isTranslating = false) } } } }
    fun dismissTranslationPanel() { _uiState.update { it.copy(showTranslationPanel = false, translationResult = null) } }

    /** Speak arbitrary text (used by translation panel speaker button) */
    fun speakTranslationText(text: String) {
        val p = recreateProvider() ?: return
        viewModelScope.launch {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                p.speak(text, _uiState.value.ttsSpeed, object : TTSListener {
                    override fun onStart() {}
                    override fun onDone() {}
                    override fun onError(msg: String) {}
                })
            }
        }
    }

    // ===== TTS =====

    /** Get or create provider — only re-create when provider TYPE changes */
    private fun recreateProvider(): TTSProvider? {
        val s = _uiState.value
        
        // If same type and already created, reuse it (critical for System TTS!)
        if (s.ttsProvider == lastProviderType && currentTTSProvider != null) {
            return currentTTSProvider
        }
        
        // Type changed — destroy old and create new
        currentTTSProvider?.destroy()
        currentTTSProvider = null
        lastProviderType = s.ttsProvider
        
        return when (s.ttsProvider) {
            TTSProviderType.SYSTEM -> { SystemTTSProvider(getApplication()).also { currentTTSProvider = it } }
            TTSProviderType.EDGE_TTS -> { EdgeTTSProvider(s.edgeTtsEndpoint, s.edgeTtsVoice).also { currentTTSProvider = it } }
            TTSProviderType.AI_VOICE -> { AIVoiceTTSProvider(s.aiVoiceEndpoint, s.aiVoiceApiKey, s.aiVoiceModel, s.aiVoiceId).also { currentTTSProvider = it } }
            TTSProviderType.CUSTOM_TTS -> { CustomTTSProvider(s.customTtsEndpoint, s.customTtsApiKey, s.customTtsModel, s.customTtsVoice).also { currentTTSProvider = it } }
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
                    is CustomTTSProvider -> p.fetchAudio(text, ttsSpeed)
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
        if (idx < 0 || idx >= paragraphs.size) {
            // End of chapter reached — auto-advance to next chapter if available
            if (s.currentChapterIndex < s.chapters.size - 1) {
                log("[TTS] 📖 章节读完，自动跳转下一章")
                audioCache.clear()
                _uiState.update { it.copy(
                    currentChapterIndex = it.currentChapterIndex + 1,
                    isLoading = true, ttsParagraphs = emptyList(), ttsCurrentIdx = -1
                ) }
                viewModelScope.launch {
                    loadChapterContent()
                    if (_uiState.value.ttsParagraphs.isNotEmpty()) {
                        playOne(0)
                    } else {
                        _uiState.update { it.copy(isTtsPlaying = false, ttsCurrentIdx = -1, ttsPlayIdx = -1) }
                    }
                }
            } else {
                log("[TTS] 🎉 全部章节朗读完毕")
                _uiState.update { it.copy(isTtsPlaying = false, ttsCurrentIdx = -1, ttsPlayIdx = -1) }
            }
            return
        }

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
            override fun onError(msg: String) { log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_engine_error, idx, msg)); playOne(idx + 1) }
        }

        if (cached != null && cached.isNotEmpty()) {
            // Play cached audio directly (zero network delay!)
            when (val p = getProvider()) {
                is EdgeTTSProvider -> p.playRaw(cached, listener)
                is AIVoiceTTSProvider -> p.playRaw(cached, listener)
                is CustomTTSProvider -> p.playRaw(cached, listener)
                is SystemTTSProvider -> {
                    // System TTS doesn't use audio cache, speak directly
                    p.speak(text, speed, listener)
                }
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
        log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_full_read))
        playChainActive = true
        val s = _uiState.value
        val paragraphs = if (s.ttsParagraphs.isEmpty()) {
            val html = s.currentHtml ?: run { log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_empty_html)); return }
            val pl = extractParagraphsFromHtml(html); if (pl.isEmpty()) { log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_no_paragraphs)); return }
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

    /** Read from a specific paragraph index */
    fun readFromParagraph(index: Int) {
        log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_read_from_paragraph, index + 1))
        playChainActive = true
        val s = _uiState.value
        val paragraphs = if (s.ttsParagraphs.isEmpty()) {
            val html = s.currentHtml ?: run { log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_empty_html)); return }
            val pl = extractParagraphsFromHtml(html); if (pl.isEmpty()) { log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_no_paragraphs)); return }
            _uiState.update { it.copy(ttsParagraphs = pl) }; pl
        } else s.ttsParagraphs
        if (paragraphs.isEmpty()) return
        if (index < 0 || index >= paragraphs.size) {
            log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_invalid_paragraph_index, index))
            return
        }

        killPlayChain(); audioCache.clear()
        currentTTSProvider?.destroy(); currentTTSProvider = null
        
        // Calculate highlight index with offset (same logic as playOne)
        // ttsCurrentIdx = highlight index (for UI), ttsPlayIdx = actual play index
        val offset = s.ttsHighlightOffset
        val highlightIdx = (index - offset).coerceIn(0, maxOf(0, paragraphs.size - 1))
        
        _uiState.update { it.copy(isTtsPlaying = true, isTtsPaused = false, ttsCurrentIdx = highlightIdx, ttsPlayIdx = index) }
        playChainActive = true

        // Preload from current position
        recreateProvider()
        preloadRange(index, index + 6)

        playOne(index)
    }

    fun readSelection(text: String) {
        killPlayChain()
        _uiState.update { it.copy(isTtsPlaying = true, isTtsPaused = false, ttsCurrentIdx = -1) }
        val p = recreateProvider() ?: run { _uiState.update { it.copy(isTtsPlaying = false) }; return }
        p.speak(text, _uiState.value.ttsSpeed, object : TTSListener {
            override fun onStart() {}
            override fun onDone() { _uiState.update { it.copy(isTtsPlaying = false, ttsCurrentIdx = -1, ttsPlayIdx = -1) } }
            override fun onError(msg: String) { _uiState.update { it.copy(isTtsPlaying = false, ttsCurrentIdx = -1, ttsPlayIdx = -1) } }
        })
    }

    fun ttsPause() { log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_pause, _uiState.value.ttsPlayIdx)); playChainActive = false; currentTTSProvider?.stop(); _uiState.update { it.copy(isTtsPaused = true, isTtsPlaying = false) } }
    fun ttsResume() { log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_resume)); val s = _uiState.value; val playIdx = s.ttsPlayIdx; if (s.ttsParagraphs.isEmpty() || playIdx < 0) { log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_invalid_restore, playIdx)); return }; playChainActive = true; _uiState.update { it.copy(isTtsPaused = false, isTtsPlaying = true) }; playOne(playIdx) }
    private fun killPlayChain() { playChainActive = false; currentTTSProvider?.stop(); audioCache.clear() }
    fun ttsStop() { log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_stop)); killPlayChain(); _uiState.update { it.copy(isTtsPlaying = false, isTtsPaused = false, ttsCurrentIdx = -1, ttsPlayIdx = -1) } }
    fun togglePlayPause() { val s = _uiState.value; when { s.isTtsPlaying -> ttsPause(); s.isTtsPaused -> ttsResume(); else -> readChapter() } }

    fun setTTSProvider(type: TTSProviderType) { log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_switch_engine, type.name)); killPlayChain(); currentTTSProvider?.destroy(); currentTTSProvider = null; _uiState.update { it.copy(ttsProvider = type) } }
    fun setTTSSpeed(s: Float) {
        _uiState.update { it.copy(ttsSpeed = s.coerceIn(0.5f, 2.0f)) }
        prefs.edit().putFloat("tts_speed", s.coerceIn(0.5f, 2.0f)).apply()
    }
    fun increaseHighlightOffset() { _uiState.update { it.copy(ttsHighlightOffset = it.ttsHighlightOffset + 1) }; log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_offset_inc, _uiState.value.ttsHighlightOffset)); prefs.edit().putInt("tts_highlight_offset", _uiState.value.ttsHighlightOffset).apply() }
    fun decreaseHighlightOffset() { _uiState.update { it.copy(ttsHighlightOffset = it.ttsHighlightOffset - 1) }; log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_offset_dec, _uiState.value.ttsHighlightOffset)); prefs.edit().putInt("tts_highlight_offset", _uiState.value.ttsHighlightOffset).apply() }
    fun updateEdgeTTSConfig(endpoint: String, voice: String) { log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_edge_config, voice)); killPlayChain(); currentTTSProvider?.destroy(); currentTTSProvider = null; _uiState.update { it.copy(edgeTtsEndpoint = endpoint, edgeTtsVoice = voice) }; prefs.edit().putString("edge_endpoint", endpoint).putString("edge_voice", voice).apply() }
    fun updateAIVoiceConfig(endpoint: String, apiKey: String, model: String, voice: String) { log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_ai_config, voice)); killPlayChain(); currentTTSProvider?.destroy(); currentTTSProvider = null; _uiState.update { it.copy(aiVoiceEndpoint = endpoint, aiVoiceApiKey = apiKey, aiVoiceModel = model, aiVoiceId = voice) }; prefs.edit().putString("ai_endpoint", endpoint).putString("ai_apikey", apiKey).putString("ai_model", model).putString("ai_voice_id", voice).apply() }
    fun updateCustomTTSConfig(endpoint: String, apiKey: String, model: String, voice: String) { log("Custom TTS: $endpoint, model=$model, voice=$voice"); killPlayChain(); currentTTSProvider?.destroy(); currentTTSProvider = null; _uiState.update { it.copy(customTtsEndpoint = endpoint, customTtsApiKey = apiKey, customTtsModel = model, customTtsVoice = voice) }; prefs.edit().putString("custom_endpoint", endpoint).putString("custom_apikey", apiKey).putString("custom_model", model).putString("custom_voice", voice).apply() }
    fun toggleFullscreen() { _uiState.update { it.copy(isFullscreen = !it.isFullscreen) } }
    fun setFullscreen(enabled: Boolean) { _uiState.update { it.copy(isFullscreen = enabled) } }
    fun updateLLMConfig(c: LLMConfig) { _uiState.update { it.copy(llmConfig = c) }; prefs.edit().putString("llm_provider", c.provider).putString("llm_apikey", c.apiKey).putString("llm_endpoint", c.endpoint).putString("llm_model", c.model).apply() }
    fun toggleTocPanel() { _uiState.update { it.copy(showTocPanel = !it.showTocPanel) } }
    fun toggleTtsSettingsPanel() { _uiState.update { it.copy(showTtsSettingsPanel = !it.showTtsSettingsPanel) } }

    // ===== Bookmark =====
    fun addBookmark() {
        val s = _uiState.value
        val book = s.book ?: return
        val chapter = s.chapters.getOrNull(s.currentChapterIndex) ?: return
        
        // 使用当前朗读/阅读的段落索引，如果没有则使用 0
        val paraIdx = if (s.ttsCurrentIdx >= 0 && s.ttsCurrentIdx < s.ttsParagraphs.size) s.ttsCurrentIdx else s.currentParagraphIndex
        val paraText = s.ttsParagraphs.getOrNull(paraIdx)?.take(50) // 保存前 50 字作为预览
        
        viewModelScope.launch {
            val bookmark = Bookmark(
                bookId = book.id,
                chapterIndex = s.currentChapterIndex,
                chapterTitle = chapter.id,
                paragraphIndex = paraIdx,
                paragraphText = paraText,
                progress = s.currentChapterIndex.toFloat() / maxOf(1, s.chapters.size - 1),
                createdAt = System.currentTimeMillis()
            )
            repository.addBookmark(bookmark)
            val msg = getApplication<android.app.Application>().getString(com.moyue.app.R.string.bookmark_added_at, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()))
            _uiState.update { it.copy(showBookmarkToast = true, bookmarkToastMsg = msg) }
            delay(2000)
            _uiState.update { it.copy(showBookmarkToast = false) }
        }
    }

    fun navigateToBookmark(bookmark: Bookmark) {
        _uiState.update { it.copy(showBookmarkPanel = false) }
        val s = _uiState.value
        val chapters = s.chapters
        
        // 如果书签不在当前章节，先跳转章节
        if (bookmark.chapterIndex != s.currentChapterIndex) {
            val targetChapter = chapters.getOrNull(bookmark.chapterIndex)
            if (targetChapter != null) {
                killPlayChain()
                _uiState.update { it.copy(currentChapterIndex = bookmark.chapterIndex, isLoading = true, currentHtml = null, ttsParagraphs = emptyList(), ttsCurrentIdx = -1, isTtsPaused = false) }
                viewModelScope.launch {
                    loadChapterContent()
                    // 章节加载完成后滚动到书签段落
                    _uiState.update { it.copy(scrollToParagraph = bookmark.paragraphIndex) }
                }
            }
        } else {
            // 同一章节，直接滚动到段落
            _uiState.update { it.copy(scrollToParagraph = bookmark.paragraphIndex) }
        }
    }

    fun toggleBookmarkPanel() { _uiState.update { it.copy(showBookmarkPanel = !it.showBookmarkPanel) } }

    fun setCurrentParagraph(idx: Int) { _uiState.update { it.copy(currentParagraphIndex = idx) } }

    fun clearScrollToParagraph() { _uiState.update { it.copy(scrollToParagraph = -1) } }

    // ===== Highlight =====
    private val _highlights = MutableStateFlow<List<Highlight>>(emptyList())
    val loadedHighlights: StateFlow<List<Highlight>> = _highlights.asStateFlow()

    fun addHighlight() {
        val s = _uiState.value
        val book = s.book ?: return
        val info = s.selectionInfo ?: return
        if (info.paraIdx < 0 || info.startOffset < 0) return

        viewModelScope.launch {
            val endPara = if (info.endParaIdx >= 0) info.endParaIdx else info.paraIdx
            val endOff = if (info.endOffset >= 0) info.endOffset else info.startOffset + info.text.length

            val highlight = Highlight(
                bookId = book.id,
                chapterIndex = s.currentChapterIndex,
                startParagraph = info.paraIdx,
                startOffset = info.startOffset,
                endParagraph = endPara,
                endOffset = endOff,
                text = info.text,
                color = 0xFFFFFF00.toInt(),
                createdAt = System.currentTimeMillis()
            )
            repository.addHighlight(highlight)
            // Add to in-memory list so EpubWebView renders it
            _highlights.update { it + highlight }
            _uiState.update { it.copy(selectedText = null, selectionInfo = null, showSelectionMenu = false) }
        }
    }

    fun removeHighlight(highlight: Highlight) {
        viewModelScope.launch {
            repository.deleteHighlight(highlight)
            _highlights.update { it.filter { h -> h.id != highlight.id } }
            _uiState.update { it.copy(highlightToRemove = highlight) }
            // Clear the remove signal after a short delay
            kotlinx.coroutines.delay(200)
            _uiState.update { it.copy(highlightToRemove = null) }
        }
    }

    /** Check if selected text overlaps with an existing highlight */
    fun getExistingHighlightForSelection(): Highlight? {
        val s = _uiState.value
        val info = s.selectionInfo ?: return null
        val endPara = if (info.endParaIdx >= 0) info.endParaIdx else info.paraIdx
        val endOff = if (info.endOffset >= 0) info.endOffset else info.startOffset + info.text.length
        return _highlights.value.find { hl ->
            hl.startParagraph == info.paraIdx &&
            hl.startOffset == info.startOffset &&
            hl.endParagraph == endPara &&
            hl.endOffset == endOff
        }
    }

    fun loadHighlightsForChapter() {
        val s = _uiState.value
        val book = s.book ?: return
        viewModelScope.launch {
            val all = repository.getHighlightsForBook(book.id).first()
            _highlights.value = all.filter { it.chapterIndex == s.currentChapterIndex }
        }
    }

    // ===== Vocabulary =====
    fun addVocabulary() {
        val s = _uiState.value
        val book = s.book ?: return
        val text = s.selectedText?.trim() ?: return
        if (text.isEmpty()) return

        viewModelScope.launch {
            val existing = repository.getVocabularyByWord(text)
            if (existing != null) {
                _uiState.update { it.copy(showBookmarkToast = true, bookmarkToastMsg = getApplication<android.app.Application>().getString(com.moyue.app.R.string.vocabulary_already_exists)) }
            } else {
                val vocab = Vocabulary(
                    word = text,
                    bookId = book.id.toLongOrNull(),
                    chapterIndex = s.currentChapterIndex,
                    createdAt = System.currentTimeMillis()
                )
                repository.insertVocabulary(vocab)
                _uiState.update { it.copy(showBookmarkToast = true, bookmarkToastMsg = getApplication<android.app.Application>().getString(com.moyue.app.R.string.vocabulary_added)) }
            }
            delay(2000)
            _uiState.update { it.copy(showBookmarkToast = false) }
            dismissSelectionMenu()
        }
    }

    override fun onCleared() { super.onCleared(); killPlayChain(); currentTTSProvider?.destroy() }
}
