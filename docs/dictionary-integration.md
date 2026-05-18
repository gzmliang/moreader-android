# 离线词典集成方案 — 墨阅 Moreader

## 📋 概述

将 ECDICT（77 万英汉词条）集成到墨阅阅读器，实现**毫秒级离线查词**，AI 翻译作为兜底。

## 🏗 架构

```
用户选中文本 → ReaderViewModel.translate()
    │
    ├─ 是短英文词? (< 50字符, 无中文)
    │   ├─ YES → DictionaryEngine.query(word)
    │   │   ├─ Found → 结构化展示 (音标+释义+词形) ← 毫秒级
    │   │   │           ↓
    │   │   │        ➕ 按钮 → 结构化数据存入生词本
    │   │   │
    │   │   └─ NotFound → 降级到 AI/云端翻译
    │   │
    │   └─ NO → 直接走 AI/云端翻译（句子/段落/中文）
```

## 📦 数据源

- **ECDICT** (skywind3000/ECDICT)
- 770,611 词条
- SQLite 数据库: `app/src/main/assets/dictionary.db` (~80MB)
- 字段: word, phonetic, translation, pos, collins, oxford, bnc, frq, exchange, detail
- APK 增量: ~30-40MB (assets 会被 zip 压缩)

## 📁 新增文件

```
app/src/main/
├── assets/
│   └── dictionary.db              ← 预打包词典数据库
└── java/com/moyue/
    ├── data/models/
    │   └── DictionaryEntry.kt     ← 词典数据模型 + 格式化显示
    └── app/localai/
        └── DictionaryEngine.kt    ← 词典查询引擎
```

## 🔧 修改文件

| 文件 | 修改内容 |
|------|---------|
| `ReaderViewModel.kt` | 翻译逻辑增加词典优先；`addSelectedWordToVocabulary` 支持结构化数据 |
| `ReaderScreen.kt` | 翻译弹窗标题栏增加 ➕ 按钮；增加 vocabToast |

## ⚡ 性能

| 场景 | 响应时间 | 说明 |
|------|---------|------|
| 词典命中 | **~10ms** | SQLite 精确匹配 |
| 词典未命中 | ~200ms | 数据库查询未找到，降级到 AI |
| AI 翻译 (1.5B CPU) | ~16s | 本地模型生成 |
| AI 翻译 (云端) | ~3-8s | 取决于 API |

## 🎯 使用场景

1. **查单词**: "resilience" → 词典命中 → 瞬间显示音标+释义 → ➕ 存入生词本（带音标）
2. **查生词**: 词典里没有的新词 → 自动降级到 AI → AI 翻译 → ➕ 存入生词本
3. **查句子/段落**: 直接走 AI → 不走词典

## 🚀 构建

```bash
# 数据库已打包到 assets/，直接编译即可
./gradlew assembleDebug

# APK 输出
app/build/outputs/apk/debug/app-debug.apk
```

## 💡 未来优化

1. **词形还原 (Lemmatization)**: "went" → "go" 查询（需要 lemma.en.txt）
2. **中文词典**: 集成 CEDICT 支持中翻英
3. **发音音频**: ECDICT 有 audio 字段，可对接 TTS 朗读单词
4. **数据库压缩**: 用 Room + prepackaged DB 替代 raw SQLite
