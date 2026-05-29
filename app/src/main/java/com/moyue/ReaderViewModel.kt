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
import com.moyue.app.localai.LocalAiEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
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

/** A single entry in the navigation history stack */
data class NavHistoryEntry(
    val chapterIndex: Int,
    val chapterHref: String,
    val chapterLabel: String,
    val paragraphIndex: Int = 0,
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
    val fontFamily: String = "sans-serif",
    val fontWeight: String = "400",
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
    // Vocabulary quick-add from translation
    val showVocabToast: Boolean = false,
    val vocabToastMsg: String = "",
    val showBookmarkPanel: Boolean = false,
    val showHighlightPanel: Boolean = false,
    val currentParagraphIndex: Int = 0,       // 当前阅读/朗读的段落
    val scrollToParagraph: Int = -1,           // 需要滚动到的段落索引（-1 表示无）
    val highlightToRemove: Highlight? = null,    // Signal to remove a highlight in WebView
    // Navigation history
    val navHistory: List<NavHistoryEntry> = emptyList(),  // Stack of previous positions
    // Translation engine: cloud vs local
    val translateEngine: TranslateEngine = TranslateEngine.CLOUD,
    val localAiModelName: String = "",  // Name of loaded local model
    val localAiGpuLayers: Int = 0,  // GPU layers for Vulkan (0=CPU, 999=all GPU)
    // Dictionary query result (for structured data display)
    val isDictionaryResult: Boolean = false,
    val dictionaryDebugLog: String = "",
    val showDictionaryDebugLog: Boolean = false,
    // TTS Recording
    val isRecording: Boolean = false,
    val recordingChapterLabel: String = "",
    val recordingIsFullBook: Boolean = false,
    val recordingProgress: Int = 0,
    val recordingTotalSegments: Int = 0,
    val recordingCompletedSegments: Int = 0,
    val recordingCurrentText: String = "",
    val recordingBytesWritten: Long = 0L,
    val recordingResult: String? = null,  // success file path or error message
    val showRecordingDialog: Boolean = false,
    val showRecordingManager: Boolean = false,
    val recordingsList: List<com.moyue.app.ui.components.RecordingItem> = emptyList(),
) {
    val canGoBack: Boolean get() = navHistory.isNotEmpty()
}

class ReaderViewModel(
    application: Application,
    private val repository: BookRepository,
    private val translationService: TranslationService = TranslationService(),
) : AndroidViewModel(application) {

    companion object { private const val TAG = "ReaderVM" }

    // Activity context for System TTS (some ROMs require Activity, not Application)
    private var activityContext: Context? = null
    fun setActivityContext(ctx: Context) { activityContext = ctx }

    private val prefs = application.getSharedPreferences("moreader_config", Context.MODE_PRIVATE)

    // Local AI engine — completely independent of TranslationService
    private val localAiEngine = LocalAiEngine

    init {
        // Try to restore local AI model from previous session
        localAiEngine.init(application)
    }

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
            translateEngine = TranslateEngine.valueOf(prefs.getString("translate_engine", "CLOUD") ?: "CLOUD"),
            localAiModelName = localAiEngine.getModelName(application),
            localAiGpuLayers = localAiEngine.getGpuLayers(application),
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
    // Consecutive error counter — if TTS engine is broken, stop retrying
    private var consecutiveErrors = 0

    private fun log(msg: String) {
        Log.d(TAG, msg)
        // Also pull SystemTTSProvider's debug log
        val sysLog = com.moyue.app.tts.SystemTTSProvider.getDebugLog()
        _uiState.update { it.copy(ttsDebugLog = sysLog + it.ttsDebugLog + msg + "\n") }
    }

    private fun extractParagraphsFromHtml(html: String): List<String> {
        val doc = org.jsoup.Jsoup.parse(html)
        // 去掉注释符和拼音再提取文本（只删子元素不删段落，不影响索引对应）
        doc.select("sup").remove()
        doc.select("rt, rp").remove()
        val paragraphs = doc.select("p, h1, h2, h3, h4, h5, h6").map {
            var text = it.text().trim()
            // 文本级清理：方括号注释 [1] [2]、括号注释 ① ②、ruby 残留 /*/
            text = text.replace(Regex("""\[\d+\]"""), "")
            text = text.replace(Regex("""[①②③④⑤⑥⑦⑧⑨⑩]"""), "")
            text = text.replace(Regex("""/\*+/\s*"""), "")
            text.trim()
        }
        // 如果过滤后为空，返回整个 body 文本
        if (paragraphs.isEmpty() || paragraphs.all { it.isEmpty() }) { 
            val t = doc.body()?.text()?.trim() ?: "" 
            return if (t.length >= 2) listOf(t) else emptyList() 
        }
        return paragraphs
    }

    // ===== Navigation History =====
    
    /** Push current reading position onto the history stack (max 20 entries) */
    private fun pushToHistory() {
        val s = _uiState.value
        val chapter = s.chapters.getOrNull(s.currentChapterIndex) ?: return
        val entry = NavHistoryEntry(
            chapterIndex = s.currentChapterIndex,
            chapterHref = chapter.href,
            chapterLabel = chapter.id,
            paragraphIndex = s.currentParagraphIndex,
        )
        val newHistory = (s.navHistory + entry).takeLast(20)
        _uiState.update { it.copy(navHistory = newHistory) }
    }

    /** Navigate back to the previous reading position */
    fun goBack() {
        val s = _uiState.value
        if (s.navHistory.isEmpty()) return
        
        val prev = s.navHistory.last()
        val newHistory = s.navHistory.dropLast(1)
        val targetPara = prev.paragraphIndex
        
        killPlayChain()
        _uiState.update { 
            it.copy(
                currentChapterIndex = prev.chapterIndex,
                isLoading = true,
                currentHtml = null,
                navHistory = newHistory,
                ttsParagraphs = emptyList(),
                ttsCurrentIdx = -1,
                isTtsPaused = false,
                scrollToParagraph = -1,
            )
        }
        viewModelScope.launch {
            loadChapterContent()
            // Scroll to the saved paragraph — ALWAYS use the history entry's position,
            // not the DB-restored one (which may be for a different chapter)
            _uiState.update { it.copy(scrollToParagraph = targetPara) }
            saveProgress()
        }
    }

    // ===== Book =====
    fun loadBook(bookId: String) { viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true, loadingMessage = getApplication<android.app.Application>().getString(com.moyue.app.R.string.load_book)) }
        val book = repository.getBook(bookId) ?: run { _uiState.update { it.copy(isLoading = false, error = "Book not found") }; return@launch }
        _uiState.update { it.copy(loadingMessage = getApplication<android.app.Application>().getString(com.moyue.app.R.string.parse_chapters)) }
        val chapters = repository.parseSpine(bookId); val toc = repository.parseToc(bookId)
        if (chapters.isEmpty()) { _uiState.update { it.copy(book = book, isLoading = false, error = getApplication<android.app.Application>().getString(com.moyue.app.R.string.error_parse_failed)) }; return@launch }
        
        // Restore theme and font size from saved preference
        val restoredTheme = ReaderTheme.entries.find { it.id == book.themeId } ?: ReaderTheme.LIGHT
        
        // Restore per-book TTS config (empty = fall back to global prefs)
        val restoredTtsProvider = if (book.ttsProvider.isNotEmpty()) {
            runCatching { TTSProviderType.valueOf(book.ttsProvider) }.getOrDefault(TTSProviderType.EDGE_TTS)
        } else {
            TTSProviderType.entries.find { it.name == prefs.getString("tts_provider", null) }
                ?: TTSProviderType.EDGE_TTS
        }
        val restoredTtsVoice = if (book.ttsVoice.isNotEmpty()) book.ttsVoice
            else (prefs.getString("edge_voice", "zh-CN-XiaoxiaoNeural") ?: "zh-CN-XiaoxiaoNeural")
        val restoredTtsSpeed = if (book.ttsProvider.isNotEmpty() && book.ttsSpeed != 0f) book.ttsSpeed
            else prefs.getFloat("tts_speed", 1.0f)
        
        _uiState.update { it.copy(
            book = book, 
            chapters = chapters, 
            toc = toc, 
            currentChapterIndex = book.currentChapterIndex.coerceIn(0, maxOf(0, chapters.size - 1)), 
            theme = restoredTheme,
            fontSize = book.fontSize,
            ttsProvider = restoredTtsProvider,
            edgeTtsVoice = restoredTtsVoice,
            aiVoiceId = if (book.ttsVoice.isNotEmpty()) book.ttsVoice else it.aiVoiceId,
            customTtsVoice = if (book.ttsVoice.isNotEmpty()) book.ttsVoice else it.customTtsVoice,
            ttsSpeed = restoredTtsSpeed,
            isLoading = false, 
            loadingMessage = ""
        ) }
        loadChapterContent()
    } }
    private suspend fun loadChapterContent() {
        val s = _uiState.value; val b = s.book ?: return; if (s.chapters.isEmpty()) return
        _uiState.update { it.copy(isLoading = true, loadingMessage = getApplication<android.app.Application>().getString(com.moyue.app.R.string.load_content)) }
        val ch = s.chapters.getOrNull(s.currentChapterIndex) ?: run { _uiState.update { it.copy(isLoading = false, error = getApplication<android.app.Application>().getString(com.moyue.app.R.string.error_chapter_out_of_range)) }; return }
        val html = repository.getChapterContent(b.id, ch.href) ?: run { _uiState.update { it.copy(isLoading = false, error = getApplication<android.app.Application>().getString(com.moyue.app.R.string.error_read_failed)) }; return }
        val pl = extractParagraphsFromHtml(html)
        
        // Restore paragraph position if we're loading the same chapter we left off
        val restorePara = if (ch.href == b.currentChapterHref) b.currentParagraphIndex.coerceIn(0, maxOf(0, pl.size - 1)) else 0
        
        _uiState.update { 
            it.copy(
                currentHtml = html, 
                isLoading = false, 
                loadingMessage = "", 
                ttsParagraphs = pl, 
                ttsCurrentIdx = -1,
                currentParagraphIndex = restorePara,
            ) 
        }
        
        // Scroll to restored paragraph after a short delay
        if (restorePara > 0) {
            kotlinx.coroutines.delay(300)
            _uiState.update { it.copy(scrollToParagraph = restorePara) }
        }
    }
    fun nextChapter() { val s = _uiState.value; if (s.currentChapterIndex < s.chapters.size - 1) { killPlayChain(); _uiState.update { it.copy(currentChapterIndex = it.currentChapterIndex + 1, isLoading = true, currentHtml = null, ttsParagraphs = emptyList(), ttsCurrentIdx = -1, isTtsPaused = false) }; viewModelScope.launch { loadChapterContent(); saveProgress() } } }
    fun prevChapter() { val s = _uiState.value; if (s.currentChapterIndex > 0) { killPlayChain(); _uiState.update { it.copy(currentChapterIndex = it.currentChapterIndex - 1, isLoading = true, currentHtml = null, ttsParagraphs = emptyList(), ttsCurrentIdx = -1, isTtsPaused = false) }; viewModelScope.launch { loadChapterContent(); saveProgress() } } }
    fun navigateToChapter(href: String) { 
        pushToHistory()
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
    private suspend fun saveProgress() { 
        val s = _uiState.value; val b = s.book ?: return; val c = s.chapters.getOrNull(s.currentChapterIndex) ?: return
        val maxIdx = s.ttsParagraphs.size.coerceAtMost(100) - 1
        val paraIdx = if (maxIdx < 0) 0 else s.currentParagraphIndex.coerceIn(0, maxIdx)
        repository.updateProgress(b.id, c.href, s.currentChapterIndex, b.currentProgress, null, paraIdx, s.theme.id, s.fontSize) 
    }
    fun setTheme(t: ReaderTheme) { 
        _uiState.update { it.copy(theme = t) }
        val book = _uiState.value.book ?: return
        viewModelScope.launch {
            repository.updateBookTheme(book.id, t.id)
        }
    }
    fun setFontSize(s: Int) { 
        val size = s.coerceIn(14, 28)
        _uiState.update { it.copy(fontSize = size) }
        val book = _uiState.value.book ?: return
        viewModelScope.launch {
            repository.updateBookFontSize(book.id, size)
        }
    }
    fun setFontFamily(f: String) { _uiState.update { it.copy(fontFamily = f) } }
    fun setFontWeight(w: String) { _uiState.update { it.copy(fontWeight = w) } }
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
    fun onLinkClicked(url: String, visibleParaIdx: Int = 0) { 
        // Update current paragraph to visible position before pushing to history
        _uiState.update { it.copy(currentParagraphIndex = visibleParaIdx) }
        // Handle internal EPUB link clicks
        pushToHistory()
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
    fun copyTtsDebugLog() {
        val log = _uiState.value.ttsDebugLog
        val clipboard = getApplication<android.app.Application>()
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TTS Log", log))
    }
    fun translate(mode: String = "translate") {
        val t = _uiState.value.selectedText ?: return
        _uiState.update { it.copy(isTranslating = true, translationMode = mode, showTranslationPanel = true, translationResult = null, isDictionaryResult = false, dictionaryDebugLog = "") }

        // === Dictionary mode: try local dictionary, fallback to AI with label ===
        if (mode == "dictionary") {
            com.moyue.app.localai.DictionaryEngine.clearDebugLog()  // clear BEFORE init so we keep init logs
            com.moyue.app.localai.DictionaryEngine.init(getApplication())
            val dictResult = com.moyue.app.localai.DictionaryEngine.query(t.trim())
            val debugLog = com.moyue.app.localai.DictionaryEngine.getDebugLog()
            
            if (dictResult is com.moyue.app.data.models.DictionaryResult.Found) {
                // Found in dictionary — show instantly
                val ctx = getApplication<android.app.Application>()
                _uiState.update { 
                    it.copy(
                        isTranslating = false,
                        translationResult = dictResult.entry.formatForDisplay(ctx),
                        isDictionaryResult = true,
                        dictionaryDebugLog = debugLog
                    )
                }
                return
            }
            // Not in dictionary — fallback to AI translation with label
            _uiState.update { 
                it.copy(
                    translationResult = "${getApplication<android.app.Application>().getString(com.moyue.app.R.string.dict_not_found_fallback)}\n\n",
                    dictionaryDebugLog = debugLog
                )
            }
            // Fall through to AI/cloud route below
        }

        // === Translate mode: try dictionary as hidden optimization for short English words ===
        if (mode == "translate") {
            val isShortWord = t.trim().length < 50 && t.none { it in '\u4e00'..'\u9fff' }
            if (isShortWord) {
                com.moyue.app.localai.DictionaryEngine.init(getApplication())
                com.moyue.app.localai.DictionaryEngine.clearDebugLog()
                val dictResult = com.moyue.app.localai.DictionaryEngine.query(t.trim())
                val debugLog = com.moyue.app.localai.DictionaryEngine.getDebugLog()
                if (dictResult is com.moyue.app.data.models.DictionaryResult.Found) {
                    val ctx = getApplication<android.app.Application>()
                    _uiState.update { 
                        it.copy(
                            isTranslating = false,
                            translationResult = dictResult.entry.formatForDisplay(ctx),
                            isDictionaryResult = true,
                            dictionaryDebugLog = debugLog
                        )
                    }
                    return
                }
                _uiState.update { it.copy(dictionaryDebugLog = debugLog) }
            }
        }

        if (_uiState.value.translateEngine == TranslateEngine.LOCAL) {
            // === Local AI route ===
            if (!localAiEngine.isReady()) {
                _uiState.update { it.copy(isTranslating = false, translationResult = getApplication<android.app.Application>().getString(com.moyue.app.R.string.local_model_not_loaded)) }
                return
            }
            viewModelScope.launch {
                val result = localAiEngine.translate(t)
                result.onFailure { e ->
                    _uiState.update { it.copy(isTranslating = false, translationResult = getApplication<android.app.Application>().getString(com.moyue.app.R.string.translation_failed_fmt, e.message ?: "")) }
                }.onSuccess { text ->
                    _uiState.update { it.copy(isTranslating = false, translationResult = text) }
                }
            }
        } else {
            // === Cloud API route (existing code unchanged) ===
            val c = _uiState.value.llmConfig
            if (c.apiKey.isEmpty()) {
                _uiState.update { it.copy(translationResult = getApplication<android.app.Application>().getString(com.moyue.app.R.string.config_api_key_first), showTranslationPanel = true) }
                return
            }
            viewModelScope.launch {
                val r = translationService.translate(c, t, mode) { ch ->
                    _uiState.update { it.copy(translationResult = (it.translationResult ?: "") + ch) }
                }
                r.onFailure { e ->
                    _uiState.update { it.copy(isTranslating = false, translationResult = getApplication<android.app.Application>().getString(com.moyue.app.R.string.translation_failed_fmt, e.message ?: "")) }
                }.onSuccess {
                    _uiState.update { it.copy(isTranslating = false) }
                }
            }
        }
    }
    fun dismissTranslationPanel() { _uiState.update { it.copy(showTranslationPanel = false, translationResult = null) } }

    /** Add the currently selected text + translation result to vocabulary in one tap */
    fun addSelectedWordToVocabulary() {
        val word = _uiState.value.selectedText?.trim() ?: return
        val translation = _uiState.value.translationResult ?: ""
        if (word.isEmpty()) return

        val book = _uiState.value.book
        val s = _uiState.value

        viewModelScope.launch {
            try {
                // Check if already exists
                val exists = repository.isWordExists(word)
                if (exists) {
                    _uiState.update { it.copy(showVocabToast = true, vocabToastMsg = getApplication<android.app.Application>().getString(com.moyue.app.R.string.vocabulary_already_exists)) }
                    return@launch
                }

                val isChinese = word.any { it in '\u4e00'..'\u9fff' }
                val vocab: Vocabulary

                // If result came from dictionary, use structured data directly
                if (s.isDictionaryResult) {
                    com.moyue.app.localai.DictionaryEngine.init(getApplication())
                    val dictResult = com.moyue.app.localai.DictionaryEngine.query(word)
                    if (dictResult is com.moyue.app.data.models.DictionaryResult.Found) {
                        val entry = dictResult.entry
                        vocab = Vocabulary(
                            word = entry.word,
                            pronunciation = entry.phonetic,
                            chineseDef = entry.translation.takeIf { it.isNotEmpty() },
                            englishDef = null,
                            wordForms = entry.exchangeJson,
                            bookId = book?.id?.toLongOrNull(),
                            chapterIndex = s.currentChapterIndex,
                        )
                    } else {
                        // Fallback to raw text
                        vocab = Vocabulary(
                            word = word,
                            definition = translation.takeIf { it.isNotEmpty() },
                            bookId = book?.id?.toLongOrNull(),
                            chapterIndex = s.currentChapterIndex,
                        )
                    }
                } else {
                    // AI/Cloud translation result — store as-is
                    vocab = Vocabulary(
                        word = word,
                        chineseDef = if (isChinese) translation.takeIf { it.isNotEmpty() } else null,
                        englishDef = if (!isChinese) translation.takeIf { it.isNotEmpty() } else null,
                        definition = translation.takeIf { it.isNotEmpty() },
                        bookId = book?.id?.toLongOrNull(),
                        chapterIndex = s.currentChapterIndex,
                    )
                }

                repository.insertVocabulary(vocab)
                _uiState.update { it.copy(showVocabToast = true, vocabToastMsg = getApplication<android.app.Application>().getString(com.moyue.app.R.string.vocabulary_added)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(showVocabToast = true, vocabToastMsg = getApplication<android.app.Application>().getString(com.moyue.app.R.string.vocab_add_failed, e.message)) }
            }
        }
    }

    fun dismissVocabToast() { _uiState.update { it.copy(showVocabToast = false) } }

    fun copyDictionaryDebugLog() {
        val log = _uiState.value.dictionaryDebugLog
        if (log.isEmpty()) return
        viewModelScope.launch {
            try {
                val clipboard = getApplication<android.app.Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Dictionary Debug Log", log))
                _uiState.update { it.copy(showVocabToast = true, vocabToastMsg = getApplication<android.app.Application>().getString(com.moyue.app.R.string.dict_log_copied)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(showVocabToast = true, vocabToastMsg = getApplication<android.app.Application>().getString(com.moyue.app.R.string.copy_failed_fmt, e.message)) }
            }
        }
    }

    fun toggleDictionaryDebugLog() {
        _uiState.update { it.copy(showDictionaryDebugLog = !it.showDictionaryDebugLog) }
    }

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
            TTSProviderType.SYSTEM -> {
                // System TTS needs Activity context on some ROMs (e.g. ColorOS)
                val ctx = activityContext ?: getApplication()
                SystemTTSProvider(ctx).also { currentTTSProvider = it }
            }
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
                log("[TTS] 📖 ${getApplication<android.app.Application>().getString(com.moyue.app.R.string.error_tts_chapter_complete)}")
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
                log("[TTS] ${getApplication<android.app.Application>().getString(com.moyue.app.R.string.error_tts_all_complete)}")
                _uiState.update { it.copy(isTtsPlaying = false, ttsCurrentIdx = -1, ttsPlayIdx = -1) }
            }
            return
        }

        // Highlight = current index - ttsHighlightOffset (user-adjustable, negative = earlier)
        val offset = s.ttsHighlightOffset
        val highlightIdx = (idx - offset).coerceIn(0, maxOf(0, paragraphs.size - 1))
        // Note: highlight update moved to listener.onStart() to sync with actual audio playback

        val text = paragraphs[idx]
        // Skip blank paragraphs entirely — prevents infinite error loops
        if (text.isBlank()) { playOne(idx + 1); return }
        val speed = s.ttsSpeed
        val cached = audioCache.remove(idx)

        val listener = object : TTSListener {
            override fun onStart() { _uiState.update { it.copy(ttsCurrentIdx = highlightIdx, ttsPlayIdx = idx) } }
            override fun onDone() { consecutiveErrors = 0; playOne(idx + 1) }
            override fun onError(msg: String) {
                log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_engine_error, idx, msg))
                consecutiveErrors++
                if (consecutiveErrors >= 3) {
                    log("[TTS] ⛔ ${getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_stop)}")
                    killPlayChain()
                    _uiState.update { it.copy(isTtsPlaying = false, isTtsPaused = false, ttsCurrentIdx = -1, ttsPlayIdx = -1) }
                } else {
                    playOne(idx + 1)
                }
            }
        }

        if (cached != null && cached.isNotEmpty()) {
            // Play cached audio directly (zero network delay!)
            when (val p = getProvider()) {
                is EdgeTTSProvider -> p.playRaw(cached, listener)
                is AIVoiceTTSProvider -> p.playRaw(cached, listener)
                is CustomTTSProvider -> p.playRaw(cached, listener)
                is SystemTTSProvider -> p.speak(text, speed, listener)
            }
        } else {
            val p = recreateProvider()
            if (p == null) { _uiState.update { it.copy(isTtsPlaying = false, ttsCurrentIdx = -1, ttsPlayIdx = -1) }; return }

            // System TTS: just speak, onDone/onStart drive the chain
            if (p is SystemTTSProvider) {
                p.speak(text, speed, listener)
            } else if (p != null) {
                p.speak(text, speed, listener)
            }
        }

        // Preload next paragraphs in background (for non-System providers)
        if (s.ttsProvider != TTSProviderType.SYSTEM) {
            preloadRange(idx + 1, idx + 6)
        }
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
        consecutiveErrors = 0
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
        consecutiveErrors = 0
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
    fun ttsResume() {
        log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_resume))
        val s = _uiState.value
        val playIdx = s.ttsPlayIdx
        if (s.ttsParagraphs.isEmpty() || playIdx < 0) {
            log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_invalid_restore, playIdx))
            return
        }
        // Lightweight reset: just stop current speech, don't shutdown the engine.
        // fullDestroy() + re-init causes Google TTS to accept speak() but never
        // fire onStart on ColorOS, breaking the chain.
        currentTTSProvider?.stop()
        playChainActive = true
        _uiState.update { it.copy(isTtsPaused = false, isTtsPlaying = true, ttsCurrentIdx = playIdx) }
        playOne(playIdx)
    }
    private fun killPlayChain() { playChainActive = false; currentTTSProvider?.stop(); audioCache.clear() }
    fun ttsStop() { log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_stop)); killPlayChain(); _uiState.update { it.copy(isTtsPlaying = false, isTtsPaused = false, ttsCurrentIdx = -1, ttsPlayIdx = -1) } }
    private var lastToggleTime = 0L

    fun togglePlayPause() {
        val now = System.currentTimeMillis()
        if (now - lastToggleTime < 300) return // debounce: prevent rapid double-taps
        lastToggleTime = now
        val s = _uiState.value; when { s.isTtsPlaying -> ttsPause(); s.isTtsPaused -> ttsResume(); else -> readChapter() }
    }

    fun setTTSProvider(type: TTSProviderType) { log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_switch_engine, type.name)); killPlayChain(); currentTTSProvider?.destroy(); currentTTSProvider = null; _uiState.update { it.copy(ttsProvider = type) }; savePerBookTtsConfig() }
    fun setTTSSpeed(s: Float) {
        _uiState.update { it.copy(ttsSpeed = s.coerceIn(0.5f, 2.0f)) }
        prefs.edit().putFloat("tts_speed", s.coerceIn(0.5f, 2.0f)).apply()
        // Save per-book TTS config
        val book = _uiState.value.book
        if (book != null) {
            viewModelScope.launch { repository.updateBookTtsConfig(book.id, _uiState.value.ttsProvider.name, _uiState.value.edgeTtsVoice, s.coerceIn(0.5f, 2.0f)) }
        }
    }
    fun increaseHighlightOffset() { _uiState.update { it.copy(ttsHighlightOffset = it.ttsHighlightOffset + 1) }; log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_offset_inc, _uiState.value.ttsHighlightOffset)); prefs.edit().putInt("tts_highlight_offset", _uiState.value.ttsHighlightOffset).apply() }
    fun decreaseHighlightOffset() { _uiState.update { it.copy(ttsHighlightOffset = it.ttsHighlightOffset - 1) }; log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_offset_dec, _uiState.value.ttsHighlightOffset)); prefs.edit().putInt("tts_highlight_offset", _uiState.value.ttsHighlightOffset).apply() }
    fun updateEdgeTTSConfig(endpoint: String, voice: String) { log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_edge_config, voice)); killPlayChain(); currentTTSProvider?.destroy(); currentTTSProvider = null; _uiState.update { it.copy(edgeTtsEndpoint = endpoint, edgeTtsVoice = voice) }; prefs.edit().putString("edge_endpoint", endpoint).putString("edge_voice", voice).apply(); savePerBookTtsConfig() }
    fun updateAIVoiceConfig(endpoint: String, apiKey: String, model: String, voice: String) { log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_ai_config, voice)); killPlayChain(); currentTTSProvider?.destroy(); currentTTSProvider = null; _uiState.update { it.copy(aiVoiceEndpoint = endpoint, aiVoiceApiKey = apiKey, aiVoiceModel = model, aiVoiceId = voice) }; prefs.edit().putString("ai_endpoint", endpoint).putString("ai_apikey", apiKey).putString("ai_model", model).putString("ai_voice_id", voice).apply(); savePerBookTtsConfig() }
    fun updateCustomTTSConfig(endpoint: String, apiKey: String, model: String, voice: String) { log("Custom TTS: $endpoint, model=$model, voice=$voice"); killPlayChain(); currentTTSProvider?.destroy(); currentTTSProvider = null; _uiState.update { it.copy(customTtsEndpoint = endpoint, customTtsApiKey = apiKey, customTtsModel = model, customTtsVoice = voice) }; prefs.edit().putString("custom_endpoint", endpoint).putString("custom_apikey", apiKey).putString("custom_model", model).putString("custom_voice", voice).apply(); savePerBookTtsConfig() }
    fun toggleFullscreen() { _uiState.update { it.copy(isFullscreen = !it.isFullscreen) } }
    fun setFullscreen(enabled: Boolean) { _uiState.update { it.copy(isFullscreen = enabled) } }

    /** Save current TTS config to the current book's record */
    private fun savePerBookTtsConfig() {
        val book = _uiState.value.book ?: return
        val s = _uiState.value
        val voice = when (s.ttsProvider) {
            TTSProviderType.EDGE_TTS -> s.edgeTtsVoice
            TTSProviderType.AI_VOICE -> s.aiVoiceId
            TTSProviderType.CUSTOM_TTS -> s.customTtsVoice
            TTSProviderType.SYSTEM -> ""
        }
        viewModelScope.launch {
            repository.updateBookTtsConfig(book.id, s.ttsProvider.name, voice, s.ttsSpeed)
        }
    }
    fun updateLLMConfig(c: LLMConfig) { _uiState.update { it.copy(llmConfig = c) }; prefs.edit().putString("llm_provider", c.provider).putString("llm_apikey", c.apiKey).putString("llm_endpoint", c.endpoint).putString("llm_model", c.model).apply() }

    // === Local AI engine management ===
    fun setTranslateEngine(engine: TranslateEngine) {
        _uiState.update { it.copy(translateEngine = engine, localAiModelName = localAiEngine.getModelName(getApplication()), localAiGpuLayers = localAiEngine.getGpuLayers(getApplication())) }
        prefs.edit().putString("translate_engine", engine.name).apply()
    }

    suspend fun loadLocalAiModel(uri: android.net.Uri) {
        _uiState.update { it.copy(loadingMessage = "Loading local AI model...") }
        val result = localAiEngine.loadModelFromUri(getApplication(), uri)
        result.onSuccess { msg ->
            _uiState.update { it.copy(
                localAiModelName = localAiEngine.getModelName(getApplication()),
                localAiGpuLayers = localAiEngine.getGpuLayers(getApplication()),
                loadingMessage = ""
            ) }
        }.onFailure { e ->
            _uiState.update { it.copy(localAiModelName = "Failed: ${e.message}", loadingMessage = "") }
        }
    }

    fun unloadLocalAiModel() {
        localAiEngine.releaseModel(getApplication())
        _uiState.update { it.copy(localAiModelName = "", localAiGpuLayers = 0, translateEngine = TranslateEngine.CLOUD) }
        prefs.edit().putString("translate_engine", TranslateEngine.CLOUD.name).apply()
    }

    fun getLocalAiLogs(): String = localAiEngine.getLogs()

    fun clearLocalAiLogs() = localAiEngine.clearLogs()

    fun setGpuLayers(layers: Int) {
        val app = getApplication<android.app.Application>()
        val current = localAiEngine.getGpuLayers(app)
        if (layers == current) return  // No change, skip reload
        localAiEngine.setGpuLayers(app, layers)
        _uiState.update { it.copy(localAiGpuLayers = layers) }
        // Reload model with new GPU setting
        if (localAiEngine.isReady()) {
            localAiEngine.releaseModel(app)
            localAiEngine.reloadModel(app)
        }
    }

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
        pushToHistory()
        _uiState.update { it.copy(showBookmarkPanel = false) }
        val s = _uiState.value
        val chapters = s.chapters
        
        // 如果书签不在当前章节，先跳转章节
        if (bookmark.chapterIndex != s.currentChapterIndex) {
            val targetChapter = chapters.getOrNull(bookmark.chapterIndex)
            if (targetChapter != null) {
                killPlayChain()
                // Reset scrollToParagraph first to ensure the LaunchedEffect triggers
                _uiState.update { 
                    it.copy(
                        currentChapterIndex = bookmark.chapterIndex, 
                        isLoading = true, 
                        currentHtml = null, 
                        ttsParagraphs = emptyList(), 
                        ttsCurrentIdx = -1, 
                        isTtsPaused = false,
                        scrollToParagraph = -1,
                    ) 
                }
                viewModelScope.launch {
                    loadChapterContent()
                    // Force a fresh scroll after content is fully loaded
                    kotlinx.coroutines.delay(200)
                    _uiState.update { it.copy(scrollToParagraph = bookmark.paragraphIndex) }
                }
            }
        } else {
            // 同一章节，直接滚动到段落 — 先重置再设置，确保 LaunchedEffect 触发
            _uiState.update { it.copy(scrollToParagraph = -1) }
            viewModelScope.launch {
                kotlinx.coroutines.delay(100)
                _uiState.update { it.copy(scrollToParagraph = bookmark.paragraphIndex) }
            }
        }
    }

    fun toggleBookmarkPanel() { _uiState.update { it.copy(showBookmarkPanel = !it.showBookmarkPanel) } }
    fun deleteHighlight(highlight: Highlight) {
        viewModelScope.launch {
            repository.deleteHighlight(highlight)
            // Remove from in-memory list so it disappears from rendering
            _highlights.update { it.filter { h -> h.id != highlight.id } }
            // Signal WebView to remove the visual highlight
            _uiState.update { it.copy(highlightToRemove = highlight) }
            kotlinx.coroutines.delay(200)
            _uiState.update { it.copy(highlightToRemove = null) }
        }
    }

    fun toggleHighlightPanel() { _uiState.update { it.copy(showHighlightPanel = !it.showHighlightPanel) } }

    fun navigateToHighlight(highlight: Highlight) {
        _uiState.update { it.copy(showHighlightPanel = false) }
        val s = _uiState.value
        val chapters = s.chapters
        
        // If highlight is in a different chapter, navigate there first
        if (highlight.chapterIndex != s.currentChapterIndex) {
            val targetChapter = chapters.getOrNull(highlight.chapterIndex)
            if (targetChapter != null) {
                pushToHistory()
                killPlayChain()
                _uiState.update { 
                    it.copy(
                        currentChapterIndex = highlight.chapterIndex, 
                        isLoading = true, 
                        currentHtml = null, 
                        ttsParagraphs = emptyList(), 
                        ttsCurrentIdx = -1, 
                        isTtsPaused = false 
                    ) 
                }
                viewModelScope.launch {
                    loadChapterContent()
                    // Scroll to the highlight paragraph
                    _uiState.update { it.copy(scrollToParagraph = highlight.startParagraph) }
                }
            }
        } else {
            // Same chapter, just scroll to paragraph
            pushToHistory()
            _uiState.update { it.copy(scrollToParagraph = highlight.startParagraph) }
        }
    }

    fun setCurrentParagraph(idx: Int) { 
        _uiState.update { it.copy(currentParagraphIndex = idx) }
        // Persist paragraph position
        val book = _uiState.value.book ?: return
        viewModelScope.launch {
            repository.updateBookParagraph(book.id, idx)
        }
    }

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

    // ===== TTS Recording =====

    private var recordingJob: Job? = null

    fun showRecordingDialog() { _uiState.update { it.copy(showRecordingDialog = true) } }
    fun hideRecordingDialog() { _uiState.update { it.copy(showRecordingDialog = false) } }

    /** Start recording: isFullBook=true for entire book, false for current chapter only */
    fun startRecording(isFullBook: Boolean) {
        val s = _uiState.value
        val book = s.book ?: return
        val chapters = s.chapters
        if (chapters.isEmpty()) return

        hideRecordingDialog()

        // Cancel any existing recording
        recordingJob?.cancel()

        val targetChapters = if (isFullBook) {
            chapters
        } else {
            listOf(chapters[s.currentChapterIndex])
        }

        val chapterLabel = if (isFullBook) book.title else targetChapters.firstOrNull()?.id ?: "chapter"

        recordingJob = viewModelScope.launch {
            _uiState.update { it.copy(
                isRecording = true,
                recordingChapterLabel = chapterLabel,
                recordingIsFullBook = isFullBook,
                recordingProgress = 0,
                recordingResult = null,
            )}

            val outputDir = TtsRecorder.getRecordingsDir(getApplication(), book.id)
            val filename = TtsRecorder.generateFilename(book.title, if (isFullBook) null else chapterLabel)
            val outputFile = File(outputDir, filename)

            // Collect all text segments from target chapters
            val allSegments = mutableListOf<String>()
            for (chapter in targetChapters) {
                val html = repository.getChapterContent(book.id, chapter.href)
                if (html != null) {
                    allSegments.addAll(extractParagraphsFromHtml(html))
                }
            }

            if (allSegments.isEmpty()) {
                _uiState.update { it.copy(
                    isRecording = false,
                    recordingResult = "没有可录制的文本内容",
                )}
                return@launch
            }

            // Get the current TTS provider
            val provider = recreateProvider()
            if (provider == null) {
                _uiState.update { it.copy(
                    isRecording = false,
                    recordingResult = "TTS 引擎未就绪",
                )}
                return@launch
            }

            // Check if provider supports recording (System TTS does not)
            if (provider is SystemTTSProvider) {
                _uiState.update { it.copy(
                    isRecording = false,
                    recordingResult = "系统TTS不支持录音，请切换到 Edge TTS / AI Voice / 自定义TTS",
                )}
                return@launch
            }

            log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_recording_started, chapterLabel, allSegments.size))

            val speed = s.ttsSpeed

            val result = TtsRecorder.record(
                fetchAudio = { text ->
                    when (provider) {
                        is EdgeTTSProvider -> provider.fetchAudio(text, speed)
                        is AIVoiceTTSProvider -> provider.fetchAudio(text, speed)
                        is CustomTTSProvider -> provider.fetchAudio(text, speed)
                        else -> null
                    }
                },
                segments = allSegments,
                outputFile = outputFile,
                onProgress = { progress ->
                    _uiState.update { it.copy(
                        recordingProgress = progress.percentage,
                        recordingTotalSegments = progress.totalSegments,
                        recordingCompletedSegments = progress.currentSegment,
                        recordingCurrentText = progress.currentText,
                        recordingBytesWritten = progress.bytesWritten,
                    )}
                },
            )

            when (result) {
                is TtsRecorder.Result.Success -> {
                    log(getApplication<android.app.Application>().getString(com.moyue.app.R.string.tts_log_recording_done, result.file.name, result.totalBytes / 1024))
                    _uiState.update { it.copy(
                        isRecording = false,
                        recordingResult = "success:${result.file.absolutePath}",
                    )}
                }
                is TtsRecorder.Result.Cancelled -> {
                    _uiState.update { it.copy(
                        isRecording = false,
                        recordingResult = "cancelled",
                    )}
                }
                is TtsRecorder.Result.Error -> {
                    _uiState.update { it.copy(
                        isRecording = false,
                        recordingResult = "error:${result.message}",
                    )}
                }
            }
        }
    }

    fun cancelRecording() {
        recordingJob?.cancel()
        _uiState.update { it.copy(
            isRecording = false,
            recordingResult = "cancelled",
        )}
    }

    fun clearRecordingResult() {
        _uiState.update { it.copy(recordingResult = null) }
    }

    // ===== Browse Recordings =====

    fun showRecordingManager() {
        viewModelScope.launch {
            val recordings = loadAllRecordings()
            _uiState.update { it.copy(
                showRecordingManager = true,
                recordingsList = recordings,
            )}
        }
    }

    fun hideRecordingManager() {
        _uiState.update { it.copy(showRecordingManager = false) }
    }

    private fun loadAllRecordings(): List<com.moyue.app.ui.components.RecordingItem> {
        val context = getApplication<android.app.Application>()
        val recordingsDir = File(context.filesDir, "recordings")
        if (!recordingsDir.exists()) return emptyList()

        val items = mutableListOf<com.moyue.app.ui.components.RecordingItem>()
        recordingsDir.listFiles()?.forEach { bookDir ->
            if (bookDir.isDirectory) {
                val bookId = bookDir.name
                val bookTitle = _uiState.value.book?.takeIf { it.id == bookId }?.title ?: bookId
                bookDir.listFiles { f -> f.extension == "mp3" }?.forEach { file ->
                    items.add(com.moyue.app.ui.components.RecordingItem(
                        file = file,
                        bookTitle = bookTitle,
                        chapterLabel = null,
                        sizeKB = file.length() / 1024,
                        createdAt = file.lastModified(),
                    ))
                }
            }
        }
        // Sort by creation time, newest first
        return items.sortedByDescending { it.createdAt }
    }

    fun deleteRecording(file: File) {
        file.delete()
        // Reload the list
        val recordings = loadAllRecordings()
        _uiState.update { it.copy(recordingsList = recordings) }
    }

    fun shareRecording(file: File) {
        val context = getApplication<android.app.Application>()
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "audio/mpeg"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, null))
    }

    override fun onCleared() { super.onCleared(); killPlayChain(); currentTTSProvider?.destroy(); recordingJob?.cancel() }
}
