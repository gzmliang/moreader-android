package com.moyue.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.moyue.app.data.BookRepository
import com.moyue.app.ui.BookmarksScreen
import com.moyue.app.ui.FlashcardScreen
import com.moyue.app.ui.LibraryScreen
import com.moyue.app.ui.ReaderScreen
import com.moyue.app.ui.VocabularyScreen
import com.moyue.app.ui.theme.MoreaderTheme
import com.moyue.app.util.LocaleHelper
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : ComponentActivity() {

    private lateinit var repository: BookRepository
    private var sharedUris: List<Uri> = emptyList()

    companion object {
        private const val TAG = "CrashHandler"
    }

    // Override attachBaseContext to wrap with our locale — this is the
    // standard Android approach that works reliably on all API levels.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Install crash handler — writes crash log to file so user can copy on next launch
        val crashLogFile = File(getCacheDir(), "crash_log.txt")
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                crashLogFile.writeText("Thread: ${thread.name}\n\n${sw.toString()}")
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        repository = BookRepository(applicationContext)

        // Handle shared files from other apps
        handleIntent(intent)

        // Check for previous crash log and pass to Compose
        val previousCrash = if (crashLogFile.exists()) {
            runCatching { crashLogFile.readText() }.getOrNull()
        } else null
        previousCrash?.let { crashLogFile.delete() }

        // Clean up old fetch logs silently
        val oldFetchLog = File(getCacheDir(), "flashcard_fetch_log.txt")
        if (oldFetchLog.exists()) oldFetchLog.delete()

        setContent {
            MoreaderTheme {
                MoreaderApp(repository, this@MainActivity, sharedUris, previousCrash) { sharedUris = emptyList() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                // Single file shared
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                    sharedUris = listOf(uri)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                // Multiple files shared
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris ->
                    sharedUris = uris
                }
            }
            Intent.ACTION_VIEW -> {
                // File opened from file manager
                intent.data?.let { uri ->
                    sharedUris = listOf(uri)
                }
            }
        }
    }
}

sealed class Screen {
    data object Library : Screen()
    data class Reader(val bookId: String) : Screen()
    data object Bookmarks : Screen()
    data object Vocabulary : Screen()
    data object Flashcards : Screen()
}

@Composable
fun MoreaderApp(
    repository: BookRepository,
    activity: ComponentActivity,
    sharedUris: List<Uri> = emptyList(),
    previousCrash: String? = null,
    onSharedUrisConsumed: () -> Unit = {},
) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Library) }
    var showCrashDialog by remember { mutableStateOf(previousCrash != null) }
    var crashText by remember { mutableStateOf(previousCrash ?: "") }

    // Bookshelf independent dark/light theme (separate from reader settings)
    val context = LocalContext.current
    val bookshelfPrefs = remember { context.getSharedPreferences("moreader_prefs", Context.MODE_PRIVATE) }
    var bookshelfDark by remember { mutableStateOf(bookshelfPrefs.getBoolean("bookshelf_dark", false)) }

    MoreaderTheme(
        darkTheme = when (currentScreen) {
            is Screen.Library -> bookshelfDark
            else -> isSystemInDarkTheme()
        }
    ) {
        when (val screen = currentScreen) {
            is Screen.Library -> {
                LibraryScreen(
                    onOpenBook = { bookId -> currentScreen = Screen.Reader(bookId) },
                    onOpenBookmarks = { currentScreen = Screen.Bookmarks },
                    onOpenVocabulary = { currentScreen = Screen.Vocabulary },
                    onOpenFlashcards = { currentScreen = Screen.Flashcards },
                    repository = repository,
                    sharedUris = sharedUris,
                    onSharedUrisConsumed = onSharedUrisConsumed,
                    onLanguageSwitch = {
                        activity.recreate()
                    },
                    bookshelfDark = bookshelfDark,
                    onToggleBookshelfDark = {
                        val newVal = !bookshelfDark
                        bookshelfDark = newVal
                        bookshelfPrefs.edit().putBoolean("bookshelf_dark", newVal).apply()
                    },
                )
            }
            is Screen.Reader -> {
                ReaderScreen(
                    bookId = screen.bookId,
                    repository = repository,
                    onBack = { currentScreen = Screen.Library }
                )
            }
            is Screen.Bookmarks -> {
                BookmarksScreen(
                    repository = repository,
                    onBack = { currentScreen = Screen.Library },
                    onNavigateToBookmark = { bookId, chapterIndex, progress ->
                        currentScreen = Screen.Reader(bookId)
                    }
                )
            }
            is Screen.Vocabulary -> {
                VocabularyScreen(
                    repository = repository,
                    onBack = { currentScreen = Screen.Library }
                )
            }
            is Screen.Flashcards -> {
                FlashcardScreen(
                    repository = repository,
                    onBack = { currentScreen = Screen.Library }
                )
            }
        }

        // Show crash dialog from previous session
        if (showCrashDialog && crashText.isNotEmpty()) {
            CrashDialog(
                errorText = crashText,
                onDismiss = { showCrashDialog = false }
            )
        }
    }
}

@Composable
private fun CrashDialog(errorText: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💥", fontSize = 24.sp)
                Spacer(Modifier.width(8.dp))
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.crash_dialog_title), color = Color(0xFFD32F2F))
            }
        },
        text = {
            Column {
                Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.crash_dialog_message), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 250.dp)
                        .background(Color(0xFFF5F5F5), shape = MaterialTheme.shapes.small)
                ) {
                    LazyColumn(modifier = Modifier.padding(8.dp)) {
                        item {
                            Text(errorText, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = Color(0xFF333333))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(errorText))
                        copied = true
                    }
                ) {
                    Icon(Icons.Default.CopyAll, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (copied) androidx.compose.ui.res.stringResource(com.moyue.app.R.string.crash_dialog_copied) else androidx.compose.ui.res.stringResource(com.moyue.app.R.string.crash_dialog_copy))
                }
                Button(onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(com.moyue.app.R.string.close)) }
            }
        }
    )
}
