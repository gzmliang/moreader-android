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
import androidx.compose.foundation.gestures.detectTapGestures
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
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)

    // 保存进度函数
    fun saveProgress() {
        val page = listState.firstVisibleItemIndex
        val progress = if (pageCount > 0) (page + 1).toFloat() / pageCount else 0f
        coroutineScope.launch {
            repository.updatePdfProgress(bookId, page, progress)
        }
    }

    // 页面滚动停顿防抖自动更新进度
    LaunchedEffect(listState.firstVisibleItemIndex) {
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

    val currentPageDisplay = (listState.firstVisibleItemIndex + 1).coerceAtMost(pageCount)

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
                        TextButton(onClick = {
                            // 优先判断：如果已经有题目缓存，直接秒开做题界面
                            val cachedQuiz = currentGeneratedQuiz ?: aiCacheRepo.getQuiz(bookId, quizScopeStart, "chapter", quizQuestionCount, quizDifficulty)
                            if (cachedQuiz != null && cachedQuiz.questions.isNotEmpty()) {
                                showAiQuizScreen = true
                            } else {
                                val cur = (listState.firstVisibleItemIndex + 1).coerceIn(1, pageCount)
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
                                    val prev = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                                    coroutineScope.launch { listState.animateScrollToItem(prev) }
                                },
                                enabled = listState.firstVisibleItemIndex > 0
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
                                    val next = (listState.firstVisibleItemIndex + 1).coerceAtMost(pageCount - 1)
                                    coroutineScope.launch { listState.animateScrollToItem(next) }
                                },
                                enabled = listState.firstVisibleItemIndex < pageCount - 1
                            ) {
                                Text(stringResource(R.string.pdf_reader_next_page), fontSize = 13.sp)
                                Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }

                        // 下方：可拖动跳页滑块
                        if (pageCount > 1) {
                            var sliderValue by remember(listState.firstVisibleItemIndex) {
                                mutableFloatStateOf(listState.firstVisibleItemIndex.toFloat())
                            }
                            Slider(
                                value = sliderValue,
                                onValueChange = { sliderValue = it },
                                onValueChangeFinished = {
                                    coroutineScope.launch {
                                        listState.scrollToItem(sliderValue.toInt().coerceIn(0, pageCount - 1))
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
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            showControls = !showControls
                        },
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
                .transformable(state = transformState)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(pageCount) { pageIndex ->
                    PdfPageItem(
                        holder = currentHolder,
                        pageIndex = pageIndex,
                        targetRenderWidth = targetRenderWidth,
                        screenWidthDp = screenWidth
                    )
                }
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
        ) {
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
        }
    }
}
