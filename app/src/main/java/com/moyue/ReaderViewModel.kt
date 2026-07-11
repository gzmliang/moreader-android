package com.moyue.app.ui

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
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
import java.util.Date
import java.util.Locale
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
    val systemTtsVoice: String = "",
    val ttsParagraphs: List<String> = emptyList(),
    val ttsCurrentIdx: Int = -1,       // 当前高亮索引（考虑了偏移）
    val ttsPlayIdx: Int = -1,          // 当前实际朗读的段落索引（用于恢复）
    val ttsHighlightOffset: Int = 0,  // 高亮偏移量（负 = 高亮提前，正 = 高亮延迟），默认0
    val ttsSentenceIdx: Int = -1,       // 当前句子高亮索引
    val ttsSentenceCount: Int = 0,      // 当前段落总句子数
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
    val showVocabPlanPicker: Boolean = false,
    val vocabPlanOptions: List<String> = listOf("默认"),
    val showBookmarkPanel: Boolean = false,
    val showHighlightPanel: Boolean = false,
    val currentParagraphIndex: Int = 0,       // 当前阅读/朗读的段落
    val scrollToParagraph: Int = -1,           // 需要滚动到的段落索引（-1 表示无）
    val scrollToAnchor: String? = null,          // 需要滚动到的 HTML 锚点（如 filepos0000154187）
    val isChapterSwitching: Boolean = false,     // 章节切换过渡期标记，暂停 scroll 监听写 DB
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
            systemTtsVoice = prefs.getString("system_tts_voice", "") ?: "",
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
    // ======== 句子级高亮 ========
    private var sentenceEnds = IntArray(0)           // 当前段句子边界（原文字符偏移）
    private var pendingBoundaries: List<com.moyue.app.tts.WordBoundary>? = null  // onWordBoundaries 在 onStart 之前
    private val boundariesCache = mutableMapOf<Int, List<com.moyue.app.tts.WordBoundary>>()  // 预加载词边界
    private var estJob: kotlinx.coroutines.Job? = null  // 句子跟踪协程
    private val SENTENCE_REGEX = Regex("(?<=[.!?。！？；;])\\s*['\"\\u00BB\\u00AB\\u201C\\u201D\\u2018\\u2019\\u300C\\u300D\\u300E\\u300F]*")

    private fun log(msg: String) {
        Log.d(TAG, msg)
        // Also pull SystemTTSProvider's debug log
        val sysLog = com.moyue.app.tts.SystemTTSProvider.getDebugLog()
        _uiState.update { it.copy(ttsDebugLog = sysLog + it.ttsDebugLog + msg + "\n") }
    }

    private fun extractParagraphsFromHtml(html: String): List<String> {
        val doc = org.jsoup.Jsoup.parse(html)
        // ⚠️ 不要 remove 任何元素！WebView DOM 用 querySelectorAll('p,h1-h6') 获取段落索引，
        // 这里必须和 DOM 完全对齐，否则保存的段落索引恢复时会跳到错误位置。
        // 只做文本级清理（去注音/脚注标记的文字内容），不改变 DOM 结构。
        val paragraphs = doc.select("p, h1, h2, h3, h4, h5, h6").map { para ->
            var text = para.text().trim()
            // 清除脚注标记 [1] [2] ...
            text = text.replace(Regex("""\[\d+]"""), "")
            // 清除圈号 ①②③...
            text = text.replace(Regex("""[①②③④⑤⑥⑦⑧⑨⑩]"""), "")
            // 清理注释分隔符 /*/ ／＊／
            text = text.replace(Regex("""/\*+/\s*"""), "")
            text = text.replace(Regex("""／\＊+／\s*"""), "")
            // 清除纯装饰符号段落
            text = text.replace(Regex("""[*＊·•●▶▷◀◁◆◇○◎●◉○□■△▲☆★❀✿❁🌸🌺]"""), "")
            // 清除其他标记符号 ** ## __ ~~ ``
            text = text.replace(Regex("""[*]{2,}"""), "")
            text = text.replace(Regex("""[#]{2,}"""), "")
            text = text.replace(Regex("""[_]{2,}"""), "")
            text = text.replace(Regex("""[~]{2,}"""), "")
            text = text.replace(Regex("""`{2,}"""), "")
            text = text.trim()
            // 删除汉字间的空格（Edge TTS 把空格当词边界）
            text = text.replace(Regex("""([\u4e00-\u9fff\u3040-\u309f\u30a0-\u30ff])\s+(?=[\u4e00-\u9fff\u3040-\u309f\u30a0-\u30ff])"""), "$1")
            // 纯符号段落置空（保留索引不删行，playOne 自动跳过空段落）
            if (text.isNotEmpty() && text.none { c -> (c in '\u4e00'..'\u9fff') || c.isLetter() }) "" else text
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
                scrollToAnchor = null,
                isChapterSwitching = true,
            )
        }
        viewModelScope.launch {
            loadChapterContent()
            _uiState.update { it.copy(isChapterSwitching = false) }
            // Scroll to the saved paragraph - ALWAYS use the history entry's position,
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
        // 首次打开书本时，批量修复跨平台高亮的章节/偏移量
        repairAllCrossPlatformHighlights(book.id)
    } }
    private suspend fun loadChapterContent(forceStart: Boolean = false) {
        val s = _uiState.value; val b = s.book ?: return; if (s.chapters.isEmpty()) return
        _uiState.update { it.copy(isLoading = true, loadingMessage = getApplication<android.app.Application>().getString(com.moyue.app.R.string.load_content)) }
        val ch = s.chapters.getOrNull(s.currentChapterIndex) ?: run { _uiState.update { it.copy(isLoading = false, error = getApplication<android.app.Application>().getString(com.moyue.app.R.string.error_chapter_out_of_range)) }; return }
        val html = repository.getChapterContent(b.id, ch.href) ?: run { _uiState.update { it.copy(isLoading = false, error = getApplication<android.app.Application>().getString(com.moyue.app.R.string.error_read_failed)) }; return }
        val pl = extractParagraphsFromHtml(html)
        
        // Restore paragraph position: use saved position only when reopening the same chapter,
        // not when explicitly navigating via TOC or links
        val restorePara = if (forceStart) 0
            else if (ch.href == b.currentChapterHref) b.currentParagraphIndex.coerceIn(0, maxOf(0, pl.size - 1))
            else 0
        log("ChapterNav: ch=${ch.href} forceStart=$forceStart curCHref=${b.currentChapterHref} restorePara=$restorePara idx=${s.currentChapterIndex}")
        
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
        } else {
            // forceStart 或新章节：显式滚到顶部，防止 WebView 保留旧滚动位置
            kotlinx.coroutines.delay(300)
            _uiState.update { it.copy(scrollToParagraph = 0) }
        }
    }
    fun nextChapter() { val s = _uiState.value; if (s.currentChapterIndex < s.chapters.size - 1) { killPlayChain(); _uiState.update { it.copy(currentChapterIndex = it.currentChapterIndex + 1, isLoading = true, currentHtml = null, ttsParagraphs = emptyList(), ttsCurrentIdx = -1, isTtsPaused = false, currentParagraphIndex = 0, isChapterSwitching = true) }; viewModelScope.launch { loadChapterContent(forceStart = true); _uiState.update { it.copy(isChapterSwitching = false) }; saveProgress() } } }
    fun prevChapter() { val s = _uiState.value; if (s.currentChapterIndex > 0) { killPlayChain(); _uiState.update { it.copy(currentChapterIndex = it.currentChapterIndex - 1, isLoading = true, currentHtml = null, ttsParagraphs = emptyList(), ttsCurrentIdx = -1, isTtsPaused = false, currentParagraphIndex = 0, isChapterSwitching = true) }; viewModelScope.launch { loadChapterContent(forceStart = true); _uiState.update { it.copy(isChapterSwitching = false) }; saveProgress() } } }
    fun navigateToChapter(href: String) { 
        pushToHistory()
        val s = _uiState.value
        val chs = s.chapters
        val filePart = href.substringBefore('#').trim()
        val anchorPart = href.substringAfter('#', "").trim().ifEmpty { null }
        
        // Try multiple matching strategies
        var idx = chs.indexOfFirst { it.href == filePart }
        if (idx < 0) idx = chs.indexOfFirst { it.href.endsWith(filePart) }
        if (idx < 0) idx = chs.indexOfFirst { filePart.endsWith(it.href) }
        if (idx < 0) {
            val targetFile = filePart.substringAfterLast('/')
            idx = chs.indexOfFirst { it.href.substringAfterLast('/') == targetFile }
        }
        if (idx < 0) {
            val targetNoExt = filePart.substringAfterLast('/').substringBeforeLast('.')
            idx = chs.indexOfFirst { 
                it.href.substringAfterLast('/').substringBeforeLast('.') == targetNoExt 
            }
        }
        
        if (idx >= 0) {
            // Same chapter + has anchor → just scroll via JS, don't reload
            val sameChapter = (idx == s.currentChapterIndex)
            if (sameChapter && anchorPart != null && s.currentHtml != null) {
                _uiState.update { it.copy(showTocPanel = false, scrollToAnchor = anchorPart) }
                return
            }
            
            killPlayChain()
            val pendingAnchor = if (!sameChapter) anchorPart else null
            _uiState.update { it.copy(currentChapterIndex = idx, isLoading = true, currentHtml = null, showTocPanel = false, ttsParagraphs = emptyList(), ttsCurrentIdx = -1, isTtsPaused = false, currentParagraphIndex = 0, isChapterSwitching = true) }
            viewModelScope.launch { 
                loadChapterContent(forceStart = true)
                // 章节加载完毕，清除切换标记，允许 scroll 监听恢复正常工作
                _uiState.update { it.copy(isChapterSwitching = false) }
                if (pendingAnchor != null) {
                    // Wait for WebView to render the new HTML
                    delay(800)
                    _uiState.update { it.copy(scrollToAnchor = pendingAnchor) }
                }
                saveProgress() 
            }
        }
    }
    private suspend fun saveProgress() { 
        val s = _uiState.value; val b = s.book ?: return; val c = s.chapters.getOrNull(s.currentChapterIndex) ?: return
        val maxIdx = s.ttsParagraphs.size - 1
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
            val sameChapter = (idx == _uiState.value.currentChapterIndex)
            if (sameChapter && anchor.isNotEmpty()) {
                _uiState.update { it.copy(scrollToAnchor = anchor) }
                return
            }
            _uiState.update { it.copy(currentChapterIndex = idx, isLoading = true, currentHtml = null, ttsParagraphs = emptyList(), ttsCurrentIdx = -1, isTtsPaused = false) }
            viewModelScope.launch { 
                loadChapterContent()
                if (anchor.isNotEmpty()) {
                    delay(800)
                    _uiState.update { it.copy(scrollToAnchor = anchor) }
                }
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
    fun addSelectedWordToVocabulary(plan: String = "默认") {
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
                            plan = plan,
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
                            plan = plan,
                            definition = translation.takeIf { it.isNotEmpty() },
                            bookId = book?.id?.toLongOrNull(),
                            chapterIndex = s.currentChapterIndex,
                        )
                    }
                } else {
                    // AI/Cloud translation result — store as-is
                    vocab = Vocabulary(
                        word = word,
                        plan = plan,
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
        // Use the flashcard/vocabulary TTS setting for dictionary pronunciation,
        // so the pronunciation engine is consistent with the word book settings.
        val prefs = getApplication<android.app.Application>()
            .getSharedPreferences("moreader_config", android.content.Context.MODE_PRIVATE)
        val flashcardProviderStr = prefs.getString("flashcard_tts_provider", "edge_tts") ?: "edge_tts"
        val ttsType = try { TTSProviderType.valueOf(flashcardProviderStr) }
            catch (e: IllegalArgumentException) { TTSProviderType.EDGE_TTS }
        val s = _uiState.value

        // If flashcard TTS type matches the reader's current provider, reuse the cached provider
        val p = if (ttsType == s.ttsProvider) {
            recreateProvider()
        } else {
            // Create a one-off provider for dictionary pronunciation
            currentTTSProvider?.let { if (it is com.moyue.app.tts.SystemTTSProvider) null else it }
            ?: createDictProvider(ttsType)
        }
        if (p == null) return

        viewModelScope.launch {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                p.speak(text, s.ttsSpeed, object : TTSListener {
                    override fun onStart() {}
                    override fun onDone() {}
                    override fun onError(msg: String) {}
                })
            }
        }
    }

    /** Create a lightweight one-off TTS provider for dictionary pronunciation */
    private fun createDictProvider(ttsType: TTSProviderType): com.moyue.app.tts.TTSProvider? {
        val s = _uiState.value
        return when (ttsType) {
            TTSProviderType.EDGE_TTS -> com.moyue.app.tts.EdgeTTSProvider(s.edgeTtsEndpoint, s.edgeTtsVoice)
            TTSProviderType.AI_VOICE -> com.moyue.app.tts.AIVoiceTTSProvider(s.aiVoiceEndpoint, s.aiVoiceApiKey, s.aiVoiceModel, s.aiVoiceId)
            TTSProviderType.CUSTOM_TTS -> com.moyue.app.tts.CustomTTSProvider(s.customTtsEndpoint, s.customTtsApiKey, s.customTtsModel, s.customTtsVoice)
            TTSProviderType.SYSTEM -> {
                val ctx = activityContext ?: getApplication()
                com.moyue.app.tts.SystemTTSProvider(ctx)
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
                SystemTTSProvider(ctx).also { provider ->
                    currentTTSProvider = provider
                    // Apply saved voice preference after creation
                    val savedVoice = s.systemTtsVoice
                    if (savedVoice.isNotEmpty()) {
                        // Defer setVoice slightly to let ensureInitialized() finish
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (!provider.isSpeaking) provider.setVoice(savedVoice)
                        }, 100)
                    }
                }
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
                val result = when (val p = getProvider()) {
                    is EdgeTTSProvider -> p.fetchAudio(text, ttsSpeed)
                    is AIVoiceTTSProvider -> p.fetchAudio(text, ttsSpeed)?.let { PreloadResult(it) }
                    is CustomTTSProvider -> p.fetchAudio(text, ttsSpeed)?.let { PreloadResult(it) }
                    else -> null
                }
                if (result != null && result.audio.isNotEmpty()) {
                    audioCache[i] = result.audio
                    if (result.boundaries.isNotEmpty()) boundariesCache[i] = result.boundaries
                }
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
            private var rangesReceived = false
            private var detectJob: kotlinx.coroutines.Job? = null
            private var paraStartMs = 0L

            override fun onStart() {
                paraStartMs = System.currentTimeMillis()
                log("[TIME] ⏱ Para${idx + 1} START @${paraStartMs}ms")
                // 重置每个段落的检测状态
                rangesReceived = false
                detectJob?.cancel(); detectJob = null
                // 1. 段落高亮
                _uiState.update { it.copy(ttsCurrentIdx = highlightIdx, ttsPlayIdx = idx) }
                // 2. 拆分句子
                val rawSentences = text.split(SENTENCE_REGEX).filter { it.isNotBlank() }
                sentenceEnds = IntArray(rawSentences.size)
                if (rawSentences.isNotEmpty()) {
                    var searchPos = 0
                    for (si in rawSentences.indices) {
                        val pos = text.indexOf(rawSentences[si], searchPos)
                        if (pos < 0) { sentenceEnds[si] = searchPos; continue }
                        sentenceEnds[si] = pos + rawSentences[si].length
                        searchPos = sentenceEnds[si]
                    }
                    _uiState.update { it.copy(ttsSentenceCount = sentenceEnds.size, ttsSentenceIdx = 0) }
                    log("[SENT] P${idx + 1} → ${sentenceEnds.size} sentences")
                } else {
                    _uiState.update { it.copy(ttsSentenceCount = 0, ttsSentenceIdx = -1) }
                }
                // 3. 词边界驱动 (Edge TTS — 最精确)
                val wb = pendingBoundaries
                if (wb != null && wb.isNotEmpty() && sentenceEnds.size > 1) {
                    log("[SENT] word-boundary tracking → ${wb.size} words")
                    startWordBoundaryTracking(wb, text, idx)
                }
                pendingBoundaries = null
                // 4. System TTS onRangeStart 会自己驱动
                // 5. 2.5s 后无信号走估算
                if (sentenceEnds.size > 1 && wb == null) {
                    detectJob = viewModelScope.launch {
                        delay(2500L)
                        if (!rangesReceived && playChainActive) {
                            log("[SENT:est] P${idx + 1} — fallback estimation")
                            startEstimation(text, idx)
                        }
                    }
                }
            }
            override fun onWordBoundaries(boundaries: List<com.moyue.app.tts.WordBoundary>) {
                pendingBoundaries = boundaries  // 暂存，onStart 中消费
            }
            override fun onRange(start: Int, end: Int) {
                if (!rangesReceived) {
                    rangesReceived = true
                    detectJob?.cancel()
                    estJob?.cancel()
                    estJob = null
                }
                if (sentenceEnds.isEmpty()) return
                var sentIdx = 0
                while (sentIdx < sentenceEnds.size - 1 && sentenceEnds[sentIdx] <= start) sentIdx++
                if (sentIdx != _uiState.value.ttsSentenceIdx) {
                    _uiState.update { it.copy(ttsSentenceIdx = sentIdx) }
                    log("[SENT] P${idx + 1} #$sentIdx/${sentenceEnds.size} @$start")
                }
            }
            override fun onDone() {
                val now = System.currentTimeMillis()
                val elapsed = now - paraStartMs
                log("[TIME] ⏱ Para${idx + 1} DONE @${now}ms (+${elapsed}ms)")
                estJob?.cancel()
                consecutiveErrors = 0
                // ✅ 不清 ttsSentenceIdx — 下段的 initAndHighlight 会清除旧高亮
                // (Pitfall #37: onDone 清零会导致末句高亮提前消失)
                playOne(idx + 1)
            }
            override fun onError(msg: String) {
                estJob?.cancel()
                _uiState.update { it.copy(ttsSentenceCount = 0, ttsSentenceIdx = -1) }
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
            // 注入预加载的词边界
            val cachedBounds = boundariesCache.remove(idx)
            if (cachedBounds != null && cachedBounds.isNotEmpty()) {
                listener.onWordBoundaries(cachedBounds)
            }
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

            if (p is SystemTTSProvider) {
                p.speak(text, speed, listener)
            } else {
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
        _uiState.update { it.copy(isTtsPlaying = true, isTtsPaused = false, ttsCurrentIdx = -1, ttsPlayIdx = 0) }
        consecutiveErrors = 0
        playChainActive = true

        // Preload first paragraph async, then next 5
        recreateProvider()
        val firstText = paragraphs[0]
        if (firstText.length >= 2) {
            viewModelScope.launch(Dispatchers.IO) {
                val p = getProvider() ?: return@launch
                val spd = _uiState.value.ttsSpeed
                when (p) {
                    is EdgeTTSProvider -> {
                        val result = p.fetchAudio(firstText, spd)
                        if (result != null) {
                            audioCache[0] = result.audio
                            if (result.boundaries.isNotEmpty()) boundariesCache[0] = result.boundaries
                        }
                    }
                    is AIVoiceTTSProvider -> p.fetchAudio(firstText, spd)?.let { audioCache[0] = it }
                    is CustomTTSProvider -> p.fetchAudio(firstText, spd)?.let { audioCache[0] = it }
                }
            }
        }
        preloadRange(1, 6)

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
        
        _uiState.update { it.copy(isTtsPlaying = true, isTtsPaused = false, ttsCurrentIdx = -1, ttsPlayIdx = index) }
        consecutiveErrors = 0
        playChainActive = true

        // Preload first paragraph from index, then next 5
        recreateProvider()
        val firstText = paragraphs[index]
        if (firstText.length >= 2) {
            viewModelScope.launch(Dispatchers.IO) {
                val p = getProvider() ?: return@launch
                val spd = _uiState.value.ttsSpeed
                when (p) {
                    is EdgeTTSProvider -> {
                        val result = p.fetchAudio(firstText, spd)
                        if (result != null) {
                            audioCache[index] = result.audio
                            if (result.boundaries.isNotEmpty()) boundariesCache[index] = result.boundaries
                        }
                    }
                    is AIVoiceTTSProvider -> p.fetchAudio(firstText, spd)?.let { audioCache[index] = it }
                    is CustomTTSProvider -> p.fetchAudio(firstText, spd)?.let { audioCache[index] = it }
                }
            }
        }
        preloadRange(index + 1, index + 6)

        playOne(index)
    }

    fun readSelection(text: String) {
        // 优先从已清洗的 ttsParagraphs 取文本（用户选中了段落中的文字）
        val s = _uiState.value
        val cleanText = s.selectionInfo?.let { info ->
            if (info.startOffset >= 0 && info.text.isNotBlank()) {
                // 有精确选中范围 → 用 JS 返回的选中文本，清除 CJK 空格
                var t = info.text.trim()
                t = t.replace(Regex("""\[\d+]"""), "")
                t = t.replace(Regex("""[①②③④⑤⑥⑦⑧⑨⑩]"""), "")
                t = t.replace(Regex("""[/][*]+/"""), "")
                t = t.replace(Regex("""／[＊]+／"""), "")
                t = t.replace(Regex("""[*＊·•●▶▷◀◁◆◇○◎●◉○□■△▲☆★❀✿❁🌸🌺]"""), "")
                t = t.replace(Regex("""[*]{2,}"""), "")
                t = t.replace(Regex("""[#]{2,}"""), "")
                t = t.replace(Regex("""[_]{2,}"""), "")
                t = t.replace(Regex("""[~]{2,}"""), "")
                t = t.replace(Regex("""`{2,}"""), "")
                // 删除汉字间的空格（Edge TTS 把空格当词边界）
                t = t.replace(Regex("""([\u4e00-\u9fff\u3040-\u309f\u30a0-\u30ff])\s+(?=[\u4e00-\u9fff\u3040-\u309f\u30a0-\u30ff])"""), "$1")
                t.trim().ifBlank { null }
            } else {
                // 无精确偏移 → 取整个段落（保持向后兼容）
                val paras = s.ttsParagraphs
                if (paras.isNotEmpty() && info.paraIdx in paras.indices) {
                    if (info.endParaIdx > info.paraIdx) {
                        // 跨段：从 paraIdx 到 endParaIdx 连起来
                        (info.paraIdx..info.endParaIdx.coerceAtMost(paras.lastIndex))
                            .map { paras.getOrElse(it) { "" } }
                            .filter { it.isNotBlank() }
                            .joinToString("\n")
                    } else {
                        paras[info.paraIdx]
                    }
                } else null
            }
        }?.takeIf { it.isNotBlank() } ?: text  // fallback 到原始选中文本
        killPlayChain()
        _uiState.update { it.copy(isTtsPlaying = true, isTtsPaused = false, ttsCurrentIdx = -1) }
        val p = recreateProvider() ?: run { _uiState.update { it.copy(isTtsPlaying = false) }; return }
        p.speak(cleanText, s.ttsSpeed, object : TTSListener {
            override fun onStart() {}
            override fun onDone() { _uiState.update { it.copy(isTtsPlaying = false, ttsCurrentIdx = -1, ttsPlayIdx = -1) } }
            override fun onError(msg: String) { _uiState.update { it.copy(isTtsPlaying = false, ttsCurrentIdx = -1, ttsPlayIdx = -1) } }
        })
    }

    // ===== Transcribe: selected paragraph → TTS → save MP3 to Downloads =====

    fun transcribeSelection() {
        val s = _uiState.value
        val info = s.selectionInfo ?: return
        val paragraphs = s.ttsParagraphs
        if (paragraphs.isEmpty()) {
            log("转录失败：ttsParagraphs 为空")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. 取清洗后的文本
                val cleanText = if (info.startOffset >= 0 && info.text.isNotBlank()) {
                    // 有精确选中范围 → 用 JS 返回的选中文本，清洗后朗读
                    var t = info.text.trim()
                    t = t.replace(Regex("""\[\d+]"""), "")
                    t = t.replace(Regex("""[①②③④⑤⑥⑦⑧⑨⑩]"""), "")
                    t = t.replace(Regex("""[/][*]+/"""), "")
                    t = t.replace(Regex("""／[＊]+／"""), "")
                    t = t.replace(Regex("""[*＊·•●▶▷◀◁◆◇○◎●◉○□■△▲☆★❀✿❁🌸🌺]"""), "")
                    t = t.replace(Regex("""[*]{2,}"""), "")
                    t = t.replace(Regex("""[#]{2,}"""), "")
                    t = t.replace(Regex("""[_]{2,}"""), "")
                    t = t.replace(Regex("""[~]{2,}"""), "")
                    t = t.replace(Regex("""`{2,}"""), "")
                    t = t.replace(Regex("""([\u4e00-\u9fff\u3040-\u309f\u30a0-\u30ff])\s+(?=[\u4e00-\u9fff\u3040-\u309f\u30a0-\u30ff])"""), "$1")
                    t.trim()
                } else if (info.endParaIdx > info.paraIdx) {
                    // 跨多段：从 paraIdx 到 endParaIdx 取全部
                    (info.paraIdx..info.endParaIdx.coerceAtMost(paragraphs.lastIndex))
                        .map { paragraphs.getOrElse(it) { "" } }
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                } else {
                    // 单段无偏移：用整段清洗文本
                    paragraphs.getOrElse(info.paraIdx) { "" }
                }
                if (cleanText.isBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "清洗后无有效内容", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                log("转录: 段落${info.paraIdx} \"${cleanText.take(80)}\"")

                // 2. Call Edge TTS
                val voice = s.edgeTtsVoice.ifEmpty { "zh-CN-XiaoxiaoNeural" }
                val endpoint = s.edgeTtsEndpoint
                val json = JSONObject().apply {
                    put("text", cleanText)
                    put("voice", voice)
                    put("rate", "+0%")
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${endpoint.trimEnd('/')}/tts")
                    .post(body)
                    .build()
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val response = client.newCall(request).execute()
                val mp3Bytes = response.body?.bytes()
                if (mp3Bytes == null || mp3Bytes.size < 100) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "TTS 生成失败", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // 3. Save to Downloads/MoreaderVoice/
                val prefix = cleanText.take(15).replace(Regex("""[\\/:*?"<>|]"""), "").trim()
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val filename = "转录-$prefix-$date.mp3"
                val saved = saveMp3ToDownloads(filename, mp3Bytes)

                // 4. Notify user
                withContext(Dispatchers.Main) {
                    if (saved) {
                        Toast.makeText(getApplication(),
                            "✅ 已转录 → Downloads/MoreaderVoice/$filename",
                            Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(getApplication(), "转录失败：无法保存文件", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                log("转录失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "转录失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun cleanSelectedText(text: String): String {
        // 用普通字符串（非 raw string），让 \uXXXX 在编译期转为实际汉字
        val cjk = "[\u4e00-\u9fff\u3040-\u309f\u30a0-\u30ff]"
        var cleaned = text
        // 移除注音（括号包裹的拼音）
        cleaned = cleaned.replace(Regex("[（(][a-zA-Zāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜü\\s]+[)）]"), "")
        // 移除汉字间或汉字前后的拼音音节
        cleaned = cleaned.replace(Regex("(?<=${cjk})\\s*[a-zāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜü]{1,6}\\s*(?=${cjk})"), "")
        // 也移除行首/行尾的孤立拼音（选中文本可能每行一条拼音）
        cleaned = cleaned.replace(Regex("\\s*[a-zāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜü]{1,6}\\s*"), " ")
        // 清除脚注标记 [1] [2] ...
        cleaned = cleaned.replace(Regex("\\[\\d+]"), "")
        // 清除装饰符号
        cleaned = cleaned.replace(Regex("[*＊·•●▶▷◀◁◆◇○◎●◉○□■△▲☆★❀✿❁🌸🌺]"), "")
        // 清除汉字间空格（避免 Edge TTS 逐字朗读）
        cleaned = cleaned.replace(Regex("(${cjk})\\s+(?=${cjk})"), "$1")
        // 合并多余空白
        cleaned = cleaned.replace(Regex("\\s+"), " ")
        return cleaned.trim()
    }

    private fun saveMp3ToDownloads(filename: String, data: ByteArray): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/MoreaderVoice")
                }
                val ctx = getApplication<Application>()
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    ctx.contentResolver.openOutputStream(it)?.use { output ->
                        output.write(data)
                    }
                }
                uri != null
            } else {
                @Suppress("DEPRECATION")
                val dir = getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                val moreaderDir = File(dir, "MoreaderVoice")
                moreaderDir.mkdirs()
                File(moreaderDir, filename).writeBytes(data)
                true
            }
        } catch (e: Exception) {
            log("保存 MP3 失败: ${e.message}")
            false
        }
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
    private fun killPlayChain() { playChainActive = false; currentTTSProvider?.stop(); audioCache.clear(); boundariesCache.clear(); estJob?.cancel() }

    // ======== 句子追踪 ========
    /** Edge TTS 词边界驱动 — 每个句子定位第一个词的时间偏移精确调度 */
    private fun startWordBoundaryTracking(boundaries: List<com.moyue.app.tts.WordBoundary>, text: String, paraIdx: Int) {
        estJob?.cancel()
        estJob = viewModelScope.launch {
            var prevMs = 0L
            log("[SENT:wb] START P${paraIdx + 1} - boundaries=${boundaries.size} ends=${sentenceEnds.size} range=[${sentenceEnds.firstOrNull() ?: 0}..${sentenceEnds.lastOrNull() ?: 0}] bRange=[${boundaries.firstOrNull()?.charStart ?: 0}..${boundaries.lastOrNull()?.charEnd ?: 0}]")
            for (sentIdx in 1 until sentenceEnds.size) {
                val sentStart = sentenceEnds[sentIdx - 1]
                val firstWord = boundaries.find { it.charStart >= sentStart }
                if (firstWord == null) {
                    // 降级：找不到词边界就跳过（不设高亮）
                    log("[SENT:wb] ⛔️ no word at sentStart=$sentStart - skip")
                    continue
                }
                val targetMs = firstWord.offsetMs.toLong()
                val waitMs = (targetMs - prevMs).coerceAtLeast(50)
                delay(waitMs)
                if (!playChainActive) break
                _uiState.update { it.copy(ttsSentenceIdx = sentIdx) }
                log("[SENT:wb] P${paraIdx + 1} #$sentIdx/${sentenceEnds.size} @${targetMs}ms")
                prevMs = targetMs
            }
        }
    }

    /** 估算降级 — 按字符比例估算句子时间 (用于无 onRangeStart 的引擎如 Oppo/AOSP/Custom/AI Voice) */
    private fun startEstimation(text: String, paraIdx: Int) {
        val spd = _uiState.value.ttsSpeed
        val hasChinese = text.any { it in '\u4e00'..'\u9fff' }
        val charsPerSec = if (hasChinese) (6f * spd).coerceAtLeast(4f)   // 中文 ~6 字/秒
                          else (15f * spd).coerceAtLeast(8f)              // 英文 ~15 字/秒
        estJob?.cancel()
        estJob = viewModelScope.launch {
            for (sentIdx in 1 until sentenceEnds.size) {
                val sentChars = sentenceEnds[sentIdx] - sentenceEnds[sentIdx - 1]
                val waitMs = (sentChars.toFloat() / charsPerSec * 1000f).toLong().coerceAtLeast(80L)
                delay(waitMs)
                if (!playChainActive) break
                _uiState.update { it.copy(ttsSentenceIdx = sentIdx) }
                log("[SENT:est] P${paraIdx + 1} #$sentIdx/${sentenceEnds.size} ($sentChars chars, ${waitMs}ms)")
            }
        }
    }
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
    fun setSystemTTSVoice(voiceName: String) {
        _uiState.update { it.copy(systemTtsVoice = voiceName) }
        prefs.edit().putString("system_tts_voice", voiceName).apply()
        // Recreate TTS provider to apply voice
        currentTTSProvider?.destroy()
        currentTTSProvider = null
        killPlayChain()
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

        // ── 跨平台书签：来自浏览器同步，chapterIndex==0, paragraphIndex==0，
        //     但有 paragraphText。通过在 EPUB 中搜索文字找到真实位置 ──
        if (bookmark.chapterIndex == 0 && bookmark.paragraphIndex == 0
            && !bookmark.paragraphText.isNullOrBlank()) {
            viewModelScope.launch {
                val found = findTextPosition(bookmark.paragraphText!!)
                if (found != null) {
                    val (chIdx, paraIdx) = found
                    val updated = bookmark.copy(chapterIndex = chIdx, paragraphIndex = paraIdx)
                    repository.updateBookmark(updated)
                    doNavigateToBookmarkPosition(chIdx, paraIdx)
                }
            }
            return
        }

        doNavigateToBookmarkPosition(bookmark.chapterIndex, bookmark.paragraphIndex)
    }

    /** Navigate to a bookmark position by chapter/paragraph index (from bookmarks list) */
    fun navigateToBookmarkPosition(chapterIndex: Int, paragraphIndex: Int) {
        pushToHistory()
        doNavigateToBookmarkPosition(chapterIndex, paragraphIndex)
    }

    private fun doNavigateToBookmarkPosition(chapterIndex: Int, paragraphIndex: Int) {
        val s = _uiState.value
        val chapters = s.chapters

        if (chapterIndex != s.currentChapterIndex) {
            val targetChapter = chapters.getOrNull(chapterIndex)
            if (targetChapter != null) {
                killPlayChain()
                _uiState.update {
                    it.copy(
                        currentChapterIndex = chapterIndex,
                        isLoading = true,
                        currentHtml = null,
                        ttsParagraphs = emptyList(),
                        ttsCurrentIdx = -1,
                        isTtsPaused = false,
                        scrollToParagraph = -1,
                        scrollToAnchor = null,
                        isChapterSwitching = true,
                    )
                }
                viewModelScope.launch {
                    loadChapterContent()
                    _uiState.update { it.copy(isChapterSwitching = false) }
                    kotlinx.coroutines.delay(200)
                    _uiState.update { it.copy(scrollToParagraph = paragraphIndex) }
                }
            }
        } else {
            _uiState.update { it.copy(scrollToParagraph = -1, scrollToAnchor = null) }
            viewModelScope.launch {
                kotlinx.coroutines.delay(100)
                _uiState.update { it.copy(scrollToParagraph = paragraphIndex) }
            }
        }
    }

    /**
     * 在整本书中搜索文字，返回 (chapterIndex, paragraphIndex)。
     * 先搜当前章节（已有 ttsParagraphs），再遍历所有章节。
     * 支持多行文字（换行符分隔）：逐行匹配，找到任一行即定位成功。
     */
    private suspend fun findTextPosition(text: String): Pair<Int, Int>? {
        val s = _uiState.value
        val book = s.book ?: return null
        val chapters = s.chapters
        if (chapters.isEmpty()) return null

        // 拆分多行文字（浏览器选中跨段落时会产生 \n）
        val lines = text.split("\n").map { it.trim() }.filter { it.length >= 2 }

        // 搜索函数：在段落列表中查找匹配
        fun searchIn(paras: List<String>, chIdx: Int): Pair<Int, Int>? {
            // 优先精确匹配全文（去除换行后的单行文本）
            for (line in lines) {
                val match = paras.indexOfFirst { it.contains(line) }
                if (match >= 0) return Pair(chIdx, match)
            }
            // 回退：用原文模糊匹配（忽略首尾空白）
            val trimmed = text.trim()
            val match = paras.indexOfFirst { it.contains(trimmed) }
            if (match >= 0) return Pair(chIdx, match)
            return null
        }

        // 1) 先搜当前章节的段落列表（已加载，速度最快）
        searchIn(s.ttsParagraphs, s.currentChapterIndex)?.let { return it }

        // 2) 遍历所有章节，加载内容搜索
        for (i in chapters.indices) {
            if (i == s.currentChapterIndex) continue  // 已搜过
            val ch = chapters[i]
            val html = repository.getChapterContent(book.id, ch.href) ?: continue
            val paras = extractParagraphsFromHtml(html)
            searchIn(paras, i)?.let { return it }
        }
        return null
    }

    /** 跳转到指定章节和段落的公共方法 */
    private fun navigateToChapterAndParagraph(chIdx: Int, paraIdx: Int) {
        val s = _uiState.value
        val chapters = s.chapters
        val targetChapter = chapters.getOrNull(chIdx) ?: return
        killPlayChain()
        _uiState.update {
            it.copy(
                currentChapterIndex = chIdx,
                isLoading = true,
                currentHtml = null,
                ttsParagraphs = emptyList(),
                ttsCurrentIdx = -1,
                isTtsPaused = false,
                scrollToParagraph = -1,
                scrollToAnchor = null,
                isChapterSwitching = true,
            )
        }
        viewModelScope.launch {
            loadChapterContent()
            _uiState.update { it.copy(isChapterSwitching = false) }
            kotlinx.coroutines.delay(200)
            _uiState.update { it.copy(scrollToParagraph = paraIdx) }
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
        pushToHistory()
        _uiState.update { it.copy(showHighlightPanel = false) }
        val s = _uiState.value
        val chapters = s.chapters

        // ── 跨平台高亮：来自浏览器同步，chapterIndex==0, startParagraph==0，
        //     但有 text。通过在 EPUB 中搜索文字找到真实位置 ──
        if (highlight.chapterIndex == 0 && highlight.startParagraph == 0
            && highlight.text.isNotBlank()) {

            viewModelScope.launch {
                val found = findTextPosition(highlight.text)
                if (found != null) {
                    val (chIdx, paraIdx) = found
                    // 更新高亮的章节/段落索引到数据库
                    val updated = highlight.copy(
                        chapterIndex = chIdx,
                        startParagraph = paraIdx,
                        endParagraph = paraIdx
                    )
                    repository.updateHighlight(updated)

                    // 用找到的真实位置跳转
                    navigateToChapterAndParagraph(chIdx, paraIdx)
                } else {
                    Log.w("ReaderVM", "跨平台高亮：未找到匹配文字 '${highlight.text.take(60)}'")
                }
            }
            return
        }

        // ── 正常高亮跳转 ──
        if (highlight.chapterIndex != s.currentChapterIndex) {
            val targetChapter = chapters.getOrNull(highlight.chapterIndex)
            if (targetChapter != null) {
                killPlayChain()
                _uiState.update { 
                    it.copy(
                        currentChapterIndex = highlight.chapterIndex, 
                        isLoading = true, 
                        currentHtml = null, 
                        ttsParagraphs = emptyList(), 
                        ttsCurrentIdx = -1, 
                        isTtsPaused = false,
                        isChapterSwitching = true,
                    ) 
                }
                viewModelScope.launch {
                    loadChapterContent()
                    _uiState.update { it.copy(isChapterSwitching = false) }
                    _uiState.update { it.copy(scrollToParagraph = highlight.startParagraph) }
                }
            }
        } else {
            _uiState.update { it.copy(scrollToParagraph = -1, scrollToAnchor = null) }
            viewModelScope.launch {
                kotlinx.coroutines.delay(100)
                _uiState.update { it.copy(scrollToParagraph = highlight.startParagraph) }
            }
        }
    }

    fun setCurrentParagraph(idx: Int) { 
        if (_uiState.value.currentParagraphIndex == idx) return  // No-op if same
        _uiState.update { it.copy(currentParagraphIndex = idx) }
        // 章节切换过渡期不写 DB，避免 WebView 恢复旧滚动位置污染进度
        if (_uiState.value.isChapterSwitching) return
        // Persist paragraph position (debounced - only if changed significantly)
        val book = _uiState.value.book ?: return
        viewModelScope.launch {
            repository.updateBookParagraph(book.id, idx)
        }
    }

    fun clearScrollToParagraph() { _uiState.update { it.copy(scrollToParagraph = -1, scrollToAnchor = null) } }

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
            val newId = repository.addHighlight(highlight)
            // Add to in-memory list with correct DB-assigned ID
            _highlights.update { it + highlight.copy(id = newId) }
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

    /**
     * 首次打开书本时调用：扫描所有跨平台高亮（offset=0），
     * 遍历全部章节找到文字位置，更新 chapterIndex + 偏移量到数据库。
     * 只执行一次（修复后 offset≠0 不会被重复处理）。
     */
    private fun repairAllCrossPlatformHighlights(bookId: String) {
        viewModelScope.launch {
            val all = repository.getHighlightsForBook(bookId).first()
            val needsRepair = all.filter {
                it.startOffset == 0 && it.endOffset == 0 && it.text.isNotBlank()
            }
            if (needsRepair.isEmpty()) return@launch

            val s = _uiState.value
            val chapters = s.chapters
            if (chapters.isEmpty()) return@launch

            var repaired = 0
            for (hl in needsRepair) {
                // 在全部章节中搜索
                val found = findTextPosition(hl.text)
                if (found != null) {
                    val (chIdx, paraIdx) = found
                    // 在段落内精确定位偏移量
                    val ch = chapters[chIdx]
                    val html = repository.getChapterContent(bookId, ch.href) ?: continue
                    val rawParas = getRawParagraphTexts(html)
                    val offsetMatch = findTextInParagraphs(rawParas, hl.text)
                    val (startOff, endOff) = if (offsetMatch != null) {
                        Pair(offsetMatch.second, offsetMatch.third)
                    } else {
                        Pair(0, hl.text.length)
                    }
                    val updated = hl.copy(
                        chapterIndex = chIdx,
                        startParagraph = paraIdx, startOffset = startOff,
                        endParagraph = paraIdx, endOffset = endOff
                    )
                    repository.updateHighlight(updated)
                    repaired++
                    Log.d("ReaderVM", "全局修复高亮: '${hl.text.take(30)}' → ch$chIdx p$paraIdx o$startOff-$endOff")
                } else {
                    Log.w("ReaderVM", "全局修复失败: '${hl.text.take(50)}'")
                }
            }
            if (repaired > 0) {
                // 重新加载当前章节的高亮以反映修复
                val currentHighlights = repository.getHighlightsForBook(bookId).first()
                    .filter { it.chapterIndex == s.currentChapterIndex }
                _highlights.value = currentHighlights
            }
        }
    }

    fun loadHighlightsForChapter() {
        val s = _uiState.value
        val book = s.book ?: return
        viewModelScope.launch {
            val all = repository.getHighlightsForBook(book.id).first()
            val chapterHighlights = all.filter { it.chapterIndex == s.currentChapterIndex }
            
            // ── 修复跨平台高亮偏移量（浏览器同步的高亮 offset=0）──
            val repaired = repairHighlightOffsets(chapterHighlights)
            _highlights.value = repaired
        }
    }

    /**
     * 检测偏移量为 0 的高亮（来自浏览器同步），在原始 HTML 中搜索文字，
     * 计算准确的 startOffset/endOffset，更新数据库后返回修正后的列表。
     */
    private suspend fun repairHighlightOffsets(highlights: List<Highlight>): List<Highlight> {
        val needsRepair = highlights.filter {
            it.startOffset == 0 && it.endOffset == 0 && it.text.isNotBlank()
        }
        if (needsRepair.isEmpty()) return highlights

        val s = _uiState.value
        val html = if (s.currentHtml != null) {
            s.currentHtml!!
        } else {
            val book = s.book ?: return highlights
            val ch = s.chapters.getOrNull(s.currentChapterIndex) ?: return highlights
            repository.getChapterContent(book.id, ch.href) ?: return highlights
        }

        // 用 Jsoup 提取原始段落文本（含拼音/脚注，与 WebView DOM textContent 一致）
        val rawParas = getRawParagraphTexts(html)
        if (rawParas.isEmpty()) return highlights

        val result = highlights.toMutableList()
        for (hl in needsRepair) {
            val match = findTextInParagraphs(rawParas, hl.text)
            if (match != null) {
                val (paraIdx, startOff, endOff) = match
                val updated = hl.copy(startParagraph = paraIdx, startOffset = startOff,
                    endParagraph = paraIdx, endOffset = endOff)
                repository.updateHighlight(updated)
                val idx = result.indexOfFirst { it.id == hl.id }
                if (idx >= 0) result[idx] = updated
                Log.d("ReaderVM", "修补高亮偏移: '${hl.text.take(30)}' → p=$paraIdx o=$startOff-$endOff")
            } else {
                Log.w("ReaderVM", "未找到高亮文字: '${hl.text.take(50)}'")
            }
        }
        return result
    }

    /** 从 HTML 提取段落原始文本（与 WebView DOM textContent 一致） */
    private fun getRawParagraphTexts(html: String): List<String> {
        return try {
            val doc = org.jsoup.Jsoup.parse(html)
            doc.select("p, h1, h2, h3, h4, h5, h6").map { el -> el.text() }
        } catch (e: Exception) {
            Log.e("ReaderVM", "getRawParagraphTexts error: ${e.message}")
            emptyList()
        }
    }

    /**
     * 在段落文本列表中搜索目标文字，返回 (paraIdx, startOffset, endOffset)。
     * 偏移量基于原始 textContent（含拼音/脚注），与 WebView DOM 一致。
     */
    private fun findTextInParagraphs(
        paras: List<String>, target: String
    ): Triple<Int, Int, Int>? {
        val search = target.trim()
        if (search.length < 2) return null

        for (i in paras.indices) {
            val idx = paras[i].indexOf(search)
            if (idx >= 0) {
                return Triple(i, idx, idx + search.length)
            }
        }
        // 回退：多行拆分逐行尝试
        val lines = search.split("\n").map { it.trim() }.filter { it.length >= 2 }
        for (i in paras.indices) {
            for (line in lines) {
                val idx = paras[i].indexOf(line)
                if (idx >= 0) {
                    return Triple(i, idx, idx + line.length)
                }
            }
        }
        return null
    }

    // ===== Vocabulary =====
    /** Show plan picker before adding word from selection toolbar */
    fun showVocabPlanPicker() {
        val s = _uiState.value
        val text = s.selectedText?.trim() ?: return
        if (text.isEmpty()) return
        val vocabPrefs = getApplication<Application>().getSharedPreferences("moreader_vocab", Context.MODE_PRIVATE)
        val plans = (vocabPrefs.getStringSet("vocab_notebook_plans", setOf("默认")) ?: setOf("默认")).toList().sorted()
        _uiState.update { it.copy(showVocabPlanPicker = true, vocabPlanOptions = plans) }
    }

    fun dismissVocabPlanPicker() {
        _uiState.update { it.copy(showVocabPlanPicker = false) }
    }

    /** Add word to vocabulary with chosen plan (from selection toolbar) */
    fun addVocabulary(plan: String) {
        val s = _uiState.value
        val book = s.book ?: return
        val text = s.selectedText?.trim() ?: return
        if (text.isEmpty()) return
        dismissVocabPlanPicker()

        viewModelScope.launch {
            val existing = repository.getVocabularyByWord(text)
            if (existing != null) {
                _uiState.update { it.copy(showBookmarkToast = true, bookmarkToastMsg = getApplication<Application>().getString(com.moyue.app.R.string.vocabulary_already_exists)) }
            } else {
                val translation = s.translationResult ?: ""
                val isChinese = text.any { it in '\u4e00'..'\u9fff' }
                val vocab = Vocabulary(
                    word = text,
                    plan = plan,
                    chineseDef = if (isChinese && translation.isNotEmpty()) translation else null,
                    englishDef = if (!isChinese && translation.isNotEmpty()) translation else null,
                    definition = translation.takeIf { it.isNotEmpty() },
                    bookId = book.id.toLongOrNull(),
                    chapterIndex = s.currentChapterIndex,
                    createdAt = System.currentTimeMillis()
                )
                repository.insertVocabulary(vocab)
                _uiState.update { it.copy(showBookmarkToast = true, bookmarkToastMsg = getApplication<Application>().getString(com.moyue.app.R.string.vocabulary_added) + " → " + plan) }
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
                        is EdgeTTSProvider -> provider.fetchAudio(text, speed)?.audio
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

    override fun onCleared() {
        super.onCleared()
        killPlayChain(); currentTTSProvider?.destroy(); recordingJob?.cancel()
        // 保存阅读进度（章节、段落位置等），防止退出后位置丢失
        val s = _uiState.value; val b = s.book; val c = s.chapters.getOrNull(s.currentChapterIndex)
        if (b != null && c != null) {
            val maxIdx = s.ttsParagraphs.size - 1
            val paraIdx = if (maxIdx < 0) 0 else s.currentParagraphIndex.coerceIn(0, maxIdx)
            kotlinx.coroutines.runBlocking {
                repository.updateProgress(b.id, c.href, s.currentChapterIndex, b.currentProgress, null, paraIdx, s.theme.id, s.fontSize)
            }
        }
    }
}
