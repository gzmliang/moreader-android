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
import com.moyue.app.sync.SyncClient
import com.moyue.app.ui.components.SyncSettingsDialog
import com.moyue.app.util.LocaleHelper
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (String) -> Unit,
    onOpenBookmarks: () -> Unit = {},
    onOpenVocabulary: () -> Unit = {},
    onOpenFlashcards: () -> Unit = {},
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
    val context = LocalContext.current

    // Handle shared files from other apps
    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) {
            sharedUris.forEach { uri ->
                viewModel.importBook(context, uri)
            }
            onSharedUrisConsumed()
        }
    }

    // 回到书架时，如果 searchQuery 非空则自动激活搜索框
    LaunchedEffect(Unit) {
        if (searchQuery.isNotBlank() && !isSearchActive) {
            viewModel.setSearchActive(true)
        }
    }

    // File picker for EPUB import (multiple files)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            viewModel.importBook(context, uri)
        }
    }

    // App dark mode toggle for UI screens (independent of reader theme)
    var isAppDark by remember { mutableStateOf(com.moyue.app.ui.theme.getDarkModePreference(context) ?: false) }

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
                        Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.library_title), fontWeight = FontWeight.Bold)
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!isSearchActive) {
                            IconButton(onClick = { viewModel.setSearchActive(true) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                            }
                        }
                        // Dark/Light mode toggle for app UI
                        IconButton(onClick = {
                            val newDark = !isAppDark
                            isAppDark = newDark
                            com.moyue.app.ui.theme.saveDarkModePreference(context, newDark)
                            (context as? androidx.activity.ComponentActivity)?.recreate()
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                if (isAppDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.dark_mode_toggle),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        IconButton(onClick = onOpenBookmarks, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Bookmark, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.bookmark_list_title), modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = onOpenVocabulary, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.MenuBook, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.vocabulary_title), modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = onOpenFlashcards, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Bolt, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.flashcard_title), modifier = Modifier.size(20.dp))
                        }
                        // Upload all to cloud button (only when logged in)
                        val uploadScope = rememberCoroutineScope()
                        val syncClientForUpload = remember { SyncClient(context) }
                        if (syncClientForUpload.isLoggedIn()) {
                            var showUploadAllConfirm by remember { mutableStateOf(false) }
                            IconButton(onClick = { showUploadAllConfirm = true }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.CloudUpload, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_upload_all_desc),
                                    modifier = Modifier.size(20.dp))
                            }
                            if (showUploadAllConfirm) {
                                AlertDialog(
                                    onDismissRequest = { if (!isUploading) showUploadAllConfirm = false },
                                    title = { Text(if (isUploading) androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_uploading) else androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_upload_all_title)) },
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
                                            Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_upload_all_confirm))
                                        }
                                    },
                                    confirmButton = {
                                        if (!isUploading) {
                                            TextButton(onClick = {
                                                val client = SyncClient(context)
                                                uploadScope.launch {
                                                    viewModel.uploadAllToCloud(context, client)
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
                        }
                        IconButton(onClick = {
                            importLauncher.launch(arrayOf("application/epub+zip"))
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Add, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.import_book), modifier = Modifier.size(20.dp))
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

                    // Sync settings
                    var showSyncSettings by remember { mutableStateOf(false) }
                    IconButton(onClick = { showSyncSettings = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Cloud, contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_cloud_icon),
                            modifier = Modifier.size(18.dp))
                    }
                    if (showSyncSettings) {
                        val syncClientForSync = remember { SyncClient(context) }
                        SyncSettingsDialog(
                            syncClient = syncClientForSync,
                            onDismiss = { showSyncSettings = false },
                            onUpload = { onResult ->
                                viewModel.uploadToCloud(context,
                                    syncClientForSync, onResult)
                            },
                            onDownload = { onResult ->
                                viewModel.downloadFromCloud(context,
                                    syncClientForSync, onResult)
                            },
                        )
                    }
                }
            }
        }
    ) { padding ->
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
                        onClick = { importLauncher.launch(arrayOf("application/epub+zip")) },
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
                // 搜索结果显示提示
                if (searchQuery.isNotBlank()) {
                    Text(
                        "搜索 \"$searchQuery\" · 找到 ${mergedItems.size} 本",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                    )
                }
                LazyVerticalGrid(
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
                        CloudOnlyBookCard(
                            title = item.title,
                            author = item.author,
                            onDownload = {
                                item.cloudInfo?.let { info ->
                                    viewModel.downloadCloudBook(context, syncClient, info)
                                }
                            },
                        )
                    } else {
                        val book = item.localBook!!
                        BookCard(
                            book = book,
                            onClick = { onOpenBook(book.id) },
                            onDelete = { viewModel.deleteBook(context, book) },
                            onUploadToCloud = {
                                val client = SyncClient(context)
                                viewModel.uploadSingleBook(context, client, book.id)
                            },
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
    onUploadToCloud: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }

    if (showMenu) {
        AlertDialog(
            onDismissRequest = { showMenu = false },
            title = { Text(book.title) },
            text = { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.select_action)) },
            confirmButton = {
                TextButton(onClick = { showMenu = false; onUploadToCloud() }) {
                    Icon(Icons.Default.CloudUpload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_upload_to_cloud))
                }
            },
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

/** 云端独有书籍卡片（浅色/半透明） */
@Composable
private fun CloudOnlyBookCard(
    title: String,
    author: String,
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
            // Cover placeholder — 云朵图标表示未下载
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                if (isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    )
                } else {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = androidx.compose.ui.res.stringResource(com.moyue.app.R.string.sync_download),
                        modifier = Modifier.size(32.dp),
                        tint = Color.Gray.copy(alpha = 0.5f),
                    )
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
