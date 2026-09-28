# Moreader — EPUB Reader with TTS

A lightweight Android EPUB reader built with Kotlin + Jetpack Compose + WebView.

Originally migrated from a browser extension (墨阅 Moreader), reimagined as a native Android app for Google Play and personal use.

## Features

- **Cloud Library with Cover Grid (v1.1.0)** — A dedicated cloud shelf page: 3-column cover grid, instant search, "on device" badges, one-tap download (bookmarks/highlights/progress restored) and long-press to remove. Covers are extracted automatically from each uploaded EPUB on the server (5-level fallback) and cached on both ends, so reopening is instant.
- **PDF → Reading Edition (v1.1.0)** — Import a text-layer PDF and it is converted on the server into a standard EPUB (chapter detection reads the PDF outline when available), with a page-1 cover rendered in. The result is an ordinary book, so **TTS, sentence highlighting, bookmarks, highlights, vocabulary, AI summary, AI character graph and AI quizzes all work with no extra steps**. Scanned image-only PDFs are detected and reported instead of silently producing an empty book.
- **Background PDF Jobs with Real Progress (v1.1.1)** — Large/scanned PDFs are converted by a cloud job queue instead of one long HTTP wait: the app shows a real percentage, page count and estimated time, can keep working in the background, resumes after the app is killed, and notifies when the book is on the shelf.
- **EPUB Reading** — Renders EPUB content via WebView with customizable fonts, themes, and layout
- **Footnote Preview & Precise Return-Jump (v2.9.44)** — 
  - Instant bottom sheet preview for both page-level and cross-chapter endnotes (no need to navigate away from reading text)
  - Automatic collision-avoidance between footnote popup and floating action chips
  - Bookmark-channel return jump with golden-halo badge highlight and soft blue paragraph highlight for 100% accurate visual focus restoration
- **AI Character Graph & Plot Network (v2.9.52)** —
  - Target-Centric relationship perspective constraint avoiding master-servant or seniority inversion
  - Dual-language relationship labels (`labelTranslation`) with localized display
  - Real-time character search by English/Chinese name or house/family
  - Automatic multi-relative split into independent capsules with accurate generational categorization
  - Resilient cache self-healing against missing fields and NPE
  - Fallback relationship cards ensuring seamless interaction without dead ends
- **Text-to-Speech** — Two TTS engines:
  - **Edge TTS** (online, 100+ voices across locales, gender icons)
  - **AI Voice** (OpenAI-compatible API)
- **Rock-solid TTS Sync (v1.0.3)** —
  - Sentence-level green highlight shares the *same* character ruler as the narration (clean-text → DOM coordinate map, rebuilt before every sentence), so the bar never drifts; cross-element sentences are wrapped for real instead of inserting an empty span
  - **Playback tokens + full in-flight cancellation**: an audio response that arrives after a cancel/pause is discarded and never spoken (no more two voices at once)
  - **"Generating audio, please wait…"** overlay with a ⏹ Cancel button; it disappears the instant audio starts, and every read-aloud entry point (play button / tap-paragraph / selection / whole chapter) goes through the same gate
  - Pinyin annotations (`<rt>/<rp>/<sup>/<sub>`) are stripped so narration never reads phonetic guides
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
