# 墨阅 Moreader Android 手机端项目总文档

> 本文档是墨阅 Android 手机端的开发总览，面向后续维护、功能开发、故障排查以及交给 Pi 编程助手执行任务。
>
> **资料基线：** 2026-09-04，源码 `main` 当前提交 `e6eca26`，版本 `v2.9.25`，`versionCode=20111`。
> **源码优先级：** 当前 GitHub `main` / 本地同步源码 > 本文档 > 旧 README、旧 DOCX、旧技能说明。

## 1. 项目身份与边界

- 产品：墨阅 Moreader，Android EPUB 阅读器。
- GitHub：<https://github.com/gzmliang/moreader-android>
- 本地唯一 canonical 目录：`/root/.hermes/projects/moreader-android/`
- GitHub 用户名：`gzmliang`；不要与 `gzmliang/moreader` 浏览器扩展、墨笺 Mojian、畅说 SpeakRead 混淆。
- Android 包：`com.moyue.app.tingshu`。
- Kotlin namespace：`com.moyue.app`。**namespace 不要改**，否则 `R` 类引用会大量失败。
- 主要用途：个人阅读、英语学习、EPUB 朗读、选词翻译、词汇积累和跨设备阅读数据同步。
- 当前实现是 **Native Android + Jetpack Compose + WebView**，不是 Capacitor，也不是纯原生 Compose EPUB 渲染。

### 当前真实基线

```text
HEAD / origin/main: e6eca26
版本名: 2.9.25
versionCode: 20111
最近提交: TTS设置面板重构：试听按钮复用真实TTSProvider实例；EdgeTTS移除NO_PROXY走默认代理
目标 SDK: 36
最低 SDK: 26
Java/Kotlin JVM: 17
```

旧技能文档仍写着 v2.9.24 / versionCode 20110，旧 README 和两个 DOCX 仍写 v1.3.3；这些内容只能作为历史资料，不能作为当前实现依据。

## 2. 产品功能地图

### 2.1 书架

- 导入一个或多个 EPUB 文件。
- 支持从文件管理器或其他应用分享 EPUB 到墨阅。
- 网格书架显示封面、书名、作者和阅读进度。
- 长按删除书籍。
- 进入书签列表、词汇本。
- 中英文界面切换；通过 `LocaleHelper` 包装 Context，切换后重建 Activity。
- 书架数据由 Room `BookDao`/`BookRepository` 提供 Flow。

### 2.2 阅读器

- EPUB 章节解析、目录解析、章节前后切换。
- WebView 加载章节 HTML，尽量保留 EPUB 原排版。
- 字体大小 14–28。
- 五种阅读主题：浅色、羊皮纸、灰色、深色、石板色；暗色主题支持文字亮度调节。
- 全屏阅读。
- 章节目录抽屉。
- 书签面板。
- 点击段落可从当前位置开始朗读或添加书签。
- 记忆阅读章节、段落和百分比。
- 阅读进度条可拖动到不同章节。

### 2.3 文字选择工具

选择文字后支持：

- 加入生词本。
- 添加/取消黄色高亮。
- 朗读选中文本。
- AI 翻译。
- AI 解释。
- 语法分析。

选区数据包含原文、起始段落、起始字符偏移、结束段落和结束字符偏移。偏移必须基于 WebView DOM 的原始文本，不得使用已经清理过的 TTS 文本计算用户高亮位置。

### 2.4 生词本

- Room 保存词汇数据。
- 支持多个生词本/Notebook，当前词本通过 SharedPreferences 记录，词条的 `plan` 存 Room。
- 保存单词、音标、词性、双语释义、词形、例句等结构化信息。
- 词条发音复用配置的 TTS 引擎。
- 重新拉取 AI 释义。
- 导出 Markdown / CSV。
- 内置 ECDICT SQLite 词典和汉字词典，可优先离线查询英文短词。

### 2.5 闪卡

- 闪卡复习界面和复习状态。
- Flashcard 数据目前使用 JSON 文件存储，不是 Room。
- `FlashcardViewModel` 管理复习计划、词条导入、AI 定义和发音。

### 2.6 云同步

同步不是“智能合并”，而是两个明确操作：

- **上传到云端（Push）：** 本地当前数据覆盖云端。
- **从云端下载（Pull）：** 云端当前数据覆盖本地，阅读进度取较大值。

这样才能让删除操作跨设备传播，避免“本地删了、下一次同步又回来”。详见第 7 节。

## 3. 技术栈与构建配置

### 3.1 技术栈

- Kotlin 2.1.10。
- Android Gradle Plugin 8.8.2。
- Jetpack Compose + Material 3。
- Room 2.6.1 + KSP。
- WebView：EPUB 章节 HTML 渲染、JavaScript 桥接和 DOM 高亮。
- Jsoup 1.19.1：EPUB HTML 解析。
- OkHttp 4.12.0：TTS、翻译、同步网络请求。
- Kotlin Coroutines 1.10.1。
- Gson 2.12.1。
- Coil 3.1.0：封面加载。
- AndroidX Navigation Compose、Lifecycle Compose、DocumentFile、SplashScreen。
- 可选本地 AI：llama.cpp + JNI + Vulkan；当前构建配置中的 externalNativeBuild 被禁用时，相关功能必须安全降级。

### 3.2 Gradle 关键值

```kotlin
android {
    namespace = "com.moyue.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.gzmliang.moreader"
        minSdk = 26
        targetSdk = 36
        versionCode = 20111
        versionName = "2.9.25"
    }
}
```

签名使用项目根目录的 `moreader.keystore`。签名配置和 keystore 不得随意更改；改签名会造成已安装 App 无法覆盖升级。

当前 `app/build.gradle.kts` 中 native CMake 配置被注释，虽然仓库仍包含 `app/src/main/cpp/llama-local-ai.cpp` 和 CMake 文件。不要把“源码存在”误判为“当前 APK 已启用本地 native AI”。

### 3.3 构建

```bash
cd /root/.hermes/projects/moreader-android
./gradlew assembleDebug
```

当前带 product flavor/variant 时，优先检查实际产物路径，不要凭旧文档假定一定是 `app-debug.apk`：

```bash
find app/build/outputs/apk -type f -name '*.apk' -printf '%p %s bytes\n'
```

历史资料曾使用 `app/build/outputs/apk/moyue/debug/app-moyue-debug.apk`，当前项目必须以 Gradle 实际输出为准。

## 4. 源码目录与职责

```text
app/src/main/java/com/moyue/
├── MainActivity.kt                 # Activity 入口、Intent、Screen 状态导航
├── LibraryScreen.kt                # 书架 Compose UI、EPUB 导入、语言切换
├── LibraryViewModel.kt             # 书籍 Flow、导入、封面和删除
├── ReaderScreen.kt                 # 阅读器 Compose UI、工具栏、WebView、面板
├── ReaderViewModel.kt              # 阅读状态、章节、TTS、书签、高亮、翻译
├── ReaderViewModelFactory.kt       # ReaderViewModel 工厂
├── reader/
│   └── EpubWebView.kt              # WebView 渲染、JS 桥、段落/句子/用户高亮
├── data/
│   ├── BookDao.kt                  # 书籍 DAO
│   ├── BookmarkDao.kt              # 书签 DAO
│   ├── HighlightDao.kt             # 高亮 DAO
│   ├── VocabularyDao.kt            # 生词 DAO
│   ├── BookDatabase.kt              # Room Database、版本和迁移注册
│   ├── Migrations.kt               # Room schema migrations
│   ├── BookRepository.kt           # EPUB 文件、解析、Room 统一访问层
│   ├── FlashcardDataStore.kt       # 闪卡 JSON 数据
│   └── models/
│       ├── Models.kt               # Book、Chapter、TocEntry、ReaderTheme 等
│       ├── Bookmark.kt
│       ├── Highlight.kt
│       ├── Vocabulary.kt
│       └── DictionaryEntry.kt
├── tts/
│   ├── TTSProvider.kt              # Provider 接口、监听器、边界数据
│   ├── EdgeTTSProvider.kt          # Edge TTS HTTP 客户端
│   ├── SystemTTSProvider.kt        # Android TextToSpeech
│   ├── AIVoiceTTSProvider.kt       # OpenAI 兼容 AI Voice
│   ├── CustomTTSProvider.kt        # 自定义 OpenAI 兼容 TTS
│   └── TtsRecorder.kt              # TTS 录音/MP3 拼接
├── translate/
│   └── TranslationService.kt       # 翻译、解释、语法分析和 SSE
├── localai/
│   ├── DictionaryEngine.kt         # 预打包 SQLite 词典查询
│   ├── LocalAiEngine.kt            # llama.cpp 本地模型封装
│   └── LlamaJniWrapper.kt          # JNI 加载与可用性守卫
├── ui/
│   ├── BookmarksScreen.kt
│   ├── BookmarksViewModel.kt
│   ├── VocabularyScreen.kt
│   ├── VocabularyViewModel.kt
│   ├── FlashcardScreen.kt
│   ├── FlashcardViewModel.kt
│   └── components/
│       ├── TtsSettingsSheet.kt     # TTS、主题、亮度和试听设置
│       ├── VoicePickerDialog.kt    # Edge 音色搜索、分类、试听
│       ├── EdgeVoiceData.kt        # 音色清单
│       ├── RecordingScreen.kt      # 录音 UI
│       └── SyncSettingsDialog.kt   # Push/Pull 同步 UI
└── util/
    └── LocaleHelper.kt             # 语言选择和 Context 包装
```

当前 tracked 文件约 84 个，其中 Kotlin 44 个、XML 12 个；源码及资源统计约 17,576 行（包含 Kotlin/Java/XML/C++，不含依赖目录）。`llama.cpp` 是仓库内目录但没有被 `git ls-files` 作为普通文件计入，统计时要排除生成物和依赖目录。

## 5. 核心数据流

### 5.1 Activity 与页面

`MainActivity` 创建 `BookRepository`，根据 `Screen` sealed class 在四个页面间切换：

```text
MainActivity
  └── MoreaderApp
       ├── Library
       ├── Reader(bookId)
       ├── Bookmarks
       └── Vocabulary
```

文件分享 Intent（`ACTION_VIEW`、`ACTION_SEND`、`ACTION_SEND_MULTIPLE`）被转换成 Uri，传入 `LibraryScreen`，再交给 `LibraryViewModel.importBook()`。

### 5.2 EPUB 导入与阅读

```text
文件 Uri
  → BookRepository.importBook()
  → 复制到 App 私有存储
  → 解析 EPUB 元数据/OPF/Spine/TOC
  → Room 保存 Book
  → 提取封面
  → ReaderViewModel.loadBook(bookId)
  → parseSpine + parseToc
  → getChapterContent()
  → Jsoup 提取 TTS 段落
  → EpubWebView 加载 HTML
```

TTS 段落列表必须和 WebView 的 `p,h1...h6` DOM 节点索引对齐。清理拼音、脚注、装饰符号时只能做文本级清理，不能删除 DOM 元素后再重新编号，否则阅读位置和高亮会漂移。

### 5.3 阅读位置

阅读位置至少涉及：

- `currentChapterIndex`
- `currentChapterHref`
- `currentParagraphIndex`
- 当前章节百分比
- 主题、字体大小

章节跳转时要把段落位置重置为 0，并防止 WebView 加载过渡期的 scroll 事件把旧位置写回数据库。目录跳转、上一章、下一章、书签跳转必须共用一致的状态更新原则。

## 6. TTS 架构

### 6.1 统一接口

`TTSProvider.kt` 定义：

- `speak(text, rate, listener)`：实时朗读。
- `playRaw(audioData, listener)`：播放预加载音频。
- `stop()`、`destroy()`。
- `isSpeaking`、`currentPositionMs`、`type`。
- `TTSListener.onStart/onDone/onError`。
- System TTS 的 `onRange(start,end)`。
- Edge TTS 的 `onWordBoundaries(boundaries)`。

`WordBoundary` 包含：音频起始毫秒、词文本、原文字符起止偏移。`PreloadResult` 同时保存音频和词边界。

### 6.2 引擎

| 引擎 | 实现 | 词/句边界 | 适用 |
|---|---|---|---|
| Edge TTS | `EdgeTTSProvider` | 服务端返回 WordBoundary，最精确 | 主要阅读朗读 |
| System TTS | `SystemTTSProvider` | Google 等引擎可通过 `onRangeStart` | 离线/系统语音 |
| AI Voice | `AIVoiceTTSProvider` | 通常没有边界，估算降级 | AI 语音实验 |
| Custom TTS | `CustomTTSProvider` | 通常没有边界，估算降级 | OpenAI 兼容 TTS |
| Local ONNX/本地 AI | 相关本地模块 | 视实现而定 | 离线探索，不要默认可用 |

### 6.3 Edge TTS 端点

- `/tts`：普通 MP3，适合单句/预加载。
- `/tts_with_boundaries`：MP3 + `X-Word-Boundaries`，适合多句实时同步。
- `/tts_boundaries_only`：仅边界，历史上用于后补，目前不是主路径。

服务端实际位于 192.168.199.159:5001，外部 HTTPS 入口和备用服务另见服务器维护资料。Edge TTS 服务器使用 `boundary='WordBoundary'`，否则 Edge 库默认可能只产生 SentenceBoundary。

**网络代理规则：**

- 公网域名：不要在 OkHttp 强制 `NO_PROXY`，交给系统网络栈，避免手机优先走不可达 IPv6。
- 私有 LAN IP（如 192.168.199.x:8880）：需要 `Proxy.NO_PROXY`，避免手机代理劫持 LAN 请求。
- 不要把这两个场景一刀切。

### 6.4 精确句子高亮

当前正确设计：

```text
Edge TTS word boundaries
  → onWordBoundaries 暂存 pendingBoundaries
  → onStart 计算完整文本中的 sentenceEnds
  → 按服务端 offsetMs delay 调度句子索引
  → Kotlin 状态流传给 EpubWebView
  → JS TreeWalker + Range 只包裹当前句子
```

重要规则：

1. Kotlin 的句子正则必须包含中英文标点：`. ! ? 。 ！ ？ ； ;`。
2. 句子边界必须在完整原文中用 `indexOf` 定位，不能把 `split()` 后的长度简单累加。
3. `onWordBoundaries` 可能早于 `onStart`，必须有 `pendingBoundaries`。
4. Edge 的 `onStart` 时序不能随意移动到 `MediaPlayer.onPrepared` 后面；当前已验证的同步方案依赖既定时序。
5. 句子高亮最终边界以 Kotlin 计算结果为准，JS 不要重复切句，避免 DOM textContent 和 Kotlin 文本差异造成 1–3 个字符漂移。
6. AI Voice/Custom TTS 无边界时才使用字符比例估算，中文和英文速度不能共用一个硬编码值。
7. 播放链结束时不要过早清除最后一句高亮。

### 6.5 长段落

已采用折中方案：仅对超过约 200 字的长段落拆成 2–3 个 150–250 字子段，保留上下文，不把全文切成单句，以免 Edge TTS 失去语气连贯性。子段词边界要通过字符偏移映射回原段落。

短段落走原有路径；服务器不需要因此改成 WebSocket。不要重新引入“大炮打蚊子”的流式 WebSocket 方案，除非用户明确重新讨论并批准。

### 6.6 朗读缓存

- 当前段落优先播放。
- 后续若干段后台预加载。
- 网络预加载只能在 `Dispatchers.IO`，因为 OkHttp `execute()` 是同步阻塞。
- 不能在主线程使用 `runBlocking + execute()`。
- 播放缓存音频时也要保留边界数据，否则会退化成估算高亮。
- 错误连续达到阈值后停止播放链，禁止无限重试。

### 6.7 TTS 试听

设置页试听必须复用真实的 Provider 实例，不要在 Compose UI 里另写一套 OkHttp + MediaPlayer。当前最新提交已把试听架构重构为共用真实 TTSProvider；这是为了让试听和正式朗读使用相同的网络、超时、端点和错误处理。

## 7. Room、词典与同步

### 7.1 Room

主要实体：

- `Book`
- `Bookmark`
- `Highlight`
- `Vocabulary`

DAO 由 `BookRepository` 统一暴露给 ViewModel。增加 Entity 字段时必须同时：

1. 增加 Room schema migration。
2. 提升 `BookDatabase` 版本号。
3. 把 migration 注册到 `.addMigrations(...)`。
4. 构建并验证旧数据库升级。

`fallbackToDestructiveMigration()` 不能替代规范 migration；同版本 schema hash 不匹配时也不能指望它自动救场。

### 7.2 离线词典

资源：

- `app/src/main/assets/dictionary.db`，ECDICT，约 84 MB，约 77 万英文词条。
- `app/src/main/assets/hanzi_dict.db`，约 1.8 MB。

短英文词优先 `DictionaryEngine.query()`，命中后毫秒级返回音标、释义和词形；未命中或输入为句子/中文时降级到 AI。资源数据库要保持不压缩/按 Gradle `aaptOptions` 配置处理。

### 7.3 Push/Pull 同步后端

服务端：

- Flask Blueprint `moreader_sync_bp.py`。
- 运行在 192.168.199.159:5001 的 edge-tts-server 进程中。
- SQLite：`~/.moreader_sync/moreader.db`。
- 表：users、books、reading_progress、bookmarks、highlights、browser_sync。

客户端：`sync/SyncClient.kt`，OkHttp + Coroutines，token 放 SharedPreferences。

端点：

```text
POST /sync/auth/login
GET  /sync/health
GET  /sync/books
POST /sync/books/upload
GET  /sync/books/<id>/download
DELETE /sync/books/<id>
GET  /sync/books/<id>/metadata
POST /sync/books/<id>/metadata
POST /sync/push
GET  /sync/pull
POST /sync/browser/push
GET  /sync/browser/pull
GET  /sync/auth/me
```

Push：每本书先删除该用户该书旧 bookmarks/highlights，再插入当前本地数据；同时清理 `browser_sync` 中旧数据。

Pull：拉取云端数据，按书名匹配本地书；阅读进度取大值；书签和高亮先删除本地该书数据，再写入云端数据。

跨平台同步时，浏览器端 Push 也必须清除安卓结构化表，否则 `/browser/pull` 会把安卓旧高亮合并回来。不要恢复旧的“追加式同步”或模糊 merge。

## 8. WebView 与 JavaScript 桥

### 8.1 桥接方向

```text
Android → evaluateJavascript()
JavaScript → MoreaderBridge @JavascriptInterface
```

重要函数/事件：

- `ttsHL(idx)`：段落高亮。
- `ttsClear()`：清理段落高亮。
- `initAndHighlight(paraIdx, sentenceIdx, sentenceEnds)`：段落切换、边界初始化和首句高亮。
- `ttsHLSentence(idx)`：句子切换。
- `ttsSentenceClear()`：清理绿色句子 span。
- `pageUp()` / `pageDown()`。
- `MoreaderBridge.onTextSelected(...)`。
- `MoreaderBridge.onParagraphClicked(idx)`。
- `MoreaderBridge.onLinkClicked(href)`。
- `MoreaderBridge.jsLog(msg)`：把 JS 诊断写入 Android 日志。

### 8.2 高亮实现原则

- TTS 段落高亮：蓝色。
- TTS 当前句高亮：绿色。
- 用户高亮：黄色。
- 句子高亮用 TreeWalker + Range 原位包裹，优先 `splitText` + `surroundContents`，不要用 `innerHTML` 重建整段 DOM。
- `extractContents()` 可能破坏原 EPUB 排版，只作为跨元素边界异常时的谨慎 fallback。
- 清除旧用户高亮后要 `normalize()` 文本节点。
- 段落索引和 DOM 选择器必须始终一致。

### 8.3 Compose Effect

当前必须以实际源码为准审查 `LaunchedEffect` 的拆分方式：段落级初始化和句子级切换不能因为两个异步 JS 调用产生竞态；但也不能让句子每秒变化都重建整段 DOM。修改 WebView 高亮前必须先阅读当前 `EpubWebView.kt`，不要直接套用历史技能中互相矛盾的“合并/拆分”旧建议。

## 9. AI、翻译和本地 AI

`TranslationService` 负责三种模式：

- `translate`：翻译。
- `explain`：语境解释。
- `analyze`：语法分析。

调用通过 `LLMConfig` 保存 endpoint、API key、model 和 provider，支持 OpenAI 兼容 SSE。选中文本后 `ReaderViewModel.translate()` 启动协程，并把流式片段追加到 `translationResult`。

本地 AI：

- `LocalAiEngine` 调用 llama.cpp JNI。
- `LlamaJniWrapper` 的 `System.loadLibrary` 必须 try/catch。
- 所有 JNI 入口（init、release、getLogs、clearLogs、reloadModel 等）都要用 `isAvailable()` 守卫。
- 无 `.so`、无 NDK 或模型文件时，相关功能应返回不可用/空结果，不能让阅读器启动崩溃。

## 10. GitHub 仓库组织与分支

当前可见分支：

```text
main                       # 唯一 source of truth，当前开发基线
origin/Backup              # 历史/备份基线，当前 commit 可能落后
origin/backup              # 备份分支
origin/master              # 旧分支
origin/v2.6.9-ts           # 旧 TTS 测试分支
origin/v2.6.9-ts-backup    # 旧 TTS 测试备份
feat/native-reader-poc     # 原生 Compose 阅读器 PoC
old-main-tts-fixes         # 历史 TTS 修复分支
```

标签包含 `v2.9.25`、`v2.9.24`、`v2.9.13` 等，以及旧的 `v2.16-local-ai`。不要把旧 tag、Backup 或 PoC 当作当前生产基线。

根目录重要内容：

```text
README.md                         # 简短项目说明，但内容明显落后于当前源码
CHANGELOG.md                      # 历史改动记录，主要覆盖早期版本和 Backup
墨阅Moreader_使用说明书.docx      # 面向用户的早期 v1.3.3 说明书
墨阅Moreader_源代码.docx          # 早期 v1.3.3 源代码说明，非当前代码镜像
app/                              # Android 应用源码和资源
app/src/main/assets/              # 词典数据库等大资源
app/src/main/cpp/                 # llama.cpp JNI 代码
app/build.gradle.kts              # Android 构建、签名、依赖、版本
settings.gradle.kts               # Gradle 项目设置
moreader.keystore                 # 项目签名文件，不要改
moreader.apk                      # 历史/本地 APK 产物，不能仅凭文件名判断新旧
```

### Git 工作流硬规则

修改前：

```bash
cd /root/.hermes/projects/moreader-android
git status
git fetch origin
git log --oneline -1 HEAD
git log --oneline -1 origin/main
git log --oneline -1 origin/Backup
```

- 先确认当前分支和 commit。
- 任何功能开发先讨论方案，再改代码。
- 优先 GitHub `main`，不要在 detached HEAD 或旧 tar.gz 上审查。
- 只保留一个本地项目目录。
- 修改后先编译和验证，再让用户手机测试。
- 用户明确确认后才 commit；用户明确要求后才 push。
- 禁止 force push、amend、覆盖已有 tag。
- 版本号单调递增。

## 11. 开发与排错禁区

1. 不要混淆墨阅 Android、墨阅浏览器扩展、墨笺和畅说。
2. 不要直接重写大文件；先读完整流程，做最小 patch。
3. 不要只修一个调用方；共享函数要搜索所有调用方。
4. 不要把 WebView 方案未经讨论替换成原生 Compose 阅读器；`feat/native-reader-poc` 只是 PoC。
5. 不要为了网络问题先加代理；先用直接路径测试，再判断是否需要代理。
6. 不要在主线程使用同步网络请求。
7. 不要删除段落元素来清洗 TTS 文本；只做文本清理，保持索引。
8. 不要用 TTS 清理后的文本计算 DOM 高亮偏移。
9. 不要修改签名配置、namespace 或 applicationId，除非明确讨论独立实验 APK。
10. 不要将调试 APK 误当正式 APK；检查 variant、versionCode、签名和文件 hash。
11. 不要把旧 README/DOCX 中的 v1.3.3 结构当作当前实现。
12. 不要为已经存在的 OkHttp、Coroutines、Jsoup 等能力添加重复依赖。
13. 不要重启全部 Hermes profile 处理单个问题；只操作目标服务。
14. 服务端修改前先 curl 实测；已知另一台机器有正常版本时，优先复制已知正常版本，而不是现场重写。
15. 不要把 API key、Telegram token、TTS 密钥写入日志、报告或 Pi 任务描述。

## 12. 构建、发布和验证清单

### 12.1 修改前

```bash
git status --short --branch
git fetch origin
git log --oneline -1 HEAD
git log --oneline -1 origin/main
./gradlew tasks --all >/dev/null
```

确认：项目是墨阅 Android，当前分支正确，没有未处理用户改动。

### 12.2 修改后

- 检查 Kotlin/XML 是否有不应出现的硬编码 UI 中文；内部 Log 不算。
- 检查 Room migration 是否完整。
- 检查所有 Provider 是否实现新增接口方法。
- 检查 TTS 网络调用的 Dispatcher、超时和取消。
- 检查 WebView DOM 索引是否仍与 TTS 段落索引一致。
- 检查资源数据库是否存在且未被错误压缩。
- 编译 debug/release 对应 variant。

```bash
./gradlew assembleDebug
find app/build/outputs/apk -type f -name '*.apk' -printf '%p %s bytes\n'
```

### 12.3 发布/手机测试前

- 用 `apkanalyzer` 或 `aapt` 核验实际 `versionCode`、`versionName`、applicationId。
- 确认新 versionCode 高于手机已安装版本，避免“已安装此版本”造成假故障。
- 确认签名一致。
- 部署后对下载文件做 hash/字节校验。
- 用户手机无 ADB，测试依赖 App 内日志弹窗和复制日志。
- 不要要求用户反复卸载重装，避免丢失 Room 数据。

## 13. Pi 编程助手执行说明

Pi 在 192.168.199.159 上运行，有完整 Linux/root 能力。交给 Pi 的任务必须明确：

1. 项目是 **墨阅 Android**，不是墨阅浏览器扩展、墨笺或畅说。
2. 源码目录：`/root/.hermes/projects/moreader-android/`（如果 Pi 机器没有这个目录，先从 GitHub clone 到 Pi 的工作目录）。
3. GitHub：`gzmliang/moreader-android`，基线使用 `main`。
4. 先读取本文档，再读取实际源码；如果本文档与源码冲突，以源码为准并报告差异。
5. 修改前先报告理解、涉及文件和最小方案，不要直接大改。
6. 修改后必须编译验证，并返回真实命令输出、修改文件、commit/branch 状态。
7. 未经明确授权不要 commit、push、改签名、改版本号或部署 APK。
8. 涉及服务器 TTS/同步时，先确认目标服务器和端口，先 curl，再修改。

推荐 Pi 任务模板：

```text
这是墨阅 Moreader Android 手机端项目，不是浏览器扩展/墨笺/畅说。
先读取 /root/moreader-android/docs/MOREADER_ANDROID_PROJECT.md（或当前工作目录中的同名文档），
再检查 GitHub gzmliang/moreader-android 的 main 最新 commit 和实际源码。
不要直接改代码。先报告：当前基线、问题根因、涉及文件、最小修改方案和验证方式。
如果我确认后再修改；修改后必须编译并返回真实验证结果。
```

## 14. 仍需后续整理的资料

- `README.md` 应更新为 v2.9.25 当前结构，当前 README 仍有早期路径和功能描述。
- `CHANGELOG.md` 应补齐 v2.9.14–v2.9.25 的完整历史。
- 两个 DOCX 仍是 v1.3.3 早期文档，不能继续作为“当前源代码文档”；可以保留为历史版本或另做更新版。
- 本仓库缺少一份跟源码自动同步的架构文档；后续每次重大版本应至少更新版本基线、源码树、构建产物和已知限制。
- `docs/` 目前只有少量主题文档，TTS/同步/高亮的大量知识分散在外部技能和历史记录中；本文档先统一入口，不复制全部逐 bug 历史。

---

**一句话结论：** 墨阅 Android 是一个以 `ReaderViewModel` 为核心、以 `WebView + Compose` 为阅读层、以 `TTSProvider` 为朗读抽象、以 Room 为本地元数据、以 Flask `/sync` 为跨端同步的原生 Android EPUB 学习阅读器；开发时必须以当前 GitHub `main` 和实际源码为准。