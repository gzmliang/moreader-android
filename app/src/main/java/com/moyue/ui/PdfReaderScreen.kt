package com.moyue.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moyue.app.R
import com.moyue.app.data.BookRepository
import com.moyue.app.data.models.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class PdfDocumentHolder(val file: File) {
    private var pfd: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
        private set
    val mutex = Mutex()
    var pageCount by mutableIntStateOf(0)
        private set
    private val pageDimensions = mutableMapOf<Int, Pair<Int, Int>>()
    private val bitmapCache = LruCache<Int, Bitmap>(10)

    fun open(): Boolean {
        return try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd!!)
            pageCount = renderer!!.pageCount
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun getPageDimensions(index: Int): Pair<Int, Int> = withContext(Dispatchers.IO) {
        pageDimensions[index]?.let { return@withContext it }
        val r = renderer ?: return@withContext Pair(595, 842)
        mutex.withLock {
            try {
                val page = r.openPage(index)
                val w = page.width
                val h = page.height
                page.close()
                val dim = Pair(w, h)
                pageDimensions[index] = dim
                dim
            } catch (e: Exception) {
                Pair(595, 842)
            }
        }
    }

    suspend fun renderPage(index: Int, targetWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        val cached = bitmapCache.get(index)
        if (cached != null && !cached.isRecycled) {
            return@withContext cached
        }
        val r = renderer ?: return@withContext null
        mutex.withLock {
            try {
                val page = r.openPage(index)
                val w = targetWidth.coerceIn(400, 2400)
                val h = ((w.toFloat() * page.height) / page.width).toInt()
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                bitmapCache.put(index, bitmap)
                bitmap
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    fun close() {
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        bitmapCache.evictAll()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    bookId: String,
    repository: BookRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val aiCacheRepo = remember { com.moyue.ai.data.AiCacheRepository(context) }
    val llmClient = remember { com.moyue.ai.service.LlmClient() }

    var book by remember { mutableStateOf<Book?>(null) }
    var holder by remember { mutableStateOf<PdfDocumentHolder?>(null) }
    var isReady by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var showControls by remember { mutableStateOf(true) }

    // PDF「点哪读哪」：后台提取文字层 → 命中点击位置所在句子 → 弹翻译/朗读卡片
    var lookupHit by remember { mutableStateOf<com.moyue.reader.PdfTextLayer.Hit?>(null) }
    var lookupLoading by remember { mutableStateOf(false) }
    // 整页 / 连续朗读
    var ttsReading by remember { mutableStateOf(false) }
    var ttsHighlight by remember { mutableStateOf<com.moyue.reader.PdfTextLayer.Selection?>(null) }
    var ttsProvider by remember { mutableStateOf<com.moyue.app.tts.EdgeTTSProvider?>(null) }
    var ttsJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            runCatching { ttsProvider?.stop() }
            runCatching { ttsProvider?.destroy() }
            ttsJob?.cancel()
        }
    }

    // PDF 划线选区：长按选词 → 拖动扩选 → 松手弹卡片
    var pdfSelection by remember { mutableStateOf<com.moyue.reader.PdfTextLayer.Selection?>(null) }
    var selectionAnchor by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // AI 测验状态
    var showQuizScopeDialog by remember { mutableStateOf(false) }
    var showAiQuizScreen by remember { mutableStateOf(false) }
    var quizScopeStart by remember { mutableIntStateOf(1) }
    var quizScopeEnd by remember { mutableIntStateOf(1) }
    var quizQuestionCount by remember { mutableIntStateOf(5) }
    var quizDifficulty by remember { mutableStateOf("Intermediate") } // "Basic", "Intermediate", "Advanced"
    var quizCustomPrompt by remember { mutableStateOf("") }
    var isQuizGenerating by remember { mutableStateOf(false) }
    var currentGeneratedQuiz by remember { mutableStateOf<com.moyue.ai.model.AiQuizResult?>(null) }

    // 双指缩放状态
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 3.5f)
        scale = newScale
        if (newScale > 1f) {
            offset += offsetChange
        } else {
            offset = Offset.Zero
        }
    }

    // 加载书籍和初始化 PDF
    LaunchedEffect(bookId) {
        try {
            val b = withContext(Dispatchers.IO) { repository.getBook(bookId) }
            if (b == null) {
                android.util.Log.e("PdfReader", "Book not found in DB: $bookId")
                loadError = context.getString(R.string.pdf_open_failed)
                return@LaunchedEffect
            }
            book = b
            android.util.Log.d("PdfReader", "Book found: ${b.title}, filePath=${b.filePath}")
            val file = File(b.filePath)
            if (!file.exists()) {
                android.util.Log.e("PdfReader", "File not found: ${b.filePath}")
                loadError = "文件不存在，请重新导入\n(${b.filePath})" 
                return@LaunchedEffect
            }
            if (!file.canRead()) {
                android.util.Log.e("PdfReader", "File not readable: ${b.filePath}")
                loadError = "文件无法读取，请检查权限"
                return@LaunchedEffect
            }
            android.util.Log.d("PdfReader", "Opening PDF: size=${file.length()}bytes")
            val docHolder = PdfDocumentHolder(file)
            val opened = withContext(Dispatchers.IO) { docHolder.open() }
            if (opened && docHolder.pageCount > 0) {
                android.util.Log.d("PdfReader", "PDF opened OK, pages=${docHolder.pageCount}")
                holder = docHolder
                isReady = true
            } else {
                android.util.Log.e("PdfReader", "PdfRenderer.open() failed or 0 pages")
                loadError = context.getString(R.string.pdf_open_failed)
            }
        } catch (e: Exception) {
            android.util.Log.e("PdfReader", "Fatal error loading PDF", e)
            loadError = "打开失败：${e.javaClass.simpleName}: ${e.message}"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            holder?.close()
        }
    }

    if (loadError != null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = loadError ?: "",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
        return
    }

    if (!isReady || holder == null || book == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val currentHolder = holder!!
    val currentBook = book!!
    val pageCount = currentHolder.pageCount
    val initialPage = currentBook.currentChapterIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    // 单页渲染：不再用 LazyColumn（它与 graphicsLayer 缩放层组合会导致
    // scrollToItem / 翻页 / 朗读自动跟随全部失效），改为「当前页 index」驱动。
    var currentPage by remember { mutableIntStateOf(initialPage) }

    // 保存进度函数
    fun saveProgress() {
        val page = currentPage
        val progress = if (pageCount > 0) (page + 1).toFloat() / pageCount else 0f
        coroutineScope.launch {
            repository.updatePdfProgress(bookId, page, progress)
        }
    }

    // 翻页后停顿防抖自动更新进度
    LaunchedEffect(currentPage) {
        delay(600)
        saveProgress()
    }

    BackHandler {
        if (showAiQuizScreen) {
            showAiQuizScreen = false
        } else {
            saveProgress()
            onBack()
        }
    }

    // AI 出题范围与要求选择对话框
    if (showQuizScopeDialog) {
        val totalPagesSelected = (quizScopeEnd - quizScopeStart + 1).coerceAtLeast(1)
        AlertDialog(
            onDismissRequest = { if (!isQuizGenerating) showQuizScopeDialog = false },
            title = { Text(stringResource(R.string.pdf_ai_quiz_dialog_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = stringResource(R.string.pdf_ai_quiz_scope_hint),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))

                    // 起始页
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.pdf_ai_quiz_start_page), fontSize = 14.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { quizScopeStart = (quizScopeStart - 1).coerceAtLeast(1) },
                                enabled = quizScopeStart > 1 && !isQuizGenerating
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = null)
                            }
                            Text(
                                text = "$quizScopeStart",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            IconButton(
                                onClick = {
                                    val next = (quizScopeStart + 1).coerceAtMost(pageCount)
                                    quizScopeStart = next
                                    if (quizScopeEnd < next) quizScopeEnd = next
                                },
                                enabled = quizScopeStart < pageCount && !isQuizGenerating
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                            }
                        }
                    }

                    // 结束页
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.pdf_ai_quiz_end_page), fontSize = 14.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    val prev = (quizScopeEnd - 1).coerceAtLeast(quizScopeStart)
                                    quizScopeEnd = prev
                                },
                                enabled = quizScopeEnd > quizScopeStart && !isQuizGenerating
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = null)
                            }
                            Text(
                                text = "$quizScopeEnd",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            IconButton(
                                onClick = { quizScopeEnd = (quizScopeEnd + 1).coerceAtMost(pageCount) },
                                enabled = quizScopeEnd < pageCount && !isQuizGenerating
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                            }
                        }
                    }

                    Text(
                        text = stringResource(R.string.pdf_ai_quiz_total_pages, totalPagesSelected),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    // 题量选择 (3 / 5 / 10)
                    Text(
                        text = stringResource(R.string.pdf_ai_quiz_count_label),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(3, 5, 10).forEach { cnt ->
                            FilterChip(
                                selected = (quizQuestionCount == cnt),
                                onClick = { quizQuestionCount = cnt },
                                label = { Text(stringResource(R.string.pdf_ai_quiz_count_format, cnt), fontSize = 12.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // 难度选择 (Basic / Intermediate / Advanced)
                    Text(
                        text = stringResource(R.string.pdf_ai_quiz_diff_label),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "Basic" to stringResource(R.string.pdf_ai_quiz_diff_easy),
                            "Intermediate" to stringResource(R.string.pdf_ai_quiz_diff_medium),
                            "Advanced" to stringResource(R.string.pdf_ai_quiz_diff_hard),
                        ).forEach { (diffKey, diffLabel) ->
                            FilterChip(
                                selected = (quizDifficulty == diffKey),
                                onClick = { quizDifficulty = diffKey },
                                label = { Text(diffLabel, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // 用户提示词输入框（自然语言教学意向）
                    Text(
                        text = stringResource(R.string.pdf_ai_quiz_custom_prompt_label),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = quizCustomPrompt,
                        onValueChange = { quizCustomPrompt = it },
                        placeholder = {
                            Text(
                                stringResource(R.string.pdf_ai_quiz_custom_prompt_hint),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (isQuizGenerating) {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.pdf_ai_quiz_generating),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val aiConfig = aiCacheRepo.getAiConfig()
                        if (!aiConfig.isConfigured) {
                            Toast.makeText(context, context.getString(R.string.pdf_ai_quiz_need_config), Toast.LENGTH_LONG).show()
                            return@TextButton
                        }
                        isQuizGenerating = true
                        coroutineScope.launch {
                            try {
                                val doc = holder ?: return@launch
                                val bmps = mutableListOf<Bitmap>()
                                for (p in (quizScopeStart - 1)..(quizScopeEnd - 1)) {
                                    doc.renderPage(p, 1200)?.let { bmps.add(it) }
                                }

                                val (sysPrompt, userPrompt) = com.moyue.ai.service.AiPromptBuilder.buildPdfVisionQuizPrompt(
                                    config = aiConfig,
                                    bookTitle = currentBook.title,
                                    startPage = quizScopeStart,
                                    endPage = quizScopeEnd,
                                    count = quizQuestionCount,
                                    difficulty = quizDifficulty,
                                    customPrompt = quizCustomPrompt
                                )

                                val result = llmClient.chatCompletion(
                                    config = aiConfig,
                                    systemPrompt = sysPrompt,
                                    userPrompt = userPrompt,
                                    temperature = 0.3,
                                    responseJson = true,
                                    imageBitmaps = bmps
                                )

                                result.onSuccess { jsonStr ->
                                    val quiz = com.moyue.ai.service.AiPromptBuilder.parseQuizResponse(
                                        jsonString = jsonStr,
                                        bookId = bookId,
                                        chapterIndex = quizScopeStart,
                                        scope = "chapter",
                                        count = quizQuestionCount,
                                        difficulty = quizDifficulty
                                    )
                                    if (quiz.questions.isNotEmpty()) {
                                        aiCacheRepo.saveQuiz(quiz)
                                        currentGeneratedQuiz = quiz
                                        showQuizScopeDialog = false
                                        showAiQuizScreen = true
                                    } else {
                                        Toast.makeText(context, "出题解析失败，请稍后重试", Toast.LENGTH_LONG).show()
                                    }
                                }.onFailure { e ->
                                    Toast.makeText(context, "出题失败：${e.message}", Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "处理异常：${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                isQuizGenerating = false
                            }
                        }
                    },
                    enabled = !isQuizGenerating
                ) {
                    Text(stringResource(R.string.pdf_ai_quiz_start_btn))
                }
            },
            dismissButton = {
                if (!isQuizGenerating) {
                    TextButton(onClick = { showQuizScopeDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        )
    }

    // AI 互动做题界面
    if (showAiQuizScreen) {
        com.moyue.ai.ui.AiReadingScreen(
            bookId = bookId,
            bookTitle = currentBook.title,
            chapterIndex = quizScopeStart,
            chapterTitle = "第 $quizScopeStart ~ $quizScopeEnd 页 (故事篇)",
            chapterText = "PDF 故事内容（第 $quizScopeStart 到第 $quizScopeEnd 页）",
            bookDao = repository.dao,
            edgeTTS = null,
            bookRepository = repository,
            initialTab = 2,
            initialQuizScope = "chapter",
            initialQuizCount = quizQuestionCount,
            initialQuizDifficulty = quizDifficulty,
            isPdfMode = true,
            onRecreateQuiz = {
                // 关闭当前答题卡，重新弹出选页及意向定制弹窗
                showAiQuizScreen = false
                showQuizScopeDialog = true
            },
            onDismiss = { showAiQuizScreen = false }
        )
    }

    val currentPageDisplay = (currentPage + 1).coerceAtMost(pageCount)

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            AnimatedVisibility(
                visible = showControls,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
            ) {
                TopAppBar(
                    title = {
                        Text(
                            text = currentBook.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            saveProgress()
                            onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                if (ttsReading) {
                                    ttsReading = false
                                    ttsJob?.cancel()
                                    runCatching { ttsProvider?.stop() }
                                    runCatching { ttsProvider?.destroy() }
                                    ttsProvider = null
                                    ttsHighlight = null
                                    return@IconButton
                                }
                                ttsReading = true
                                ttsJob = coroutineScope.launch {
                                    val prefs = context.getSharedPreferences("moreader_config", android.content.Context.MODE_PRIVATE)
                                    val baseVoice = prefs.getString("edge_voice", "zh-CN-XiaoxiaoNeural")
                                        ?: "zh-CN-XiaoxiaoNeural"
                                    val endpoint = com.moyue.util.SecureUrlHelper.secureEdgeEndpoint(
                                        prefs.getString("edge_endpoint", null)
                                    )
                                    var page = currentPage
                                    android.util.Log.d("PdfTtsPage", "reading start page=$page count=$pageCount")
                                    while (ttsReading && page < pageCount) {
                                        val pdfFile = holder?.file ?: break
                                        val pageText = withContext(Dispatchers.IO) {
                                            com.moyue.reader.PdfTextLayer.extract(pdfFile, page, context)
                                        }
                                        if (pageText == null || pageText.isEmpty) {
                                            page++
                                            continue
                                        }
                                        val sents = com.moyue.reader.PdfTextLayer.sentences(pageText)
                                        android.util.Log.d("PdfTtsPage", "page=$page sents=${sents.size}")
                                        if (sents.isEmpty()) {
                                            page++
                                            continue
                                        }
                                        // 朗读跨页：直接切当前页（单页渲染，天然同步）
                                        currentPage = page
                                        for (sent in sents) {
                                            if (!ttsReading) break
                                            ttsHighlight = com.moyue.reader.PdfTextLayer
                                                .resolveRange(pageText, sent.start, sent.end)
                                                ?.copy(pageIndex = page)
                                            val voice = com.moyue.tts.LanguageVoiceDetector
                                                .pickEdgeVoiceForText(sent.text, baseVoice)
                                            val prov = ttsProvider?.takeIf { it.voice == voice } ?: run {
                                                runCatching { ttsProvider?.stop() }
                                                runCatching { ttsProvider?.destroy() }
                                                com.moyue.app.tts.EdgeTTSProvider(endpoint, voice)
                                                    .also { ttsProvider = it }
                                            }
                                            suspendCancellableCoroutine<Unit> { cont ->
                                                prov.speak(sent.text, 1.0f, object : com.moyue.app.tts.TTSListener {
                                                    override fun onStart() {}
                                                    override fun onDone() {
                                                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                                                    }
                                                    override fun onError(message: String) {
                                                        android.util.Log.w(
                                                            "PdfTtsPage",
                                                            "speak failed: $message | voice=$voice | text=${sent.text.take(30)}"
                                                        )
                                                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                                                    }
                                                })
                                            }
                                        }
                                        page++
                                    }
                                    ttsReading = false
                                    ttsHighlight = null
                                }
                            }
                        ) {
                            Icon(
                                if (ttsReading) Icons.Default.StopCircle else Icons.Default.VolumeUp,
                                contentDescription = stringResource(com.moyue.app.R.string.pdf_read_page),
                                tint = if (ttsReading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        TextButton(onClick = {
                            // 优先判断：如果已经有题目缓存，直接秒开做题界面
                            val cachedQuiz = currentGeneratedQuiz ?: aiCacheRepo.getQuiz(bookId, quizScopeStart, "chapter", quizQuestionCount, quizDifficulty)
                            if (cachedQuiz != null && cachedQuiz.questions.isNotEmpty()) {
                                showAiQuizScreen = true
                            } else {
                                val cur = (currentPage + 1).coerceIn(1, pageCount)
                                quizScopeStart = cur
                                quizScopeEnd = (cur + 1).coerceAtMost(pageCount)
                                showQuizScopeDialog = true
                            }
                        }) {
                            Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.pdf_ai_quiz_action),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    )
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = showControls,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        // 上方：页码显示与快捷翻页
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    currentPage = (currentPage - 1).coerceAtLeast(0)
                                },
                                enabled = currentPage > 0
                            ) {
                                Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(stringResource(R.string.pdf_reader_prev_page), fontSize = 13.sp)
                            }

                            Text(
                                text = stringResource(R.string.pdf_reader_page_indicator, currentPageDisplay, pageCount),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            TextButton(
                                onClick = {
                                    currentPage = (currentPage + 1).coerceAtMost(pageCount - 1)
                                },
                                enabled = currentPage < pageCount - 1
                            ) {
                                Text(stringResource(R.string.pdf_reader_next_page), fontSize = 13.sp)
                                Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }

                        // 下方：可拖动跳页滑块
                        if (pageCount > 1) {
                            var sliderValue by remember(currentPage) {
                                mutableFloatStateOf(currentPage.toFloat())
                            }
                            Slider(
                                value = sliderValue,
                                onValueChange = { sliderValue = it },
                                onValueChangeFinished = {
                                    coroutineScope.launch {
                                        currentPage = sliderValue.toInt().coerceIn(0, pageCount - 1)
                                    }
                                },
                                valueRange = 0f..(pageCount - 1).toFloat(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(24.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        val screenWidth = LocalConfiguration.current.screenWidthDp
        val density = LocalDensity.current
        val screenWidthPx = with(density) { screenWidth.dp.roundToPx() }
        val targetRenderWidth = (screenWidthPx * 1.5f).toInt().coerceAtMost(2048)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFEEEEEE))
                .pointerInput(currentPage, pageCount) {
                    detectTapGestures(
                        onTap = { /* 单击统一由 PdfPageItem 处理（子元素会先消费点击） */ },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.0f
                            }
                        }
                    )
                }
                // 只在「双指」时缩放/平移；单指一律放给 LazyColumn 去滚动。
                // 原来是 Modifier.transformable，它连单指拖动也当 pan 消费掉，
                // 导致滑动翻页、animateScrollToItem、朗读自动翻页全部失效。
                .pointerInput(Unit) {
                    awaitEachGesture {
                        var event = awaitPointerEvent()
                        while (event.changes.size < 2 && event.changes.any { it.pressed }) {
                            event = awaitPointerEvent()
                        }
                        if (event.changes.size < 2) return@awaitEachGesture
                        do {
                            event = awaitPointerEvent()
                            if (event.changes.size >= 2) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (zoom != 1f) {
                                    scale = (scale * zoom).coerceIn(1f, 3.5f)
                                }
                                if (scale > 1f) offset += pan else offset = Offset.Zero
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        ) {
            PdfPageItem(
                holder = currentHolder,
                pageIndex = currentPage,
                targetRenderWidth = targetRenderWidth,
                screenWidthDp = screenWidth,
                selection = pdfSelection?.takeIf { it.pageIndex == currentPage },
                ttsSelection = ttsHighlight?.takeIf { it.pageIndex == currentPage },
                onPageTap = { fx ->
                    when {
                        fx < 0.25f && currentPage > 0 -> currentPage--
                        fx > 0.75f && currentPage < pageCount - 1 -> currentPage++
                        else -> showControls = !showControls
                    }
                },
                onSelectionGesture = { idx, sfx, sfy, efx, efy, phase ->
                            // phase 3 = 长按但没拖动就松手（多数是翻页后手指停住误触）
                            // → 直接忽略，不弹菜单，不打扰阅读
                            if (phase == 3) {
                                pdfSelection = null
                                selectionAnchor = null
                            } else coroutineScope.launch {
                                val pdfFile = currentHolder.file
                                val page = withContext(Dispatchers.IO) {
                                    com.moyue.reader.PdfTextLayer.extract(pdfFile, idx, context)
                                }
                                if (page == null || page.isEmpty) {
                                    if (phase == 0) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "这一页没有文字层（扫描版 PDF 不支持选词）",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    return@launch
                                }
                                val startIdx = com.moyue.reader.PdfTextLayer.charIndexAt(
                                    page, sfx * page.widthPt, sfy * page.heightPt
                                ) ?: return@launch

                                // 长按起点锚定成「单词 / 中文分句」，之后拖动只调整另一端
                                val anchor = if (phase == 0 || phase == 4) {
                                    com.moyue.reader.PdfTextLayer.wordRange(page, startIdx)
                                        .also { selectionAnchor = it }
                                } else {
                                    selectionAnchor ?: com.moyue.reader.PdfTextLayer.wordRange(page, startIdx)
                                }

                                // phase 0/3 = 长按（未拖动 / 未移动就松手）→ 终点取锚定词末尾
                                // phase 1/2 = 拖动中/拖动松手 → 终点取手指位置
                                val curIdx = if (phase == 0 || phase == 4) {
                                    anchor.second
                                } else {
                                    com.moyue.reader.PdfTextLayer.charIndexAt(
                                        page, efx * page.widthPt, efy * page.heightPt
                                    ) ?: anchor.second
                                }
                                val sel = if (curIdx >= anchor.first) {
                                    com.moyue.reader.PdfTextLayer.resolveRange(page, anchor.first, curIdx)
                                } else {
                                    com.moyue.reader.PdfTextLayer.resolveRange(page, curIdx, anchor.second)
                                }
                                if (sel != null) {
                                    pdfSelection = sel.copy(pageIndex = idx)
                                    if (phase == 2 || phase == 4) {
                                        lookupHit = com.moyue.reader.PdfTextLayer.Hit(
                                            sentence = sel.text,
                                            lineText = sel.text,
                                            pageIndex = idx,
                                        )
                                    }
                                } else if (phase == 2) {
                                    android.widget.Toast.makeText(
                                        context, "这里没有识别到文字", android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                                if (phase == 2 || phase == 4) selectionAnchor = null
                            }
                        }
            )
        }
    }

        // 「点哪读哪」结果卡片：浮在最上层，不跟随缩放/位移
        lookupHit?.let { hit ->
            com.moyue.ui.components.PdfLookupCard(
                hit = hit,
                onDismiss = { lookupHit = null },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 16.dp),
            )
        }
        if (lookupLoading) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.fillMaxSize(), strokeWidth = 3.dp)
            }
        }
    }
}

@Composable
private fun PdfPageItem(
    holder: PdfDocumentHolder,
    pageIndex: Int,
    targetRenderWidth: Int,
    screenWidthDp: Int,
    selection: com.moyue.reader.PdfTextLayer.Selection? = null,
    ttsSelection: com.moyue.reader.PdfTextLayer.Selection? = null,
    onSelectionGesture: ((Int, Float, Float, Float, Float, Int) -> Unit)? = null,
    onPageTap: ((Float) -> Unit)? = null,
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var aspectRatio by remember { mutableFloatStateOf(1f / 1.414f) }

    LaunchedEffect(pageIndex) {
        val (w, h) = holder.getPageDimensions(pageIndex)
        if (w > 0 && h > 0) {
            aspectRatio = w.toFloat() / h.toFloat()
        }
        val bmp = holder.renderPage(pageIndex, targetRenderWidth)
        bitmap = bmp
    }

    var startFx by remember(pageIndex) { mutableFloatStateOf(0f) }
    var startFy by remember(pageIndex) { mutableFloatStateOf(0f) }
    var lastFx by remember(pageIndex) { mutableFloatStateOf(0f) }
    var lastFy by remember(pageIndex) { mutableFloatStateOf(0f) }
    var moved by remember(pageIndex) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(4.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .pointerInput(pageIndex) {
                    // 双击 = 选中该位置的单词（浏览器 / Acrobat 同款手势），单击仍是切工具栏
                    detectTapGestures(
                        onTap = { off ->
                            val w = size.width.toFloat()
                            if (w > 0f) onPageTap?.invoke((off.x / w).coerceIn(0f, 1f))
                        },
                        onDoubleTap = { off ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            if (w > 0f && h > 0f) {
                                onSelectionGesture?.invoke(
                                    pageIndex,
                                    (off.x / w).coerceIn(0f, 1f),
                                    (off.y / h).coerceIn(0f, 1f),
                                    (off.x / w).coerceIn(0f, 1f),
                                    (off.y / h).coerceIn(0f, 1f),
                                    4, // 4 = 双击选词（直接弹卡片）
                                )
                            }
                        }
                    )
                }
                .pointerInput(pageIndex) {
                    // 长按选词 + 长按后拖动扩选
                    detectDragGesturesAfterLongPress(
                        onDragStart = { off ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            if (w > 0f && h > 0f) {
                                startFx = (off.x / w).coerceIn(0f, 1f)
                                startFy = (off.y / h).coerceIn(0f, 1f)
                                lastFx = startFx
                                lastFy = startFy
                                moved = false
                                onSelectionGesture?.invoke(pageIndex, startFx, startFy, lastFx, lastFy, 0)
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            if (w > 0f && h > 0f) {
                                lastFx = (change.position.x / w).coerceIn(0f, 1f)
                                lastFy = (change.position.y / h).coerceIn(0f, 1f)
                                moved = true
                                onSelectionGesture?.invoke(pageIndex, startFx, startFy, lastFx, lastFy, 1)
                            }
                        },
                        onDragEnd = {
                            // phase 3 = 长按未拖动就松手（终点应取锚定词末尾，而不是起点字符）
                            onSelectionGesture?.invoke(pageIndex, startFx, startFy, lastFx, lastFy, if (moved) 2 else 3)
                        },
                        onDragCancel = {
                            onSelectionGesture?.invoke(pageIndex, startFx, startFy, lastFx, lastFy, if (moved) 2 else 3)
                        },
                    )
                }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (bitmap != null && !bitmap!!.isRecycled) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                    }
                }

                // 朗读跟随高亮（绿色）
                if (ttsSelection != null && ttsSelection.rects.isNotEmpty() && ttsSelection.pageWidthPt > 0f) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        val sx = size.width / ttsSelection.pageWidthPt
                        val sy = size.height / ttsSelection.pageHeightPt
                        ttsSelection.rects.forEach { r ->
                            drawRect(
                                color = Color(0x6634D399),
                                topLeft = androidx.compose.ui.geometry.Offset(r.left * sx, r.top * sy),
                                size = androidx.compose.ui.geometry.Size(
                                    (r.right - r.left) * sx,
                                    (r.bottom - r.top) * sy,
                                ),
                            )
                        }
                    }
                }

                // 选区高亮：pt 坐标 → 控件像素
                if (selection != null && selection.rects.isNotEmpty() && selection.pageWidthPt > 0f) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        val sx = size.width / selection.pageWidthPt
                        val sy = size.height / selection.pageHeightPt
                        selection.rects.forEach { r ->
                            drawRect(
                                color = Color(0x553B82F6),
                                topLeft = androidx.compose.ui.geometry.Offset(r.left * sx, r.top * sy),
                                size = androidx.compose.ui.geometry.Size(
                                    (r.right - r.left) * sx,
                                    (r.bottom - r.top) * sy,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
