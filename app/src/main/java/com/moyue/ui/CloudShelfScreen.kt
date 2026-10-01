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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.moyue.app.R
import com.moyue.app.data.BookRepository
import com.moyue.app.data.models.Book
import com.moyue.app.sync.WebDavClient
import com.moyue.app.ui.components.SyncSettingsDialog
import com.moyue.app.ui.components.WebDavBrowserDialog
import kotlinx.coroutines.launch
import java.io.File

/**
 * 云端书库页面 —— 全面直连 WebDAV（AList / 坚果云 / 群晖），3 列网格展示，一键秒下秒读。
 */
data class WebDavBookDisplay(
    val title: String,
    val davItem: WebDavClient.DavItem,
    val localBook: Book?,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CloudShelfScreen(
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val webDavClient = remember { WebDavClient(context) }
    val repo = remember { BookRepository(context) }

    val localBooks by viewModel.books.collectAsStateWithLifecycle()

    var cloudBooks by remember { mutableStateOf<List<WebDavBookDisplay>?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var downloadingTitle by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<WebDavBookDisplay?>(null) }
    var configVersion by remember { mutableIntStateOf(0) }
    var showSyncSettings by remember { mutableStateOf(false) }
    var showWebDavDialog by remember { mutableStateOf(false) }

    val isConfigured = remember(configVersion) { webDavClient.isConfigured() }

    fun reload() {
        if (!webDavClient.isConfigured()) {
            cloudBooks = null
            return
        }
        isLoading = true
        errorMessage = null
        scope.launch {
            val dir = webDavClient.getDefaultUploadDir()
            webDavClient.listFiles(dir).fold(
                onSuccess = { items ->
                    val epubs = items.filter { item ->
                        !item.isDirectory && item.name.endsWith(".epub", ignoreCase = true)
                    }.map { item ->
                        val cleanTitle = item.name.removeSuffix(".epub").removeSuffix(".EPUB")
                        val matchedLocal = localBooks.find { lb ->
                            lb.title.equals(cleanTitle, ignoreCase = true) ||
                            item.name.equals(File(lb.filePath).name, ignoreCase = true)
                        }
                        WebDavBookDisplay(
                            title = cleanTitle,
                            davItem = item,
                            localBook = matchedLocal
                        )
                    }
                    cloudBooks = epubs
                    isLoading = false
                },
                onFailure = { e ->
                    errorMessage = e.message ?: ""
                    isLoading = false
                }
            )
        }
    }

    LaunchedEffect(configVersion, localBooks) {
        if (webDavClient.isConfigured()) reload() else cloudBooks = null
    }

    val filtered = remember(cloudBooks, query) {
        val list = cloudBooks ?: emptyList()
        if (query.isBlank()) list
        else list.filter { it.title.contains(query, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.cloud_shelf_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp,
                        )
                        cloudBooks?.let {
                            Text(
                                stringResource(R.string.cloud_shelf_count_fmt, it.size),
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
                    IconButton(onClick = { reload() }, enabled = !isLoading && isConfigured) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cloud_shelf_refresh))
                    }
                    IconButton(onClick = { showSyncSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cloud_shelf_settings))
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
            if (!isConfigured) {
                // ── 未配置 WebDAV 引导页 ──
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Icon(
                            Icons.Default.CloudQueue,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.sync_shelf_not_configured_title),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.sync_shelf_not_configured_desc),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { showSyncSettings = true },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.sync_shelf_configure_now))
                        }
                    }
                }
            } else {
                // ── 搜索框 ──
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = {
                        Text(stringResource(R.string.cloud_shelf_search_hint), fontSize = 14.sp)
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
                                stringResource(R.string.cloud_shelf_load_failed, errorMessage ?: ""),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { reload() }) {
                                Text(stringResource(R.string.cloud_shelf_refresh))
                            }
                        }
                    }
                    (cloudBooks?.isEmpty() ?: false) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.sync_shelf_empty_title),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.sync_shelf_empty_desc),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.cloud_shelf_no_match),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(filtered, key = { it.davItem.path }) { item ->
                            WebDavBookCard(
                                item = item,
                                isDownloading = downloadingTitle == item.title,
                                onOpenLocal = {
                                    item.localBook?.let { onOpenBook(it.id) }
                                },
                                onDownload = {
                                    downloadingTitle = item.title
                                    scope.launch {
                                        android.widget.Toast.makeText(
                                            context,
                                            context.getString(R.string.sync_shelf_downloading),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                        webDavClient.downloadBookAndRestore(item.davItem, repo).fold(
                                            onSuccess = { importedBook ->
                                                downloadingTitle = null
                                                android.widget.Toast.makeText(
                                                    context,
                                                    context.getString(R.string.sync_shelf_download_success),
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                                onOpenBook(importedBook.id)
                                            },
                                            onFailure = { e ->
                                                downloadingTitle = null
                                                android.widget.Toast.makeText(
                                                    context,
                                                    context.getString(R.string.webdav_download_failed_fmt, e.message ?: ""),
                                                    android.widget.Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        )
                                    }
                                },
                                onLongClick = { deleteTarget = item },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSyncSettings) {
        SyncSettingsDialog(
            onDismiss = {
                showSyncSettings = false
                configVersion++
            },
            onUpload = { onResult ->
                if (webDavClient.isConfigured()) {
                    viewModel.uploadAllToWebDav(context, webDavClient)
                    onResult(context.getString(R.string.sync_webdav_uploading))
                } else {
                    onResult(context.getString(R.string.webdav_not_configured))
                }
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
            title = { Text(stringResource(R.string.sync_shelf_delete_title)) },
            text = {
                Text(
                    stringResource(R.string.sync_shelf_delete_confirm, target.title),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val toDelete = target
                    deleteTarget = null
                    scope.launch {
                        webDavClient.deleteBookWithMetadata(toDelete.davItem.path).fold(
                            onSuccess = {
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.sync_shelf_deleted),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                                reload()
                            },
                            onFailure = { e ->
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.sync_fail, e.message ?: ""),
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        )
                    }
                }) {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** 云端网盘书籍卡片：封面 + 书名 + 本地已有徽章 + 下载转圈 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WebDavBookCard(
    item: WebDavBookDisplay,
    isDownloading: Boolean,
    onOpenLocal: () -> Unit,
    onDownload: () -> Unit,
    onLongClick: () -> Unit,
) {
    val isOnDevice = item.localBook != null
    val localCoverFile = item.localBook?.coverPath?.let { File(it) }?.takeIf { it.exists() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (!isDownloading) {
                        if (isOnDevice) onOpenLocal() else onDownload()
                    }
                },
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (localCoverFile != null) {
                    AsyncImage(
                        model = localCoverFile,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    // 无封面时的优雅书籍占位
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Book,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = item.title,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            lineHeight = 14.sp
                        )
                    }
                }

                // 右上角：本地已有角标
                if (isOnDevice) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF16A34A),
                    ) {
                        Text(
                            stringResource(R.string.sync_shelf_in_library),
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }

                // 下载中状态遮罩
                if (isDownloading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                    }
                }
            }

            // 底部书名
            Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
                Text(
                    text = item.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}
