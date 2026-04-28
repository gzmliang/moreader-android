package com.moreader.app.ui

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
import com.moreader.app.data.BookRepository
import com.moreader.app.data.models.Book
import com.moreader.app.util.LocaleHelper
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (String) -> Unit,
    repository: BookRepository,
    onLanguageSwitch: () -> Unit = {},
    sharedUris: List<Uri> = emptyList(),
    onSharedUrisConsumed: () -> Unit = {},
    viewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModelFactory(repository)
    ),
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
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

    // File picker for EPUB import (multiple files)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            viewModel.importBook(context, uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(androidx.compose.ui.res.stringResource(com.moreader.app.R.string.library_title), fontWeight = FontWeight.Bold)
                },
                actions = {
                    IconButton(onClick = {
                        importLauncher.launch(arrayOf("application/epub+zip"))
                    }) {
                        Icon(Icons.Default.Add, contentDescription = androidx.compose.ui.res.stringResource(com.moreader.app.R.string.import_book))
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
                                    "zh" -> androidx.compose.ui.res.stringResource(com.moreader.app.R.string.lang_zh)
                                    "en" -> androidx.compose.ui.res.stringResource(com.moreader.app.R.string.lang_en)
                                    else -> {
                                        // Show current system language
                                        val sys = java.util.Locale.getDefault().language
                                        if (sys == "zh") androidx.compose.ui.res.stringResource(com.moreader.app.R.string.lang_zh) else if (sys.startsWith("en")) androidx.compose.ui.res.stringResource(com.moreader.app.R.string.lang_en) else sys
                                    }
                                },
                                fontSize = 12.sp,
                            )
                        }

                        DropdownMenu(expanded = showLangMenu, onDismissRequest = { showLangMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (currentLang == "zh") "✓ " + androidx.compose.ui.res.stringResource(com.moreader.app.R.string.lang_zh) else androidx.compose.ui.res.stringResource(com.moreader.app.R.string.lang_zh), fontSize = 13.sp) },
                                onClick = {
                                    showLangMenu = false
                                    LocaleHelper.setSelectedLanguage(context, "zh")
                                    onLanguageSwitch()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (currentLang == "en") "✓ " + androidx.compose.ui.res.stringResource(com.moreader.app.R.string.lang_en) else androidx.compose.ui.res.stringResource(com.moreader.app.R.string.lang_en), fontSize = 13.sp) },
                                onClick = {
                                    showLangMenu = false
                                    LocaleHelper.setSelectedLanguage(context, "en")
                                    onLanguageSwitch()
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(if (currentLang == null) "✓ " + androidx.compose.ui.res.stringResource(com.moreader.app.R.string.follow_system) else androidx.compose.ui.res.stringResource(com.moreader.app.R.string.follow_system), fontSize = 13.sp) },
                                onClick = {
                                    showLangMenu = false
                                    LocaleHelper.setSelectedLanguage(context, null)
                                    onLanguageSwitch()
                                },
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (books.isEmpty()) {
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
                        androidx.compose.ui.res.stringResource(com.moreader.app.R.string.empty_library_hint),
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
                        Text(androidx.compose.ui.res.stringResource(com.moreader.app.R.string.import_book))
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(books, key = { it.id }) { book ->
                    BookCard(
                        book = book,
                        onClick = { onOpenBook(book.id) },
                        onDelete = { viewModel.deleteBook(context, book) },
                    )
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
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(androidx.compose.ui.res.stringResource(com.moreader.app.R.string.delete_book_title)) },
            text = { Text(androidx.compose.ui.res.stringResource(com.moreader.app.R.string.delete_book_confirm, book.title)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) { Text(androidx.compose.ui.res.stringResource(com.moreader.app.R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(androidx.compose.ui.res.stringResource(com.moreader.app.R.string.cancel)) }
            },
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showDeleteConfirm = true }
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
