# 墨阅 Moreader — 版本历史

---

## v2.4.0 (2026-05-29) 🎉

### ✨ 新功能
- **单词/闪卡独立发音引擎**：朗读设置面板新增「单词/闪卡发音引擎」区域
  - 支持 System TTS / Edge TTS / AI Voice / Custom TTS 四项独立选择
  - 与正文朗读引擎完全分离（`flashcard_tts_provider` vs `tts_provider`）
- **System TTS 真·单词发音**：单词本和闪卡点击喇叭按钮，直接用手机系统 TTS 朗读
  - 之前 System TTS 选项会回退到 Edge TTS，现已修复
  - 使用 `TextToSpeech` init 回调确保引擎就绪后再 speak

### 🔧 修复
- **纯符号段落不再朗读**：诗歌/文章中的注释分隔符段落（`*，/*/` 等）自动跳过
  - `extractParagraphsFromHtml` 增加语义过滤：无中英文的段落置空
  - 支持全角符号 `／＊／` 变体
- **高亮同步修复**：跳过符号段落后，高亮不再滞后于声音
  - 将 `.filter{}` 删除段落改为置空保留位置
  - `playOne()` 的 `if (text.isBlank()) skip` 逻辑自动跳过空段落
  - 段落索引与 DOM 保持对齐，`window.ttsHL()` 精准高亮
- **停止后重新朗读不断链**：`SystemTTSProvider.stop()` 加入 `QUEUE_FLUSH`
  - `ensureInitialized()` 复用引擎时重新绑定 listener（闭包陷阱修复）

### 📖 帮助文档更新
- DeepSeek 注册说明更新：已取消注册赠送 → 推荐 SiliconFlow/百炼免费额度
- API Key 获取指引：SiliconFlow 注册送 2000 万 tokens、百炼新用户送百万 tokens
- 中英文双语同步更新

### 📦 技术细节
- 版本号: **versionCode 40 / versionName 2.4.0**
- 基于 `main` 分支构建（包含书架搜索、EdgeTTS 修复、翻译引擎 UI 等全部功能）
- 修改文件: `ReaderViewModel.kt`, `SystemTTSProvider.kt`, `TtsSettingsSheet.kt`, `VocabularyViewModel.kt`, `FlashcardViewModel.kt`, `strings.xml` × 2

---

## v2.3.1 (2026-05) — 基于 v2.3-tts-stable 构建，功能不完整

### 功能
- TTS 三层过滤: `<sup>` 标签移除 → `<rt>/<rp>` 标签移除 → 正则清理 `[1]` `①②` `/*/`
- TTS 闭包陷阱修复: `ensureInitialized()` 复用引擎时重新绑定 listener
- `stop()` 加 `QUEUE_FLUSH` 防止断链

### ⚠️ 已知问题
- 基于 `v2.3-tts-stable` 分支构建，缺少 `main` 上的书架搜索、EdgeTTS 修复、翻译引擎 UI 等

---

## v2.2.x 及更早

- 书架搜索功能
- 单词本 + 闪卡（SR 间隔复习）
- 本地 AI 翻译（llama.cpp + GGUF 模型）
- 云端翻译（DeepSeek / 通义千问 / SiliconFlow）
- 多种 TTS 引擎: Edge TTS / System TTS / AI Voice / Custom TTS
- 内置拼音字典（8K 汉字）
- 黑夜模式 / 多主题
- 朗读跟随、书签、高亮
- 笔记导出
