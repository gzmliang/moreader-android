package com.moyue.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Color scheme
private val LightColors = lightColorScheme(
    primary = Color(0xFF1A1A2E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8EAF6),
    secondary = Color(0xFF3B82F6),
    onSecondary = Color.White,
    surface = Color(0xFFF7F7F7),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFE8E8E8),
    onSurfaceVariant = Color(0xFF444444),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1A1A),
    error = Color(0xFFEF4444),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FA8DA),
    onPrimary = Color(0xFF1A1A2E),
    primaryContainer = Color(0xFF2D2D44),
    secondary = Color(0xFF60A5FA),
    onSecondary = Color(0xFF1A1A2E),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFCCCCCC),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFAAAAAA),
    background = Color(0xFF121212),
    onBackground = Color(0xFFCCCCCC),
    error = Color(0xFFEF4444),
)

@Composable
fun MoreaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
