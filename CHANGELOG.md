# 墨阅 Moreader Android — Backup 分支

> **基线版本：** v2.6.9 (V77)  
> **备份版本：** V20002  
> **包名：** `com.moyue.app.tingshu`（测试版，不与正式版冲突）  
> **签名：** `moreader.keystore` (CN=MoYue, SHA256 B4:5C:F8:67)  
> **分支策略：** 此分支为只追加分支，不修改、不删除已有内容

---

## 改动概述

基于 V77 源码增加 **TTS 语句级绿色高亮**功能。共修改 8 个文件。

### 架构：三引擎自动检测语句追踪

```
playOne() → onStart → 拆分句子
   │
   ├─ pendingBoundaries 存在? (Edge TTS 词边界)
   │    → [SENT:wb] delay() 毫秒级精确调度 ✅
   │
   ├─ onRangeStart 触发? (Google System TTS)
   │    → [SENT] char-offset → 句子映射 ✅
   │
   └─ 2.5s 无信号
        → [SENT:est] 字符比例估算 ⚠
           ├─ 中文: 6 字/秒
           └─ 英文: 15 字/秒
```

---

## 修改文件清单

### 1. `TTSProvider.kt` — 接口扩展
- 新增 `WordBoundary` 数据类（词边界: offsetMs/text/charStart/charEnd）
- 新增 `PreloadResult` 数据类（audio + boundaries 双写缓存）
- TTSListener 接口新增：`onRange(start, end)`、`onWordBoundaries(boundaries)`
- TTSProvider 接口新增：`playRaw(audioData, listener)`
- 新增 `parseWordBoundaries(json)` 工具函数

### 2. `EdgeTTSProvider.kt` — 词边界支持
- `fetchAudio()` 返回 `PreloadResult`（audio + boundaries），多句自动走 `/tts_with_boundaries`
- `speak()` 智能选端点：单句 → `/tts`（快~1.5s），多句 → `/tts_with_boundaries`（含词边界）
- 新增 `fetchBoundariesOnly()` 轻量边界获取
- 新增 `hasMultipleSentences()` 预检
- connectTimeout: 10s → 5s（LAN 环境优化）
- `playRaw()` 加 `override`

### 3. `SystemTTSProvider.kt` — onRangeStart 集成
- `UtteranceProgressListener` 新增 `onRangeStart(id, start, end, frame)` 回调
- `setupTts()` 修复顺序：listener 先于 `setLanguage`（Pitfall #5.3）
- `ensureInitialized()` 修复：引擎就绪直接 return，不重复调 `setupTts`
- `setLanguageForText()` 带 `cachedLocale` 缓存，首次切换后跳过
- 双 `onInit` 修复：voices=0 时跳过 `setupTts`
- `fullDestroyInternal()` 重置 `cachedLocale`

### 4. `ReaderViewModel.kt` — 核心句子追踪
- `ReaderUiState` 新增字段：`ttsSentenceIdx`、`ttsSentenceCount`
- 新增字段：`sentenceEnds`、`pendingBoundaries`、`boundariesCache`、`estJob`
- `playOne` listener 重构：
  - `onStart()`: 拆分句子（text.indexOf 完整文本偏移）、词边界驱动入口、2.5s 估算检测
  - `onWordBoundaries()`: 暂存到 `pendingBoundaries`
  - `onRange()`: range→句子映射，首次触发取消估算检测
  - `onDone()`: 不清 `ttsSentenceIdx`（Pitfall #37）
- 新增 `startWordBoundaryTracking()`: 词边界→delay() 精确调度
- 新增 `startEstimation()`: 中英文自适应速率（6/15 字/秒）
- `preloadRange()` 改用 `PreloadResult` 双写 `audioCache` + `boundariesCache`
- `readChapter()` / `readFromParagraph()` 加首段异步预加载（Pitfall #31）
- `killPlayChain()` 清 `boundariesCache` + 取消 `estJob`
- 分句正则：`(?<=[.!?。！？；;])\s*`（中英文标点）

### 5. `EpubWebView.kt` — WebView 句子高亮
- 新增 `.tts-sentence-hl` CSS（绿色 rgba(34,197,94,0.3)）
- 合并 LaunchedEffect：`ttsHighlightIndex` + `ttsSentenceIdx`（Pitfall #38）
- `prevHighlightIdx` 检测段落切换，`paraChanged` 时强制 `initAndHighlight`
- JS 函数替换为 TreeWalker+Range 方案（不预包裹，只包裹当前句）：
  - `initAndHighlight(paraIdx, sentIdx)`: 设段落高亮 + 建句子边界数组 + 高亮句 0
  - `ttsHLSentence(idx)`: 句子切换 → 清旧 span → 包新 span
  - `_ttsHLSentence(idx)`: TreeWalker 定位→Range 包裹→span.tts-sentence-hl
  - `ttsSentenceClear()`: 清除所有 `[data-tts-sentence="1"]` span
  - `window.ttsSentences`: 句子边界数组
  - `window._ttsSentencePara`: 当前段落引用

### 6. `ReaderScreen.kt` — 传递句子状态
- `EpubWebView` 调用新增 `ttsSentenceIdx = state.ttsSentenceIdx` 参数

### 7. `LlamaJniWrapper.kt` — Native 崩溃保护
- `System.loadLibrary` 用 try-catch 包裹
- 新增 `isAvailable(): Boolean`

### 8. `LocalAiEngine.kt` — JNI 调用守卫
- `init()` / `releaseModel()` / `getLogs()` / `clearLogs()` 全部加 `isAvailable()` 检查

---

## 构建配置

```kotlin
// app/build.gradle.kts
applicationId = "com.moyue.app.tingshu"
namespace = "com.moyue.app"        // 保持命名空间不变
versionCode = 20002
versionName = "2.6.9-ts"
// CMake/externalNativeBuild 已禁用（无 NDK 环境）
// 签名: moreader.keystore (../moreader.keystore)
```

```xml
<!-- res/values/strings.xml -->
<string name="app_name">墨阅测试版</string>
```

---

## 已知问题

1. **英文书末句高亮偶尔消失** — 英文走估算路径，`15f` 速率有偏差。中文书 onRangeStart 精确追踪正常。
2. **LlamaJniWrapper** — native 库被保护性包裹，本地 AI 功能在无 .so 环境下自动禁用。

---

## 部署

局域网 HTTP 服务器：
```bash
# 172.16.0.xx 机器上
cd /tmp/apk-server && python3 -m http.server 9999 --bind 0.0.0.0
# 下载: http://<ip>:9999/debug/app-v20002.apk
```

---

## 相关文档

- `TTS-ARCHITECTURE.md` — 完整架构（技能系统: `moreader-tts-architecture`）
- `android-tts-system` 技能 — System TTS onRangeStart 详细实现
- `android-tts-debugging` 技能 — 50 个踩坑记录

---

## V20003 — 多生词本功能 (2026-06-07)

基于 **tingshu 测试版** 已验证方案实现，共修改 11 个文件。

### 功能

| 功能 | 说明 |
|:---|:---|
| 阅读中加词选本 | 选中单词 → [+] 或 📖 → 弹出「选择生词本」→ 选本保存 |
| 生词本页面切换 | 顶部横向 chips 显示所有生词本，点击切换过滤 |
| 新建生词本 | chips 右侧 "+" → 输入名字 → 创建 |
| 删除生词本 | 选中 chip 上的 × → 确认 → 删除该本及其中所有单词 |
| 手动添加单词 | 生词本页面顶栏 Add 按钮 → 归入当前选中的本 |
| 加词带翻译 | 翻译面板 [+] 加词时自动附带翻译结果 |

### 数据层

| 文件 | 改动 |
|:---|:---|
| `Vocabulary.kt` | 新增 `plan` 字段（默认"默认"） |
| `VocabularyDao.kt` | 新增 5 个 plan 查询方法 |
| `BookRepository.kt` | 新增 5 个 plan 委托方法 |
| `BookDatabase.kt` | 版本 7→8，加 MIGRATION_7_8 |
| `Migrations.kt` | 新增 MIGRATION_7_8：`ALTER TABLE vocabulary ADD COLUMN plan` |

### UI 层

| 文件 | 改动 |
|:---|:---|
| `VocabularyViewModel.kt` | plan 管理：currentPlan/planNames/switchPlan/createPlan/deletePlan |
| `VocabularyScreen.kt` | plan 选择器 chips + 新建/删除生词本对话框 + 添加单词对话框 |
| `ReaderUiState` | 新增 `showVocabPlanPicker` + `vocabPlanOptions` |
| `ReaderViewModel.kt` | `showVocabPlanPicker`/`dismissVocabPlanPicker`/`addVocabulary(plan)`，加词时附带翻译 |
| `ReaderScreen.kt` | 生词本选择 AlertDialog，两个加词按钮改为显式选本 |
| `strings.xml` (中+英) | 新增 7 个 vocab_notebook 字符串 + `add_word` |

### 技术细节

- Plan 列表存入 SharedPreferences (`moreader_vocab`)，单词 plan 存入 Room DB
- 向后兼容：MIGRATION_7_8 给旧数据自动设 `plan='默认'`

---

## v2.8.0 (V20009) — TTS 句子高亮大修

**日期：** 2026-06-16

### 修复

| 问题 | 原因 | 修复 |
|------|------|------|
| 每段最后一句绿色高亮消失 | JS 句子拆分 regex `text.match()` 与 Kotlin `split()` 不一致，最后一句无标点则被省略 | 改为逐标点切分 + 兜住末尾残留文本，与 Kotlin 逻辑完全对齐 |
| 引号结尾的句子高亮悬停在引号上 | `."` 结尾时引号被拆成独立1字符"句子" | Kotlin `SENTENCE_REGEX` 和 JS 拆分均跳过闭合引号（`" ' » « "" '' 「」『』`） |
| 上一段绿色高亮残留 | `initAndHighlight` 未清旧绿色 span | 加 `ttsSentenceClear()` 再建新高亮 |
| 句子高亮不在屏幕中央 | 只有段落级 `scrollIntoView`，句子级无滚动 | `_ttsHLSentence` 末尾加 `span.scrollIntoView({block:'center'})` |

### 改进

- 新增 `MoreaderBridge.jsLog()` 日志桥，JS 内部执行可写回 Android logcat
- 所有关键节点（段落开始/结束、句子高亮设置、JS 调用）加毫秒时间戳日志 `[TIME]`

### 文件改动

| 文件 | 改动 |
|------|------|
| `ReaderViewModel.kt` | `SENTENCE_REGEX` 加引号消费；`onDone` 移除50ms延迟（不再需要）；加 `[TIME]` 日志 |
| `EpubWebView.kt` | 重写 JS 句子拆分（逐标点切分+引号跳过+末段兜底）；`_ttsHLSentence` 加 `scrollIntoView`；`initAndHighlight` 加 `ttsSentenceClear`；加 `MoreaderBridge.jsLog` 桥 |
| `build.gradle.kts` | versionCode 20008→20009, versionName 2.7.0→2.8.0 |
- `vocabulary` 改为 `_currentPlan.flatMapLatest { repository.getVocabularyByPlan(it) }` 动态过滤
