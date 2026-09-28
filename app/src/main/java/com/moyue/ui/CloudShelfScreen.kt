package com.moyue.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.moyue.app.data.BookRepository
import com.moyue.app.sync.SyncClient
import com.moyue.app.sync.WebDavClient
import com.moyue.app.ui.components.SyncSettingsDialog
import com.moyue.app.ui.components.WebDavBrowserDialog
import kotlinx.coroutines.launch
import java.io.File

/**
 * 云端书库独立页面 —— 3 列封面网格，秒看、秒找、秒下。
 *
 * 与旧版「云同步设置」对话框里的文字列表相比：
 *  · 有封面（服务器从 EPUB 里抽的小图，最长边 320px）
 *  · 三列大卡片，搜索栏常驻
 *  · 邮箱等账号信息只在同步设置里出现，不再常驻书架顶部
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CloudShelfScreen(
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val syncClient = remember { SyncClient(context) }
    val webDavClient = remember { WebDavClient(context) }

    val localBooks by viewModel.books.collectAsStateWithLifecycle()

    var cloudBooks by remember { mutableStateOf<List<SyncClient.BookInfo>?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var downloadingId by remember { mutableStateOf<Int?>(null) }
    var deleteTarget by remember { mutableStateOf<SyncClient.BookInfo?>(null) }
    var loggedInVersion by remember { mutableIntStateOf(0) }
    var showSyncSettings by remember { mutableStateOf(false) }
    var showWebDavDialog by remember { mutableStateOf(false) }
    var covers by remember { mutableStateOf<Map<Int, File>>(emptyMap()) }
    val isLoggedIn = remember(loggedInVersion) { syncClient.isLoggedIn() }

    fun reload() {
        if (!syncClient.isLoggedIn()) return
        isLoading = true
        errorMessage = null
        scope.launch {
            syncClient.listBooks().fold(
                onSuccess = { list ->
                    cloudBooks = list
                    isLoading = false
                },
                onFailure = { e ->
                    errorMessage = e.message ?: ""
                    isLoading = false
                },
            )
        }
    }

    LaunchedEffect(loggedInVersion) {
        if (syncClient.isLoggedIn()) reload() else cloudBooks = null
    }

    // 逐本按需拉封面（服务端会缓存，手机端再落一层磁盘缓存）
    LaunchedEffect(cloudBooks) {
        val list = cloudBooks ?: return@LaunchedEffect
        val missing = list.filter { it.hasCover && covers[it.id] == null }
        for (b in missing) {
            val bytes = syncClient.fetchBookCover(b.id)
            if (bytes != null) {
                covers = covers + (b.id to syncClient.coverCacheFile(b.id))
            }
        }
    }

    val localTitles = remember(localBooks) { localBooks.associateBy { it.title } }
    val filtered = remember(cloudBooks, query) {
        val list = cloudBooks ?: emptyList()
        if (query.isBlank()) list
        else list.filter {
            it.title.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            androidx.compose.ui.res.stringResource(com.moyue.app.R.string.cloud_shelf_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp,
                        )
                        cloudBooks?.let {
                            Text(
                                androidx.compose.ui.res.stringResource(
                                    com.moyue.app.R.string.cloud_shelf_count_fmt, it.size),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { reload() }, enabled = !isLoading && isLoggedIn) {
                        Icon(Icons.Default.Refresh, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.cloud_shelf_refresh))
                    }
                    IconButton(onClick = { showSyncSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.cloud_shelf_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!isLoggedIn) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Icon(Icons.Default.CloudOff, contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                        Spacer(Modifier.height(14.dp))
                        Text(
                            androidx.compose.ui.res.stringResource(com.moyue.app.R.string.cloud_shelf_login_hint),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Spacer(Modifier.height(18.dp))
                        Button(onClick = { showSyncSettings = true }, shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.cloud_shelf_login_btn))
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = {
                        Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.cloud_shelf_search_hint), fontSize = 14.sp)
                    },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .height(50.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                    shape = RoundedCornerShape(12.dp),
                )

                when {
                    isLoading && cloudBooks == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    errorMessage != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Text(
                                androidx.compose.ui.res.stringResource(
                                    com.moyue.app.R.string.cloud_shelf_load_failed, errorMessage ?: ""),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { reload() }) {
                                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.cloud_shelf_refresh))
                            }
                        }
                    }
                    (cloudBooks?.isEmpty() ?: false) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            androidx.compose.ui.res.stringResource(com.moyue.app.R.string.cloud_shelf_empty),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                    filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            androidx.compose.ui.res.stringResource(com.moyue.app.R.string.cloud_shelf_no_match),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(filtered, key = { it.id }) { book ->
                            CloudBookCard(
                                book = book,
                                coverFile = covers[book.id],
                                isDownloading = downloadingId == book.id,
                                isOnDevice = localTitles.containsKey(book.title),
                                onOpenLocal = {
                                    localTitles[book.title]?.let { onOpenBook(it.id) }
                                },
                                onDownload = {
                                    downloadingId = book.id
                                    viewModel.downloadCloudBook(context, syncClient, book) { bookId ->
                                        downloadingId = null
                                        onOpenBook(bookId)
                                    }
                                },
                                onLongClick = { deleteTarget = book },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSyncSettings) {
        SyncSettingsDialog(
            syncClient = syncClient,
            onDismiss = {
                showSyncSettings = false
                loggedInVersion++
            },
            onUpload = { onResult ->
                val target = webDavClient.getDefaultCloudTarget()
                if (target == "WEBDAV") {
                    if (webDavClient.isConfigured()) {
                        viewModel.uploadAllToWebDav(context, webDavClient)
                        onResult(context.getString(com.moyue.app.R.string.sync_webdav_uploading))
                    } else {
                        onResult(context.getString(com.moyue.app.R.string.webdav_not_configured))
                    }
                } else {
                    viewModel.uploadToCloud(context, syncClient, onResult)
                }
            },
            onDownload = { onResult ->
                viewModel.downloadFromCloud(context, syncClient, onResult)
            },
            onOpenWebDav = {
                showSyncSettings = false
                showWebDavDialog = true
            },
        )
    }

    if (showWebDavDialog) {
        WebDavBrowserDialog(
            webDavClient = webDavClient,
            onDismiss = { showWebDavDialog = false },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(target.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Text(
                    androidx.compose.ui.res.stringResource(
                        com.moyue.app.R.string.cloud_shelf_delete_confirm, target.title),
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        syncClient.deleteCloudBook(target.id).fold(
                            onSuccess = {
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(com.moyue.app.R.string.cloud_shelf_deleted),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                                cloudBooks = cloudBooks?.filter { it.id != target.id }
                                viewModel.loadCloudBooks(syncClient)
                            },
                            onFailure = { e ->
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(com.moyue.app.R.string.sync_delete_fail, e.message ?: ""),
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            },
                        )
                    }
                }) {
                    Text(
                        androidx.compose.ui.res.stringResource(com.moyue.app.R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.cancel))
                }
            },
        )
    }
}

/** 云端书库卡片：封面 + 书名 + 作者（本地已有角标） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CloudBookCard(
    book: SyncClient.BookInfo,
    coverFile: File?,
    isDownloading: Boolean,
    isOnDevice: Boolean,
    onOpenLocal: () -> Unit,
    onDownload: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (!isDownloading) { if (isOnDevice) onOpenLocal() else onDownload() } },
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (coverFile != null && coverFile.exists()) {
                    AsyncImage(
                        model = coverFile,
                        contentDescription = book.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Default.MenuBook,
                        contentDescription = book.title,
                        modifier = Modifier.size(34.dp),
                        tint = Color.Gray.copy(alpha = 0.45f),
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
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp,
                            color = Color.White,
                        )
                    }
                }
                if (isOnDevice && !isDownloading) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(bottomEnd = 8.dp),
                        modifier = Modifier.align(Alignment.TopStart),
                    ) {
                        Text(
                            androidx.compose.ui.res.stringResource(com.moyue.app.R.string.cloud_shelf_on_device),
                            fontSize = 10.sp,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
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
