package com.moyue.app.data.models

/**
 * Dictionary entry from local ECDICT database.
 * Used for fast offline word lookup (milliseconds).
 */
data class DictionaryEntry(
    val word: String,
    val phonetic: String? = null,           // IPA: /rɪˈzɪliəns/
    val translation: String,                 // 中文释义
    val pos: String? = null,                // 词性 (usually empty in ECDICT)
    val collins: Int = 0,                   // 柯林斯星级 1-5
    val oxford: Int = 0,                    // 牛津星级
    val bnc: Int = 0,                       // BNC 词频排名（越小越常用）
    val frq: Int = 0,                       // 频率评分
    val exchangeJson: String? = null,       // 词形变化 JSON
    val detailJson: String? = null,         // 详细释义 JSON
) {
    /** Parse word forms from exchange JSON */
    fun getWordForms(): Map<String, String> {
        if (exchangeJson.isNullOrBlank()) return emptyMap()
        return try {
            val map = mutableMapOf<String, String>()
            // Simple JSON parsing without external lib
            val pairs = exchangeJson.removeSurrounding("{", "}").split(",")
            for (pair in pairs) {
                val parts = pair.split(":")
                if (parts.size == 2) {
                    val key = parts[0].trim().removeSurrounding("\"")
                    val value = parts[1].trim().removeSurrounding("\"")
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        map[key] = value
                    }
                }
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Format a display-friendly string for the translation panel */
    fun formatForDisplay(ctx: android.content.Context): String {
        val sb = StringBuilder()
        
        // Check if this is a Chinese character query (single char with radical info)
        val isHanzi = word.length == 1 && pos != null && pos!!.isNotEmpty()
        
        if (isHanzi) {
            // Hanzi display format: already formatted in cursorToHanziEntry
            return translation
        }
        
        // English word format
        sb.append(word)
        if (!phonetic.isNullOrBlank()) {
            sb.append("  /").append(phonetic).append("/")
        }
        sb.append("\n\n")
        
        // Translation (main content)
        sb.append(translation.replace("\\n", "\n"))
        
        // Word forms if available
        val forms = getWordForms()
        if (forms.isNotEmpty()) {
            sb.append("\n\n${ctx.getString(com.moyue.app.R.string.dict_word_forms)}")
            val formLabels = mapOf(
                "plural" to ctx.getString(com.moyue.app.R.string.dict_form_plural),
                "past_tense" to ctx.getString(com.moyue.app.R.string.dict_form_past),
                "past_participle" to ctx.getString(com.moyue.app.R.string.dict_form_past_part),
                "present_participle" to ctx.getString(com.moyue.app.R.string.dict_form_pres_part),
                "third_person" to ctx.getString(com.moyue.app.R.string.dict_form_3rd),
                "comparative" to ctx.getString(com.moyue.app.R.string.dict_form_comparative),
                "superlative" to ctx.getString(com.moyue.app.R.string.dict_form_superlative)
            )
            forms.forEach { (key, value) ->
                val label = formLabels[key] ?: key
                sb.append("\n  • $label: $value")
            }
        }
        
        return sb.toString()
    }

    /** Check if this is likely an English word (for auto-detect) */
    fun isEnglishWord(): Boolean = word.any { it.isLetter() }
}

/** Result of a dictionary query */
sealed class DictionaryResult {
    /** Word found in local dictionary */
    data class Found(val entry: DictionaryEntry) : DictionaryResult()
    
    /** Word not found — should fall back to AI translation */
    data object NotFound : DictionaryResult()
}
