# Moreader — EPUB Reader with TTS

A lightweight Android EPUB reader built with Kotlin + Jetpack Compose + WebView.

Originally migrated from a browser extension (墨阅 Moreader), reimagined as a native Android app for Google Play and personal use.

## Features

- **EPUB Reading** — Renders EPUB content via WebView with customizable fonts, themes, and layout
- **Text-to-Speech** — Two TTS engines:
  - **Edge TTS** (online, 100+ voices across locales, gender icons)
  - **AI Voice** (OpenAI-compatible API)
- **Chapter Navigation** — Side drawer table of contents, prev/next chapter
- **Translation** — Select text for inline translation (AI-powered)
- **Progress Tracking** — Remembers reading position per book
- **Multi-language UI** — Chinese / English toggle (🌐 button in library)
- **Debug Panel** — Bottom highlight offset slider for fine-tuning TTS sync

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Database**: Room (SQLite via KSP)
- **EPUB Parsing**: Cooperative WebView with asset-based rendering
- **TTS**: HTTP-based Edge TTS client + OpenAI-compatible API
- **Build**: Gradle 8.8.2, Kotlin 2.1.10, Target SDK 36 (Android 16)

## Project Structure

```
app/src/main/java/com/moreader/app/
├── MainActivity.kt              # Entry point, language switching via attachBaseContext
├── LibraryScreen.kt              # Book library grid UI
├── LibraryViewModel.kt           # Book management logic
├── ReaderScreen.kt               # Reading screen UI (WebView, TTS controls, ToC)
├── ReaderViewModel.kt            # TTS orchestration, chapter loading, highlight sync
├── ReaderViewModelFactory.kt     # ViewModel factory with dependency injection
├── data/
│   ├── BookDao.kt                # Room DAO
│   ├── BookDatabase.kt           # Room database
│   ├── BookRepository.kt         # EPUB import, parsing, cover extraction
│   └── models/Models.kt          # Data models (Book, ReaderTheme, TTSProviderType, etc.)
├── reader/
│   └── EpubWebView.kt            # WebView integration for EPUB rendering
├── tts/
│   ├── TTSProvider.kt            # TTS interface
│   ├── EdgeTTSProvider.kt        # Microsoft Edge TTS implementation
│   ├── AIVoiceTTSProvider.kt     # AI API TTS implementation
│   └── SystemTTSProvider.kt      # (Stub) System TTS
├── translate/
│   └── TranslationService.kt     # AI-powered text translation
├── ui/
│   ├── components/
│   │   ├── TtsSettingsSheet.kt   # TTS settings bottom sheet (voices, speed, provider)
│   │   └── EdgeVoiceData.kt      # 100+ Edge TTS voice definitions
│   └── theme/
│       └── Theme.kt              # Material 3 theme
└── util/
    └── LocaleHelper.kt           # Language switch support (attachBaseContext)
```

## Building

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Min Requirements

- Android 8.0 (API 26) or higher
- Internet connection for TTS and translation features

## License

Private project — all rights reserved.
