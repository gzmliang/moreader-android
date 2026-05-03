package com.moyue.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.moyue.app.data.BookRepository
import com.moyue.app.ui.BookmarksScreen
import com.moyue.app.ui.LibraryScreen
import com.moyue.app.ui.ReaderScreen
import com.moyue.app.ui.VocabularyScreen
import com.moyue.app.ui.theme.MoreaderTheme
import com.moyue.app.util.LocaleHelper
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: BookRepository
    private var sharedUris: List<Uri> = emptyList()

    // Override attachBaseContext to wrap with our locale — this is the
    // standard Android approach that works reliably on all API levels.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        repository = BookRepository(applicationContext)

        // Handle shared files from other apps
        handleIntent(intent)

        setContent {
            MoreaderTheme {
                MoreaderApp(repository, this@MainActivity, sharedUris) { sharedUris = emptyList() }
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
}

@Composable
fun MoreaderApp(repository: BookRepository, activity: ComponentActivity, sharedUris: List<Uri> = emptyList(), onSharedUrisConsumed: () -> Unit = {}) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Library) }

    when (val screen = currentScreen) {
        is Screen.Library -> {
            LibraryScreen(
                onOpenBook = { bookId -> currentScreen = Screen.Reader(bookId) },
                onOpenBookmarks = { currentScreen = Screen.Bookmarks },
                onOpenVocabulary = { currentScreen = Screen.Vocabulary },
                repository = repository,
                sharedUris = sharedUris,
                onSharedUrisConsumed = onSharedUrisConsumed,
                onLanguageSwitch = {
                    // Restart activity to apply new locale
                    activity.recreate()
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
    }
}
