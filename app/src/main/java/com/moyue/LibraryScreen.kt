package com.moyue.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.moyue.app.data.BookRepository
import com.moyue.app.data.models.*
import com.moyue.app.sync.PdfImportManager
import com.moyue.app.sync.SyncClient
import com.moyue.app.sync.WebDavClient
import com.moyue.app.ui.components.SyncSettingsDialog
import com.moyue.app.ui.components.WebDavBrowserDialog
import com.moyue.app.ui.components.ZipBookPickerDialog
import com.moyue.app.util.LocaleHelper
import com.moyue.app.util.extOf
import com.moyue.app.util.extractBooksFromZip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.moyue.ai.ui.AiSettingsDialog
import com.moyue.ai.data.AiCacheRepository
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (String) -> Unit,
    onOpenBookmarks: () -> Unit = {},
    onOpenVocabulary: () -> Unit = {},
    onOpenFlashcards: () -> Unit = {},
    onOpenCloudShelf: () -> Unit = {},
    repository: BookRepository,
    onLanguageSwitch: () -> Unit = {},
    sharedUris: List<Uri> = emptyList(),
    onSharedUrisConsumed: () -> Unit = {},
    viewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModelFactory(repository)
    ),
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val mergedItems by viewModel.mergedItems.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isSearchActive by viewModel.isSearchActive.collectAsStateWithLifecycle()
    // Upload progress state
    val isUploading by viewModel.isUploading.collectAsStateWithLifecycle()
    val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()
    val uploadTotal by viewModel.uploadTotal.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val pdfImport by PdfImportManager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()

    var showMoreMenu by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showUploadAllConfirm by remember { mutableStateOf(false) }
    var showWebDavDialog by remember { mutableStateOf(false) }
    var showSyncSettings by remember { mutableStateOf(false) }
    var showDonateDialog by remember { mutableStateOf(false) }
    val uploadScope = rememberCoroutineScope()
    val syncClientForUpload = remember { SyncClient(context) }
    val webDavClient = remember { WebDavClient(context) }

    /** 待确认转换选项的 PDF（非空 = 那个小窗正在显示） */
    var pdfOptionsUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    /** 小窗里的选择：本书的 AI 纠错级别（1=L1快速 2=L2通读 3=L3精读，默认 L1） */
    var pdfLlmLevel by remember { mutableStateOf(1) }

    /** 小窗里的开关：顺手修 OCR 认错的字（单独开关，默认关，仅 L2/L3 可用） */
    var pdfFixOcr by remember { mutableStateOf(false) }

    // Android 13+ 通知权限（后台转换完成后的提醒；拒绝也不影响功能）
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果不影响转换 */ }

    fun askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** 选好的 PDF 交给后台任务管理器（上传→云端排队转换→轮询进度→自动入库）
     *  先弹一个「转换选项」小窗：本书是否用大模型校对章节（每本书单独选，默认开） */
    fun submitPdfUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        pdfOptionsUris = uris
    }

    fun startPdfImport(uris: List<Uri>, llmLevel: Int, fixOcr: Boolean) {
        if (uris.isEmpty()) return
        val names = uris.map { viewModel.queryDisplayName(context, it) ?: "document.pdf" }
        askNotificationPermission()
        PdfImportManager.submit(context, uris, names, llmLevel = llmLevel, fixOcr = fixOcr)
    }

    /** 压缩包里有多本书时，弹出来让用户挑 */
    var zipPickLocal by remember { mutableStateOf<Pair<File, List<File>>?>(null) }

    /** 已落地到本地的书文件（如压缩包解出来的）按类型入库 */
    fun importLocalBookFile(file: File) {
        when (extOf(file.name)) {
            "epub" -> viewModel.importLocalEpubFile(context, file)
            "txt" -> viewModel.importTxtFile(context, file)
            "pdf" -> {
                val uri = runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }.getOrNull()
                if (uri != null) submitPdfUris(listOf(uri))
            }
        }
    }

    /** 统一导入入口：epub / txt / pdf / zip 各走各的通道 */
    fun dispatchImport(uris: List<Uri>) {
        if (uris.isEmpty()) return
        uris.forEach { uri ->
            val name = viewModel.queryDisplayName(context, uri) ?: ""
            when (extOf(name)) {
                "txt" -> coroutineScope.launch {
                    viewModel.copyUriToCache(context, uri, name)?.let { viewModel.importTxtFile(context, it) }
                }
                "zip" -> coroutineScope.launch {
                    val zf = viewModel.copyUriToCache(context, uri, name) ?: return@launch
                    val (dir, books) = withContext(Dispatchers.IO) { extractBooksFromZip(context, zf) }
                    zf.delete()
                    when {
                        books.isEmpty() -> {
                            dir.deleteRecursively()
                            android.widget.Toast.makeText(
                                context,
                                context.getString(com.moyue.app.R.string.webdav_zip_empty),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        books.size == 1 -> importLocalBookFile(books[0])
                        else -> zipPickLocal = Pair(dir, books)
                    }
                }
                "pdf" -> submitPdfUris(listOf(uri))
                else -> {
                    if (viewModel.isPdfUri(context, uri)) {
                        submitPdfUris(listOf(uri))
                    } else {
                        viewModel.importBook(context, uri)
                    }
                }
            }
        }
        coroutineScope.launch { gridState.animateScrollToItem(0) }
    }

    // Handle shared files from other apps
    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) {
            dispatchImport(sharedUris)
            onSharedUrisConsumed()
            // 导入后滚动到顶部
            coroutineScope.launch {
                gridState.animateScrollToItem(0)
            }
        }
    }

    // 回到书架时，如果 searchQuery 非空则自动激活搜索框
    LaunchedEffect(Unit) {
        if (searchQuery.isNotBlank() && !isSearchActive) {
            viewModel.setSearchActive(true)
        }
    }

    // 接上云端还没跑完的 PDF 转换任务（App 被杀掉/重新打开也不会白等）
    LaunchedEffect(Unit) {
        PdfImportManager.resume(context)
    }

    // File picker for EPUB / TXT / PDF / ZIP import (multiple files)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        dispatchImport(uris)
    }

    // File picker for PDF import（PDF 需上传到云端转成精读本）
    val importPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        submitPdfUris(uris)
    }

    // App dark mode toggle for UI screens (independent of reader theme)
    val systemInDark = isSystemInDarkTheme()
    val manualPref = com.moyue.app.ui.theme.getDarkModePreference(context)
    var isAppDark by remember { mutableStateOf(manualPref ?: systemInDark) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.search_hint), fontSize = 14.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp),
                        )
                    } else {
                        Text(
                            text = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.library_title),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    if (isSearchActive) {
                        IconButton(onClick = {
                            viewModel.setSearchActive(false)
                        }) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    }
                },
                actions = {
                    if (!isSearchActive) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 1. 搜索
                            IconButton(onClick = { viewModel.setSearchActive(true) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Search, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.search_hint), modifier = Modifier.size(22.dp))
                            }

                            // 2. 添加图书
                            IconButton(onClick = {
                                importLauncher.launch(
                                    arrayOf(
                                        "application/epub+zip",
                                        "text/plain",
                                        "application/pdf",
                                        "application/zip",
                                        "application/x-zip-compressed",
                                        "application/octet-stream",
                                    )
                                )
                            }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Add, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.import_book), modifier = Modifier.size(22.dp))
                            }

                            // 3. 更多菜单（方案 A）
                            Box {
                                IconButton(onClick = { showMoreMenu = true }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.MoreVert, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.action_more), modifier = Modifier.size(22.dp))
                                }

                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false }
                                ) {
                                    // 排序
                                    val currentSortLabel = when (sortOrder) {
                                        BookSortOrder.RECENT -> androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sort_recent)
                                        BookSortOrder.TITLE -> androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sort_name)
                                        BookSortOrder.AUTHOR -> androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sort_author)
                                        BookSortOrder.PROGRESS -> androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sort_progress)
                                    }
                                    DropdownMenuItem(
                                        text = { Text("${androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sort_title)} ($currentSortLabel)", fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            showMoreMenu = false
                                            showSortDialog = true
                                        }
                                    )

                                    HorizontalDivider()

                                    // 书签
                                    DropdownMenuItem(
                                        text = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.bookmark_list_title), fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            showMoreMenu = false
                                            onOpenBookmarks()
                                        }
                                    )

                                    // 生词本
                                    DropdownMenuItem(
                                        text = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.vocabulary_title), fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            showMoreMenu = false
                                            onOpenVocabulary()
                                        }
                                    )

                                    // 闪卡
                                    DropdownMenuItem(
                                        text = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.flashcard_title), fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            showMoreMenu = false
                                            onOpenFlashcards()
                                        }
                                    )

                                    HorizontalDivider()

                                    // 深色/浅色模式切换
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (isAppDark) androidx.compose.ui.res.stringResource(com.moyue.app.R.string.theme_light_mode)
                                                else androidx.compose.ui.res.stringResource(com.moyue.app.R.string.theme_dark_mode),
                                                fontSize = 13.sp
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (isAppDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            val newDark = !isAppDark
                                            isAppDark = newDark
                                            com.moyue.app.ui.theme.saveDarkModePreference(context, newDark)
                                            (context as? androidx.activity.ComponentActivity)?.recreate()
                                        }
                                    )

                                    // 云端书库（独立页面，带封面）
                                    DropdownMenuItem(
                                        text = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.menu_cloud_shelf), fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            showMoreMenu = false
                                            onOpenCloudShelf()
                                        }
                                    )

                                    // 导入 PDF（云端转成精读本）
                                    DropdownMenuItem(
                                        text = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_import_menu), fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            showMoreMenu = false
                                            importPdfLauncher.launch(arrayOf("application/pdf"))
                                        }
                                    )

                                    // 云同步设置
                                    DropdownMenuItem(
                                        text = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.menu_sync_settings), fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                        onClick = {
                                            showMoreMenu = false
                                            showSyncSettings = true
                                        }
                                    )

                                    // 云端同步全部上传
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_upload_all_webdav_title),
                                                fontSize = 13.sp
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.CloudUpload,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            if (webDavClient.isConfigured()) {
                                                showUploadAllConfirm = true
                                            } else {
                                                showSyncSettings = true
                                            }
                                        }
                                    )
                                    // 请作者喝杯咖啡 / 赞赏支持
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                androidx.compose.ui.res.stringResource(com.moyue.app.R.string.donate_menu_title),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color(0xFFD97706)
                                            )
                                        },
                                        leadingIcon = {
                                            Text("☕", fontSize = 16.sp)
                                        },
                                        onClick = {
                                            showMoreMenu = false
                                            showDonateDialog = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            // Language switcher bar
            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Language switch button
                    var showLangMenu by remember { mutableStateOf(false) }
                    val currentLang = LocaleHelper.getSelectedLanguage(context)

                    Box {
                        TextButton(onClick = { showLangMenu = true }) {
                            Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                when (currentLang) {
                                    "zh" -> androidx.compose.ui.res.stringResource(com.moyue.app.R.string.lang_zh)
                                    "en" -> androidx.compose.ui.res.stringResource(com.moyue.app.R.string.lang_en)
                                    else -> {
                                        // Show current system language
                                        val sys = java.util.Locale.getDefault().language
                                        if (sys == "zh") androidx.compose.ui.res.stringResource(com.moyue.app.R.string.lang_zh) else if (sys.startsWith("en")) androidx.compose.ui.res.stringResource(com.moyue.app.R.string.lang_en) else sys
                                    }
                                },
                                fontSize = 12.sp,
                            )
                        }

                        DropdownMenu(expanded = showLangMenu, onDismissRequest = { showLangMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (currentLang == "zh") "✓ " + androidx.compose.ui.res.stringResource(com.moyue.app.R.string.lang_zh) else androidx.compose.ui.res.stringResource(com.moyue.app.R.string.lang_zh), fontSize = 13.sp) },
                                onClick = {
                                    showLangMenu = false
                                    LocaleHelper.setSelectedLanguage(context, "zh")
                                    onLanguageSwitch()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (currentLang == "en") "✓ " + androidx.compose.ui.res.stringResource(com.moyue.app.R.string.lang_en) else androidx.compose.ui.res.stringResource(com.moyue.app.R.string.lang_en), fontSize = 13.sp) },
                                onClick = {
                                    showLangMenu = false
                                    LocaleHelper.setSelectedLanguage(context, "en")
                                    onLanguageSwitch()
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(if (currentLang == null) "✓ " + androidx.compose.ui.res.stringResource(com.moyue.app.R.string.follow_system) else androidx.compose.ui.res.stringResource(com.moyue.app.R.string.follow_system), fontSize = 13.sp) },
                                onClick = {
                                    showLangMenu = false
                                    LocaleHelper.setSelectedLanguage(context, null)
                                    onLanguageSwitch()
                                },
                            )
                        }
                    }

                    // WebDAV 网盘浏览按钮
                    IconButton(onClick = { showWebDavDialog = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Storage, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.webdav_title), modifier = Modifier.size(18.dp))
                    }
                    if (showWebDavDialog) {
                        WebDavBrowserDialog(
                            webDavClient = webDavClient,
                            onDismiss = { showWebDavDialog = false },
                            onBookImported = {
                                coroutineScope.launch {
                                    gridState.animateScrollToItem(0)
                                }
                            },
                            // 网盘里的 PDF 也走「先选 AI 纠错级别」的小窗（与本地导入同一口径）
                            onPickPdf = { uri, _ -> submitPdfUris(listOf(uri)) }
                        )
                    }

                    // 压缩包里的多本书：挑一本后删除解压目录
                    zipPickLocal?.let { pick ->
                        val dir = pick.first
                        val books = pick.second
                        ZipBookPickerDialog(
                            books = books,
                            onPick = { book ->
                                zipPickLocal = null
                                importLocalBookFile(book)
                                dir.deleteRecursively()
                            },
                            onDismiss = {
                                zipPickLocal = null
                                dir.deleteRecursively()
                            },
                        )
                    }

                    // Sync settings（底部云图标改为打开带封面的云端书库）
                    IconButton(onClick = { onOpenCloudShelf() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Cloud, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.menu_cloud_shelf),
                            modifier = Modifier.size(18.dp))
                    }
                    if (showSyncSettings) {
                        val syncClientForSync = remember { SyncClient(context) }
                        SyncSettingsDialog(
                            syncClient = syncClientForSync,
                            onDismiss = { showSyncSettings = false },
                            onUpload = { onResult ->
                                if (webDavClient.isConfigured()) {
                                    viewModel.uploadAllToWebDav(context, webDavClient)
                                    onResult(context.getString(com.moyue.app.R.string.sync_webdav_uploading))
                                } else {
                                    onResult(context.getString(com.moyue.app.R.string.webdav_not_configured))
                                }
                            },
                            onOpenWebDav = {
                                showSyncSettings = false
                                showWebDavDialog = true
                            },
                        )
                    }

                    // AI Settings
                    var showAiSettings by remember { mutableStateOf(false) }
                    IconButton(onClick = { showAiSettings = true }, modifier = Modifier.size(32.dp)) {
                        Text("🤖", fontSize = 16.sp)
                    }
                    if (showAiSettings) {
                        val aiRepo = remember { AiCacheRepository(context) }
                        AiSettingsDialog(
                            repository = aiRepo,
                            onDismiss = { showAiSettings = false },
                            onSaved = { /* updated */ }
                        )
                    }
                }
            }
        }
    ) { padding ->
        // 排序选择对话框
        if (showSortDialog) {
            AlertDialog(
                onDismissRequest = { showSortDialog = false },
                title = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sort_title)) },
                text = {
                    Column {
                        listOf(
                            BookSortOrder.RECENT to androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sort_recent),
                            BookSortOrder.TITLE to androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sort_name),
                            BookSortOrder.AUTHOR to androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sort_author),
                            BookSortOrder.PROGRESS to androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sort_progress),
                        ).forEach { (order, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setSortOrder(order)
                                        showSortDialog = false
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (sortOrder == order),
                                    onClick = {
                                        viewModel.setSortOrder(order)
                                        showSortDialog = false
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(text = label, fontSize = 15.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSortDialog = false }) {
                        Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.cancel))
                    }
                }
            )
        }

        // 云端全部上传确认对话框
        if (showUploadAllConfirm) {
            AlertDialog(
                onDismissRequest = { if (!isUploading) showUploadAllConfirm = false },
                title = {
                    Text(
                        if (isUploading) androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_uploading)
                        else androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_upload_all_webdav_title)
                    )
                },
                text = {
                    if (isUploading) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$uploadProgress / $uploadTotal")
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { if (uploadTotal > 0) uploadProgress.toFloat() / uploadTotal else 0f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_upload_all_webdav_confirm))
                    }
                },
                confirmButton = {
                    if (!isUploading) {
                        TextButton(onClick = {
                            uploadScope.launch {
                                viewModel.uploadAllToWebDav(context, webDavClient)
                            }
                        }) { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_upload), color = MaterialTheme.colorScheme.primary) }
                    }
                },
                dismissButton = {
                    if (!isUploading) {
                        TextButton(onClick = { showUploadAllConfirm = false }) { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.cancel)) }
                    } else {
                        TextButton(onClick = { showUploadAllConfirm = false }) { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_background)) }
                    }
                },
            )
        }
        if (showDonateDialog) {
            com.moyue.ui.components.DonateDialog(onDismiss = { showDonateDialog = false })
        }

        // ── 导入 PDF：转换选项（每本书确认一次）──
        if (pdfOptionsUris.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { pdfOptionsUris = emptyList() },
                title = {
                    Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_options_title), fontSize = 15.sp)
                },
                text = {
                    Column {
                        Text(
                            androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_options_ai_hint),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        // AI 纠错级别：L1 最快，L2/L3 逐行通读正文（梁老师定的默认＝L1 快速）
                        listOf(
                            0 to com.moyue.app.R.string.pdf_options_off,
                            1 to com.moyue.app.R.string.pdf_options_l1,
                            2 to com.moyue.app.R.string.pdf_options_l2,
                            3 to com.moyue.app.R.string.pdf_options_l3
                        ).forEach { (level, res) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { pdfLlmLevel = level }
                            ) {
                                RadioButton(
                                    selected = pdfLlmLevel == level,
                                    onClick = { pdfLlmLevel = level },
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    androidx.compose.ui.res.stringResource(res),
                                    fontSize = 13.sp,
                                    color = if (pdfLlmLevel == level) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_options_ocr_fix),
                                    fontSize = 13.sp,
                                    color = if (pdfLlmLevel >= 2) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Text(
                                    androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_options_ocr_fix_hint),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Switch(
                                checked = pdfFixOcr && pdfLlmLevel >= 2,
                                onCheckedChange = { pdfFixOcr = it },
                                enabled = pdfLlmLevel >= 2
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val picked = pdfOptionsUris
                        pdfOptionsUris = emptyList()
                        startPdfImport(picked, pdfLlmLevel, pdfFixOcr)
                    }) {
                        Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_options_start))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pdfOptionsUris = emptyList() }) {
                        Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.cancel))
                    }
                },
            )
        }

        // ── PDF 后台转换：进度弹窗（可「后台继续」）+ 结果提示 ──
        val focusEntry = pdfImport.focus
        if (pdfImport.dialogVisible && focusEntry != null) {
            when (focusEntry.phase) {
                PdfImportManager.Phase.PENDING,
                PdfImportManager.Phase.UPLOADING,
                PdfImportManager.Phase.CONVERTING,
                PdfImportManager.Phase.DOWNLOADING,
                PdfImportManager.Phase.IMPORTING -> AlertDialog(
                    onDismissRequest = { PdfImportManager.dismissDialog() },
                    title = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_convert_title)) },
                    text = {
                        Column {
                            Text(focusEntry.fileName, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { focusEntry.percent.coerceIn(0, 100).toFloat() / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "${focusEntry.percent}%  ·  " + androidx.compose.ui.res.stringResource(pdfStageRes(focusEntry)),
                                fontSize = 13.sp,
                            )
                            if (focusEntry.phase == PdfImportManager.Phase.UPLOADING &&
                                focusEntry.stage == PdfImportManager.STAGE_UPLOAD &&
                                focusEntry.uploadTotalBytes > 0
                            ) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    androidx.compose.ui.res.stringResource(
                                        com.moyue.app.R.string.pdf_progress_uploaded,
                                        pdfFormatBytes(focusEntry.uploadSentBytes),
                                        pdfFormatBytes(focusEntry.uploadTotalBytes)),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                )
                            }
                            if (focusEntry.pagesTotal > 0) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    androidx.compose.ui.res.stringResource(
                                        com.moyue.app.R.string.pdf_progress_pages,
                                        focusEntry.pagesDone, focusEntry.pagesTotal),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                )
                            }
                            if (focusEntry.etaSeconds > 0) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    androidx.compose.ui.res.stringResource(
                                        com.moyue.app.R.string.pdf_progress_eta, pdfFormatEta(focusEntry.etaSeconds)),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                )
                            }
                            if (pdfImport.queuedCount > 1) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    androidx.compose.ui.res.stringResource(
                                        com.moyue.app.R.string.pdf_banner_queue, pdfImport.queuedCount - 1),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_convert_background_hint),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { PdfImportManager.dismissDialog() }) {
                            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_convert_background))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { PdfImportManager.cancel(focusEntry.localId) }) {
                            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_convert_cancel))
                        }
                    },
                )

                PdfImportManager.Phase.DONE -> AlertDialog(
                    onDismissRequest = { PdfImportManager.clearFinished() },
                    title = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_convert_title)) },
                    text = {
                        Column {
                            Text(
                                androidx.compose.ui.res.stringResource(
                                    com.moyue.app.R.string.pdf_convert_done, focusEntry.title),
                                fontSize = 14.sp,
                            )
                            // AI 纠错干了多少活（L1/L2/L3）：清了水印/广告多少处、修了多少 OCR 错字
                            if (focusEntry.llmCutLines > 0 || focusEntry.llmOcrFixes > 0) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    androidx.compose.ui.res.stringResource(
                                        com.moyue.app.R.string.pdf_convert_ai_report,
                                        focusEntry.llmCutLines, focusEntry.llmOcrFixes),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // 没清掉多少也要说：拿不出证据、本档没敢动的那批（提示可以升档重跑）
                            if (focusEntry.llmUncleared > 0) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    androidx.compose.ui.res.stringResource(
                                        com.moyue.app.R.string.pdf_convert_ai_uncleared,
                                        focusEntry.llmUncleared),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val bookId = focusEntry.bookId
                            PdfImportManager.clearFinished()
                            if (bookId != null) onOpenBook(bookId)
                        }) {
                            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.cloud_shelf_open_now))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { PdfImportManager.clearFinished() }) {
                            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_convert_close))
                        }
                    },
                )

                PdfImportManager.Phase.FAILED -> AlertDialog(
                    onDismissRequest = { PdfImportManager.clearFinished() },
                    title = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_convert_title)) },
                    text = {
                        val msg = when (focusEntry.errorCode) {
                            "login_required" -> androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_error_login_required)
                            "scanned" -> androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_error_scanned)
                            "too_large" -> androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_error_too_large)
                            "empty" -> androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_error_empty)
                            "ocr_too_long" -> androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_error_ocr_too_long)
                            "ocr_unavailable" -> androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_error_ocr_unavailable)
                            "network" -> androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_error_network)
                            "network_stalled" -> androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_error_stalled)
                            "prepare_failed" -> androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_error_prepare_failed)
                            "import_failed" -> androidx.compose.ui.res.stringResource(
                                com.moyue.app.R.string.pdf_error_import_failed, focusEntry.message ?: "")
                            else -> androidx.compose.ui.res.stringResource(
                                com.moyue.app.R.string.pdf_error_failed, focusEntry.message ?: "")
                        }
                        Text(msg, fontSize = 14.sp)
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val needLogin = focusEntry.errorCode == "login_required"
                            PdfImportManager.clearFinished()
                            if (needLogin) showSyncSettings = true
                        }) {
                            Text(
                                if (focusEntry.errorCode == "login_required")
                                    androidx.compose.ui.res.stringResource(com.moyue.app.R.string.cloud_shelf_login_btn)
                                else androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_convert_close)
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { PdfImportManager.clearFinished() }) {
                            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_convert_close))
                        }
                    },
                )

                PdfImportManager.Phase.CANCELED -> AlertDialog(
                    onDismissRequest = { PdfImportManager.clearFinished() },
                    title = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_convert_title)) },
                    text = {
                        Text(
                            androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_error_canceled),
                            fontSize = 14.sp,
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { PdfImportManager.clearFinished() }) {
                            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.pdf_convert_close))
                        }
                    },
                )
            }
        }

        // Load cloud books when logged in
        val syncClient = remember { SyncClient(context) }
        LaunchedEffect(syncClient.isLoggedIn()) {
            if (syncClient.isLoggedIn()) {
                viewModel.loadCloudBooks(syncClient)
            }
        }

        if (books.isEmpty() && mergedItems.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                PdfJobBanner(pdfImport, Modifier.align(Alignment.TopCenter)) { PdfImportManager.openDialog() }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.AutoStories,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        androidx.compose.ui.res.stringResource(com.moyue.app.R.string.empty_library_hint),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = {
                            importLauncher.launch(
                                arrayOf(
                                    "application/epub+zip",
                                    "text/plain",
                                    "application/pdf",
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream",
                                )
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.import_book))
                    }
                }
            }
        } else if (mergedItems.isEmpty()) {
            // No search results
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        androidx.compose.ui.res.stringResource(com.moyue.app.R.string.search_no_results),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        fontSize = 14.sp,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                PdfJobBanner(pdfImport) { PdfImportManager.openDialog() }
                // 搜索结果显示提示
                if (searchQuery.isNotBlank()) {
                    Text(
                        androidx.compose.ui.res.stringResource(com.moyue.app.R.string.search_results_found_fmt, searchQuery, mergedItems.size),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                    )
                }
                LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(mergedItems, key = {
                    it.localBook?.id ?: "cloud_${it.cloudInfo?.id}"
                }) { item ->
                    if (item.isCloudOnly) {
                        val info = item.cloudInfo
                        var cloudCover by remember(info?.id) { mutableStateOf<File?>(null) }
                        LaunchedEffect(info?.id) {
                            val id = info?.id ?: return@LaunchedEffect
                            if (info.hasCover) {
                                if (syncClient.fetchBookCover(id) != null) {
                                    cloudCover = syncClient.coverCacheFile(id)
                                }
                            }
                        }
                        CloudOnlyBookCard(
                            title = item.title,
                            author = item.author,
                            coverFile = cloudCover,
                            onDownload = {
                                item.cloudInfo?.let { info ->
                                    viewModel.downloadCloudBook(context, syncClient, info)
                                }
                            },
                        )
                    } else {
                        val book = item.localBook!!
                        val webDavClientForUpload = remember { WebDavClient(context) }
                        BookCard(
                            book = book,
                            onClick = { onOpenBook(book.id) },
                            onDelete = { viewModel.deleteBook(context, book) },
                            onUploadToWebDav = {
                                if (webDavClientForUpload.isConfigured()) {
                                    viewModel.uploadSingleBookToWebDav(context, webDavClientForUpload, book.id)
                                } else {
                                    showSyncSettings = true
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
        }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: Book,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onUploadToWebDav: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }

    if (showMenu) {
        AlertDialog(
            onDismissRequest = { showMenu = false },
            title = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.select_action))
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { showMenu = false; onUploadToWebDav() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CloudUpload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_backup_single_btn), modifier = Modifier.weight(1f))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                Row {
                    TextButton(onClick = { showMenu = false }) { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { showMenu = false; onDelete() }) {
                        Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true }
            ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            // Cover
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (book.coverPath != null && File(book.coverPath).exists()) {
                    AsyncImage(
                        model = File(book.coverPath),
                        contentDescription = book.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Default.AutoStories,
                        contentDescription = book.title,
                        modifier = Modifier.size(40.dp),
                        tint = Color.Gray.copy(alpha = 0.4f),
                    )
                }
            }

            // Info
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    book.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    book.author,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 云端独有书籍卡片（淡色遮罩 + 渐显封面） */
@Composable
private fun CloudOnlyBookCard(
    title: String,
    author: String,
    coverFile: File? = null,
    onDownload: () -> Unit,
) {
    var isDownloading by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isDownloading) {
                isDownloading = true
                onDownload()
            },
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            width = 1.dp,
            brush = androidx.compose.ui.graphics.SolidColor(
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
        ),
    ) {
        Column {
            // Cover — 有云端封面就展示封面，否则用云朵图标
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                if (coverFile != null && coverFile.exists()) {
                    AsyncImage(
                        model = coverFile,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_download),
                        modifier = Modifier.size(32.dp),
                        tint = Color.Gray.copy(alpha = 0.5f),
                    )
                }
                if (isDownloading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    }
                }
            }

            // Info
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
                Text(
                    author,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 书架顶部的 PDF 转换进度横条（点一下就把进度弹窗重新打开） */
@Composable
private fun PdfJobBanner(
    state: PdfImportManager.State,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val entry = state.active ?: return
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable { onOpen() },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    androidx.compose.ui.res.stringResource(
                        com.moyue.app.R.string.pdf_banner_title, entry.title, entry.percent),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (state.queuedCount > 1) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        androidx.compose.ui.res.stringResource(
                            com.moyue.app.R.string.pdf_banner_queue, state.queuedCount - 1),
                        fontSize = 11.sp,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { entry.percent.coerceIn(0, 100).toFloat() / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 转换阶段 → 本地化文案（服务端只回英文阶段名，本地化由 App 负责，不显示服务端中文） */
private fun pdfStageRes(e: PdfImportManager.Entry): Int = when {
    e.phase == PdfImportManager.Phase.PENDING -> com.moyue.app.R.string.pdf_phase_uploading
    e.phase == PdfImportManager.Phase.UPLOADING -> if (e.stage == PdfImportManager.STAGE_COPY)
        com.moyue.app.R.string.pdf_phase_copying else com.moyue.app.R.string.pdf_phase_uploading
    e.phase == PdfImportManager.Phase.DOWNLOADING -> com.moyue.app.R.string.pdf_phase_downloading
    e.phase == PdfImportManager.Phase.IMPORTING -> com.moyue.app.R.string.pdf_phase_importing
    e.stage == "queued" -> com.moyue.app.R.string.pdf_phase_queued
    e.stage == "ocr" -> com.moyue.app.R.string.pdf_phase_ocr
    e.stage == "build" || e.stage == "done" -> com.moyue.app.R.string.pdf_phase_packaging
    else -> com.moyue.app.R.string.pdf_phase_analyzing
}

/** 字节数 → "12.3 MB"（语言无关，不额外增加 i18n 词条） */
private fun pdfFormatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / 1048576.0
    return if (mb >= 1) String.format(java.util.Locale.US, "%.1f MB", mb)
    else String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
}

/** 预计剩余时间 → m:ss / h:mm:ss（语言无关，不额外增加 i18n 词条） */
private fun pdfFormatEta(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}
