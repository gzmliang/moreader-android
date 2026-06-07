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
