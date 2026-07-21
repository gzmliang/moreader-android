package com.moyue.app.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class Book(
    @PrimaryKey val id: String,
    val title: String,
    val author: String = "Unknown",
    val coverPath: String? = null,       // path to cached cover image
    val filePath: String,                // path to cached EPUB file
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long? = null,
    val currentChapterHref: String? = null,  // spine href
    val currentChapterIndex: Int = 0,
    val currentProgress: Float = 0f,         // 0.0 ~ 1.0
    val currentCfi: String? = null,          // CFI reference for more precise position
    val currentParagraphIndex: Int = 0,      // paragraph index within chapter
    val themeId: String = "default",         // last used reading theme
    val fontSize: Int = 18,                  // last used font size
    // Per-book TTS config (empty = use global default)
    val ttsProvider: String = "",            // "EDGE_TTS", "AI_VOICE", "SYSTEM", "CUSTOM_TTS"
    val ttsVoice: String = "",               // last used voice name
    val ttsSpeed: Float = 1.0f,              // per-book TTS speed
    val textBrightness: Int = 100,            // 0-100 text brightness (for dark themes, 100=original)
)

// TOC entry
data class TocEntry(
    val label: String,
    val href: String,
    val level: Int = 0,
)

// Chapter in EPUB spine
data class Chapter(
    val id: String,
    val href: String,
    val index: Int,
)

// Reading theme
enum class ReaderTheme(
    val id: String,
    val bgColor: String,        // WebView background
    val textColor: String,      // WebView text
    val linkColor: String,      // for anchor tags
    val isDark: Boolean,
) {
    LIGHT("default", "#FFFFFF", "#1A1A1A", "#0066CC", false),
    PARCHMENT("parchment", "#F4ECD8", "#332D22", "#8B6914", false),
    GRAY("gray", "#E5E5E5", "#222222", "#0055BB", false),
    DARK("dark", "#1A1A1A", "#CCCCCC", "#66B3FF", true),
    SLATE("slate", "#2C3E50", "#CCCCCC", "#66B3FF", true),
}

// TTS provider types
enum class TTSProviderType {
    SYSTEM,
    EDGE_TTS,
    AI_VOICE,
    CUSTOM_TTS,  // OpenAI-compatible custom endpoint (e.g. MOSS-TTS-Nano)
}

// Translation engine: cloud API vs local AI
enum class TranslateEngine {
    CLOUD,  // Cloud LLM API (TranslationService)
    LOCAL,  // On-device llama.cpp (LocalAiEngine)
}

// AI translation config
data class LLMConfig(
    val provider: String = "siliconflow",
    val apiKey: String = "",
    val endpoint: String = "https://api.siliconflow.cn/v1",
    val model: String = "Qwen/Qwen2.5-72B-Instruct",
)
