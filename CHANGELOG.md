# 墨阅 Moreader Android 更新日志与重大攻坚纪要

---

## 🌟 v2.9.53 (Build 20139) — 原著语言智能识别与中文名著地道图谱（梁老师实测反馈）

> **改进背景**：在测试《笑傲江湖》等中文原著时，由于系统默认原著语言配置为英文，大模型错误将中文角色姓名翻译成拼音英文（如 `Linghu Chong`、`Ren Yingying`），导致图谱呈现英文。

### 1. 语言智能自动感知（Auto-Detect 引擎联动）
- 将 AI 伴读原著语言默认配置设为 **`Auto`（⚡ 自动识别原著）**；
- 生成图谱、小测验及总结前，系统利用内置的 `LanguageVoiceDetector` 进行毫秒级语言采样识别，精准区分中文原著（`Chinese`）、英文原著（`English`）、日文原著等，免去用户手动反复切换语言配置的繁琐。

### 2. 纯正中文原著模式铁律指令
- 当识别为中文名著（如《笑傲江湖》）时，注入最高优先级的中文原著指令：
  - 原著与目标语言完全统一为中文，**强制要求所有角色姓名（令狐冲、岳不群、任盈盈）、门派阵营（华山派、日月神教）、人物生平、关系标签（恩师、大弟子、掌门）和剧情时间线 100% 使用地道纯正的中文输出**；
  - 严禁任何形式的拼音英译或生硬翻译。

### 3. UI 双语与中文渲染自愈
- 在双语或原文模式下，自动识别主名称是否为中文；如果是中文，优先且突出展示中文主名称；当原名与译名完全一致时，智能抑制冗余的括号重复显示，界面清爽自然。

---

## 🏆 重大里程碑：v2.9.52 (Build 20138) — AI 角色脉络与全景人物图谱体系（梁老师深度验收通过）

> **验收评价**：“**可以了，这个版本可以了，做好备份吧。**”  
> **核心突破契机**：围绕《权力的游戏》等宏大长篇著作，彻底重构 AI 人物提取与关系图谱渲染体系。从提示词底层约束视角、双语标签支持、历史缓存自愈，到多亲属自动拆分、人物实时搜索与永不撞墙名片兜底，打造出极具沉浸感的全景角色互动网络。

### 1. 提示词立下“以对方为准（Target-Centric）”视角铁律
- **彻底杜绝主仆与辈分颠倒**：明确铁律要求大模型在某一角色名下关联他人时，关系标签（label）**必须且只能描述“对方对于当前角色是什么身份”**，严禁反过来写自己；
- **注入经典 Few-Shot 范例**：
  - 伙伴/战宠类：琼恩名下的白灵标签必须是 `Direwolf Companion`（冰原狼伙伴），反之白灵名下的琼恩才是 `Master`；
  - 上下级类：琼恩名下的莫尔蒙标签必须是 `Lord Commander / Superior`（总司令/长官）；
  - 亲属长幼：艾德名下的罗柏为 `Eldest Son / Heir`，罗柏名下的艾德为 `Father`。

### 2. 底层新增双语关系标签（`labelTranslation`）与自然渲染
- 在脉络数据结构中正式扩展 `labelTranslation` 字段；
- 大模型提取时同时生成原著英文与中文翻译；
- 中文或双语模式下直接呈现自然生动的中文标签（如 `冰原狼伙伴: Ghost`、`守夜人总司令: Jeor Mormont`），绝无晦涩理解门槛。

### 3. 本地缓存反序列化空指针与全链路自愈防御 (v2.9.51 成果集成)
- **模型自愈**：在 `CharacterCard` 内部为关系列表及展开节点加上全生命周期的空安全过滤；
- **缓存层清洗**：读取本地历史缓存时自动对旧版缺少关系字段的数据进行无害补齐与属性清洗，杜绝旧章节闪退，新旧数据平滑无缝过渡。

### 4. 人物全局实时搜索栏 (v2.9.50 成果集成)
- 图谱顶部常驻搜索栏，支持中文名、英文名、家族名（如“罗柏”、“Robb”、“Stark”）即输即滤，毫秒级定位目标人物。

### 5. 多亲属并列智能拆分与精准代际纠偏
- 将原著中并列的所有子女（如 `Robb, Sansa, Arya, Bran, and Rickon`）智能拆解为独立的精致胶囊名片，并精准归入 **👶 子女辈**。

### 6. 关联人物点击“永不撞墙”
- 遇到书中仅提及一次的远房人物或封臣，绝不弹出冷冰冰的“暂无档案”，而是就地优雅展示专属名片与亲属脉络，交互体验流畅温暖。

---

## 🌟 v2.9.44 (Build 20130) — 引用预览与注释体验深度精修（梁老师体验反馈）

> **改进背景**：  
> 1. **浮窗重叠避让**：点击图示（如 Figure 7.4）弹出 Note 弹窗时，若触发了段落点击，底部的 `▶ Read from here` 胶囊会与 Note 弹窗右下角的按钮发生部分重叠遮挡；  
> 2. **跨章节尾注/注脚轻预览覆盖**：图示引用（同页）有小弹窗，但正文中的第 1 点、第 2 点（书后尾注 Endnotes）此前仍直接大跳转，读者希望在正文里也能弹出 Note 小弹窗秒看。

### 1. 浮窗完美错开与源头防撞（0 重叠、0 遮挡）
- **源头防撞**：WebView 段落点击监听器严格排除 `a` 链接点击，点击角标或超链接时绝不再多余触发段落朗读胶囊；
- **立体避让**：Compose 界面层深度防撞：当 Note 弹窗（`state.footnotePreview != null`）展示时，段落胶囊主动隐藏；若存在并存状态，其底部内边距自动向上避让至 `290dp`（全屏 `240dp`），悬浮在 Note 弹窗上方，两者上下错开，**绝对不遮挡 Note 弹窗及其右下角操作按钮**。

### 2. 跨章节尾注/注脚全自动异步嗅探弹窗
- **全格式覆盖**：无论角标是同章节内的纯锚点（`#...`），还是跨章节指向书后尾注文件的链接（如 `notes.xhtml#fn1`、`endnotes.xhtml#note1` 等）；
- **极速嗅探提取**：系统自动在后台毫秒级读取目标尾注章节的 HTML，利用 Jsoup 自动嗅探提取出第 1 点、第 2 点的具体注释文本（自动剔除返回箭头符号 `↩`）；
- **原地弹窗秒看**：直接在当前屏幕呼出精致的「📖 Note」弹窗，**阅读完全不用离开正文，彻底消除翻书疲劳感**；
- **一键前往支持**：弹窗内依然保留「前往查看」按钮，读者若想看完整的尾注上下文，点按即可无缝前往（看完点返回依然享受金黄色光晕精准回跳）。

---

## 🏆 重大里程碑：v2.9.43 (Build 20129) — 书内注释回跳终极攻坚（梁老师灵感指引）

> **困扰时间**：历经多个版本反复调试（v2.9.41 ~ v2.9.42），长期存在“跳回时位置偏上几行”、“差了半屏、还要手指上下找”、“跨章节加载冲掉定位”等痛点。  
> **核心突破契机**：梁老师一语道破天机 —— *“书签和高亮每次都能百发百中准确跳到，为什么回跳不直接用书签和高亮那套成熟可靠的机制，顺便把离开的位置做类似高亮的标注，回跳的时候就跳回去呢？”*  
> **验收评价**：“**这个回跳的效果非常好，这个版本先做好备份吧，把这个做个标注，说明这个改动，这个改动搞了我好久时间了。**”

### 1. 为什么这个问题搞了这么久？（病根全起底）
1. **跳转前取段天然偏上**：旧逻辑在点击角标（如 `^1` 或 `[1]`）瞬间，通过 `offsetTop > scrollY + innerHeight * 0.3` 粗糙估算“当前段落”，导致记录的段落天然比读者实际阅读的段落靠上好几行。
2. **跨章节加载时序打架**：跨章节切回正文时，页面刷新过程中系统自带的“默认滚到页面顶部（段落 0）”与异步渲染排版，常常把纯像素还原指令给冲掉，或导致位置偏差。
3. **缺乏视觉定焦点**：纯像素或居中滚动后，读者视野内是一整屏密密麻麻的文字，缺少焦点，眼睛必须在上下几行反复搜索才能确认刚才离开的那个角标。

### 2. 终极架构重构（梁老师构想落地）
1. **跳转前 DOM 级锁定**：
   - 在手指点击角标 `<a>` 瞬间，通过 `a.closest('p,h1,h2,h3,h4,h5,h6')` 严格锁死包含该角标的具体段落下标（`clickedIdx`）；
   - 同时将该链接特征 `linkHref` 压入历史栈 `NavHistoryEntry`。
2. **回跳走书签/高亮成熟通道**：
   - 彻底废弃孤立脆弱的纯像素时序打架算法；
   - 统一接入与书签、高亮完全同源的 `scrollToParagraph` 成熟渲染与滚动流程，保证跨章节与同章节定位百分之百稳健。
3. **视觉焦点双层标注（金黄光晕角标 + 柔和蓝底段落）**：
   - 回跳到达那一刻，`window.scrollToPara(idx, linkHref)` 自动在段落中嗅探定位出该角标元素；
   - 被点击角标立即点亮醒目的**金黄色高亮光晕（`#FFE082` 背景 + `#FFB300` 精致微外框）**并居中展示；
   - 所在大段落同时伴随柔和淡蓝色底色，停留 2.5 ~ 3 秒后平滑优雅淡出；
   - 读者眼睛一睁开就能秒锁离开前的数字，彻底告别上下翻找！

---

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
