package com.moyue.tts

object LanguageVoiceDetector {

    // 日文假名正则
    private val JP_REGEX = Regex("[\\u3040-\\u309F\\u30A0-\\u30FF]")
    // 韩文谚文正则
    private val KO_REGEX = Regex("[\\uAC00-\\uD7AF\\u1100-\\u11FF]")
    // CJK 统一汉字
    private val CJK_REGEX = Regex("[\\u4E00-\\u9FFF]")
    // 西里尔字母 (俄语)
    private val CYRILLIC_REGEX = Regex("[\\u0400-\\u04FF]")

    /**
     * 检测文本语言代码: zh, en, ja, ko, ru, de, fr, es
     */
    fun detectLanguage(text: String): String {
        if (text.isBlank()) return "zh"

        var cjk = 0
        var jp = 0
        var ko = 0
        var cyrillic = 0
        var latin = 0

        for (ch in text) {
            val s = ch.toString()
            when {
                JP_REGEX.matches(s) -> jp++
                KO_REGEX.matches(s) -> ko++
                CJK_REGEX.matches(s) -> cjk++
                CYRILLIC_REGEX.matches(s) -> cyrillic++
                ch in 'a'..'z' || ch in 'A'..'Z' -> latin++
            }
        }

        // 1. 排他性日韩俄
        if (jp >= 2) return "ja"
        if (ko >= 2) return "ko"
        if (cyrillic >= 3) return "ru"

        // 2. 中英文对比
        if (cjk >= 2) return "zh"
        if (latin >= 4) return "en"

        return if (cjk > latin) "zh" else "en"
    }

    /**
     * 根据语言选择最合适的 Edge-TTS 音色
     */
    fun getMatchingEdgeVoice(detectedLang: String, currentVoice: String): String {
        return when (detectedLang) {
            "en" -> {
                // 如果当前已经是英文音色就保留，否则换 Jenny
                if (currentVoice.startsWith("en-")) currentVoice else "en-US-JennyNeural"
            }
            "ja" -> "ja-JP-NanamiNeural"
            "ko" -> "ko-KR-SunHiNeural"
            "ru" -> "ru-RU-SvetlanaNeural"
            "de" -> "de-DE-KatjaNeural"
            "fr" -> "fr-FR-DeniseNeural"
            "es" -> "es-ES-ElviraNeural"
            else -> {
                // 中文：保留用户当前选的中文音色（如晓晓、云希）
                if (currentVoice.startsWith("zh-")) currentVoice else "zh-CN-XiaoxiaoNeural"
            }
        }
    }
}
