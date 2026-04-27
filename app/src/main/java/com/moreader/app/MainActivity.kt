package com.moreader.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.moreader.app.data.BookRepository
import com.moreader.app.ui.LibraryScreen
import com.moreader.app.ui.ReaderScreen
import com.moreader.app.ui.theme.MoreaderTheme
import com.moreader.app.util.LocaleHelper

class MainActivity : ComponentActivity() {

    private lateinit var repository: BookRepository

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

        setContent {
            MoreaderTheme {
                MoreaderApp(repository, this@MainActivity)
            }
        }
    }
}

@Composable
fun MoreaderApp(repository: BookRepository, activity: ComponentActivity) {
    var currentBookId by remember { mutableStateOf<String?>(null) }

    if (currentBookId == null) {
        LibraryScreen(
            onOpenBook = { bookId -> currentBookId = bookId },
            repository = repository,
            onLanguageSwitch = {
                // Restart activity to apply new locale
                activity.recreate()
            },
        )
    } else {
        ReaderScreen(
            bookId = currentBookId!!,
            repository = repository,
            onBack = { currentBookId = null },
        )
    }
}
