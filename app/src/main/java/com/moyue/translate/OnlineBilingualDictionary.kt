package com.moyue.app.translate

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Universal bilingual dictionary engine aligned with professional monolingual & bilingual reference specs:
 * 1. Monolingual Definition: Explains in the word's own native language (e.g. English-English, Chinese-Chinese)
 * 2. Bilingual Target Translation: Explains in user's selected target language (e.g. English-Chinese, English-French)
 * 3. Authentic Bilingual Examples: Up to 2 real-world sentences with dual-language translations
 * 4. 0ms offline phonetic & CJK radical/stroke support with persistent disk caching
 */
object OnlineBilingualDictionary {

    private const val TAG = "OnlineBilingualDict"
    private const val PREFS_CACHE = "online_dict_cache_v6"
    private const val GOOGLE_GTX_URL = "https://translate.googleapis.com/translate_a/single"
    private const val MYMEMORY_URL = "https://api.mymemory.translated.net/get"
    private const val FREE_DICT_URL = "https://api.dictionaryapi.dev/api/v2/entries/en"

    private var isGoogleReachable: Boolean? = null
    private var lastGoogleCheckTime = 0L

    data class LanguageOption(
        val code: String,
        val englishName: String,
        val displayName: String
    )

    val SUPPORTED_LANGUAGES = listOf(
        LanguageOption("zh-CN", "Chinese", "🇨🇳 中文 (简体)"),
        LanguageOption("zh-TW", "Traditional Chinese", "🇭🇰 中文 (繁體)"),
        LanguageOption("en", "English", "🇺🇸 English"),
        LanguageOption("es", "Spanish", "🇪🇸 Español"),
        LanguageOption("fr", "French", "🇫🇷 Français"),
        LanguageOption("de", "German", "🇩🇪 Deutsch"),
        LanguageOption("ja", "Japanese", "🇯🇵 日本語"),
        LanguageOption("ko", "Korean", "🇰🇷 한국어"),
        LanguageOption("ru", "Russian", "🇷🇺 Русский"),
        LanguageOption("it", "Italian", "🇮🇹 Italiano"),
        LanguageOption("pt", "Portuguese", "🇵🇹 Português"),
        LanguageOption("nl", "Dutch", "🇳🇱 Nederlands"),
        LanguageOption("ar", "Arabic", "🇸🇦 العربية"),
        LanguageOption("vi", "Vietnamese", "🇻🇳 Tiếng Việt"),
        LanguageOption("th", "Thai", "🇹🇭 ไทย"),
        LanguageOption("id", "Indonesian", "🇮🇩 Bahasa Indonesia"),
        LanguageOption("hi", "Hindi", "🇮🇳 हिन्दी")
    )

    // Fast HTTP client with tight timeouts (no hanging)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    fun getDefaultTargetLangCode(): String {
        val locale = Locale.getDefault()
        val lang = locale.language.lowercase()
        return when (lang) {
            "zh" -> if (locale.country.equals("TW", ignoreCase = true) || locale.country.equals("HK", ignoreCase = true)) "zh-TW" else "zh-CN"
            "es" -> "es"
            "fr" -> "fr"
            "de" -> "de"
            "ja" -> "ja"
            "ko" -> "ko"
            "ru" -> "ru"
            "it" -> "it"
            "pt" -> "pt"
            "nl" -> "nl"
            "ar" -> "ar"
            "vi" -> "vi"
            "th" -> "th"
            "id" -> "id"
            "hi" -> "hi"
            else -> "en"
        }
    }

    fun normalizeLangCode(lang: String): String {
        val trimmed = lang.trim()
        val found = SUPPORTED_LANGUAGES.find { 
            it.code.equals(trimmed, ignoreCase = true) || 
            it.englishName.equals(trimmed, ignoreCase = true) 
        }
        return found?.code ?: getDefaultTargetLangCode()
    }

    data class DictLookupResult(
        val word: String,
        val phonetic: String? = null,
        val targetLangCode: String,
        val primaryTranslation: String,
        val alternativeTranslations: List<String> = emptyList(),
        val monoDefinitions: List<Pair<String, String>> = emptyList(),
        val examples: List<Pair<String, String>> = emptyList(),
        val formattedDisplay: String
    )

    /**
     * Query bilingual dictionary entry with native monolingual definition + target language definition + dual examples.
     */
    suspend fun lookup(
        context: Context,
        rawWord: String,
        targetLang: String = getDefaultTargetLangCode()
    ): Result<DictLookupResult> = withContext(Dispatchers.IO) {
        val word = rawWord.trim().trim('"', '\'', '.', ',', '!', '?', ';', ':', '(', ')', '[', ']')
        if (word.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Empty word"))
        }

        val targetCode = normalizeLangCode(targetLang)
        val cacheKey = "${word.lowercase(Locale.ROOT)}|$targetCode"
        val prefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)

        // 1. Instant local cache lookup (0ms)
        // If cached entry has no examples, bypass cache to fetch full bilingual examples
        val cachedJson = prefs.getString(cacheKey, null)
        if (!cachedJson.isNullOrBlank()) {
            try {
                val parsed = parseCachedResult(JSONObject(cachedJson))
                if (parsed != null && parsed.examples.isNotEmpty()) {
                    return@withContext Result.success(parsed)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse cached dict entry", e)
            }
        }

        // Check if word is Chinese/Hanzi
        val isChineseChar = word.any { it in '\u4e00'..'\u9fff' }

        if (isChineseChar) {
            return@withContext handleChineseLookup(context, prefs, cacheKey, word, targetCode)
        } else {
            return@withContext handleEnglishLookup(context, prefs, cacheKey, word, targetCode)
        }
    }

    /**
     * Handle Chinese character / word lookup:
     * 1. Monolingual: Hanzi Dict (Pinyin, Radical, Strokes, Native Chinese Definition, Word Groups)
     * 2. Target Translation: Translate word/char into target language (e.g. English, French, etc.)
     * 3. Authentic Bilingual Examples: Practical contextual sentences with dual-language translations
     */
    private suspend fun handleChineseLookup(
        context: Context,
        prefs: android.content.SharedPreferences,
        cacheKey: String,
        word: String,
        targetCode: String
    ): Result<DictLookupResult> = withContext(Dispatchers.IO) {
        try {
            com.moyue.app.localai.DictionaryEngine.init(context)
            val localEntry = com.moyue.app.localai.DictionaryEngine.query(word)
            val localText = if (localEntry is com.moyue.app.data.models.DictionaryResult.Found) {
                localEntry.entry.formatForDisplay(context)
            } else {
                ""
            }

            val encoded = URLEncoder.encode(word, StandardCharsets.UTF_8.name())
            
            // 1. Fetch translation of Chinese word/character into target language
            var targetTrans = ""
            if (!targetCode.startsWith("zh", ignoreCase = true)) {
                // Extract clean native definition from localEntry
                var localDefn = ""
                if (localEntry is com.moyue.app.data.models.DictionaryResult.Found) {
                    val lines = localEntry.entry.translation.lines()
                    localDefn = lines.firstOrNull { l ->
                        val t = l.trim()
                        t.isNotEmpty() && !t.contains("【") && !t.contains("部首") && !t.contains("组词") && !t.startsWith("•") && t != word
                    }?.trim() ?: ""
                }

                // If we have a clear definition, translating the definition gives the most authoritative English translation!
                if (localDefn.isNotBlank()) {
                    val defEnc = URLEncoder.encode(localDefn, StandardCharsets.UTF_8.name())
                    val defPair = fetchMyMemoryTranslation("$MYMEMORY_URL?q=$defEnc&langpair=zh|$targetCode")
                    targetTrans = defPair?.first.orEmpty()
                }

                // Fallback to word query if definition translation was empty
                if (targetTrans.isBlank() || targetTrans.length <= 1) {
                    val pair = fetchMyMemoryTranslation("$MYMEMORY_URL?q=$encoded&langpair=zh|$targetCode")
                        ?: fetchGoogleTranslation(encoded, targetCode)
                    val cand = pair?.first.orEmpty()
                    if (cand.isNotBlank() && cand != word && cand.length > 1) {
                        targetTrans = cand
                    }
                }
            }

            // 2. Generate 2 natural Chinese example sentences
            val cnExamples = generateChineseExamples(word, localEntry)

            // 3. Concurrently translate Chinese example sentences into target language
            val bilingualExamples = mutableListOf<Pair<String, String>>()
            if (cnExamples.isNotEmpty()) {
                val transJobs = cnExamples.map { cnSent ->
                    async {
                        val transSent = if (!targetCode.startsWith("zh", ignoreCase = true)) {
                            translateTextToTarget(cnSent, targetCode)
                        } else {
                            ""
                        }
                        Pair(cnSent, transSent)
                    }
                }
                transJobs.forEach { job ->
                    try {
                        bilingualExamples.add(job.await())
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to translate Chinese example", e)
                    }
                }
            }

            val targetOption = SUPPORTED_LANGUAGES.find { it.code == targetCode }
            val targetLabel = targetOption?.displayName ?: targetCode

            val display = StringBuilder()
            if (localText.isNotBlank()) {
                display.append("[📖 现代汉语字典]\n").append(localText).append("\n\n")
            } else {
                display.append(word).append("\n\n")
            }

            if (targetTrans.isNotBlank() && !targetCode.startsWith("zh", ignoreCase = true)) {
                display.append("[$targetLabel]\n• ").append(targetTrans).append("\n\n")
            }

            if (bilingualExamples.isNotEmpty()) {
                display.append("[📝 Examples / 例句]\n")
                bilingualExamples.forEach { (cnEx, targetEx) ->
                    display.append("• ").append(cnEx).append("\n")
                    if (targetEx.isNotBlank()) {
                        display.append("  (").append(targetEx).append(")\n")
                    }
                }
            }

            val formatted = display.toString().trimEnd()
            val result = DictLookupResult(
                word = word,
                phonetic = null,
                targetLangCode = targetCode,
                primaryTranslation = targetTrans,
                examples = bilingualExamples,
                formattedDisplay = formatted
            )
            saveResultToCache(prefs, cacheKey, result)
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Hanzi lookup error", e)
            Result.failure(e)
        }
    }

    /**
     * Common Chinese idioms and natural usage examples for frequent characters.
     */
    private val CHINESE_CURATED_EXAMPLES = mapOf(
        "逐" to listOf(
            "我们要把方案中的各项任务逐一落实到位。",
            "年轻人在时代的大潮中勇于逐梦前行。"
        ),
        "是" to listOf(
            "这是一个非常关键且正确的决定。",
            "实事求是是认识事物和解决问题的根本方法。"
        ),
        "密" to listOf(
            "两国人民之间的文化交流密不可分。",
            "这里的保密工作做得密不透风。"
        ),
        "爱" to listOf(
            "人人都应当热爱生活并关爱他人。",
            "他对这套古典书籍爱不释手。"
        ),
        "勇" to listOf(
            "面对未知的挑战，我们需要有勇往直前的气魄。",
            "真正的勇士敢于直面生活中的困难。"
        ),
        "学" to listOf(
            "我们在工作中要坚持学以致用、知行合一。",
            "终身学习是适应时代发展的必备素养。"
        ),
        "书" to listOf(
            "读书可以开阔视野，丰富人的精神世界。",
            "他认真研读了这套具有深远影响的书籍。"
        ),
        "楣" to listOf(
            "光耀门楣是古代读书人一生的追求。",
            "今天出门遇上暴雨，真有点倒楣。"
        ),
        "走" to listOf(
            "我们要走群众路线，深入基层倾听声音。",
            "坚定不移地走高质量发展之路。"
        ),
        "看" to listOf(
            "我们要用发展的眼光看问题。",
            "从当前形势来看，各项工作进展顺利。"
        )
    )

    /**
     * Generate natural, textbook-quality Chinese example sentences for a character or word.
     * ZERO FLUFF: Returns genuine examples or empty list. Never generates generic templates!
     */
    private fun generateChineseExamples(
        word: String,
        entry: com.moyue.app.data.models.DictionaryResult?
    ): List<String> {
        val w = word.trim()

        // 1. Direct curated match
        CHINESE_CURATED_EXAMPLES[w]?.let { return it }

        // 2. If no curated match, check word groups from hanzi_dict
        val groups = mutableListOf<String>()
        if (entry is com.moyue.app.data.models.DictionaryResult.Found) {
            val trans = entry.entry.translation
            val regex = Regex("•\\s*([\\u4e00-\\u9fa5]{2,})")
            regex.findAll(trans).forEach { match ->
                val g = match.groupValues[1].trim()
                if (g.isNotEmpty() && g != w && !groups.contains(g)) {
                    if (!g.contains("县") && !g.contains("科技") && !g.contains("丁堡")) {
                        groups.add(g)
                    }
                }
            }
        }

        // Return empty list if no genuine examples exist (strictly no boilerplate fluff)
        return emptyList()
    }

    /**
     * Handle English word lookup:
     * 1. Monolingual: English-English Definition (Part of Speech + Precise English definition)
     * 2. Target Translation: Target language definition (e.g. Chinese, French, Spanish, Dutch, etc.)
     * 3. Authentic Bilingual Examples: 2 native English sentences with dual translations
     */
    private suspend fun handleEnglishLookup(
        context: Context,
        prefs: android.content.SharedPreferences,
        cacheKey: String,
        word: String,
        targetCode: String
    ): Result<DictLookupResult> = withContext(Dispatchers.IO) {
        // 1. Instant local phonetic extraction via ECDICT (0ms, 100% offline)
        var localPhonetic: String? = null
        var localCnTranslation: String? = null
        try {
            com.moyue.app.localai.DictionaryEngine.init(context)
            val localEntry = com.moyue.app.localai.DictionaryEngine.query(word)
            if (localEntry is com.moyue.app.data.models.DictionaryResult.Found) {
                localPhonetic = localEntry.entry.phonetic?.ifBlank { null }
                localCnTranslation = localEntry.entry.translation.replace("\\n", "\n").trim()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Local phonetic check failed", e)
        }

        val encodedWord = URLEncoder.encode(word, StandardCharsets.UTF_8.name())

        // 2. Concurrently fetch target language translation + English monolingual definitions & raw examples
        val transJob = async {
            if (targetCode.startsWith("zh", ignoreCase = true) && !localCnTranslation.isNullOrBlank()) {
                Pair(localCnTranslation, emptyList<String>())
            } else {
                var transPair = fetchGoogleTranslation(encodedWord, targetCode)
                if (transPair == null || transPair.first.isBlank()) {
                    val myMemoryUrl = "$MYMEMORY_URL?q=$encodedWord&langpair=autodetect|$targetCode"
                    transPair = fetchMyMemoryTranslation(myMemoryUrl)
                }
                transPair
            }
        }

        val monoAndExamplesJob = async {
            fetchMonolingualDefsAndExamples(encodedWord)
        }

        val transPair = transJob.await()
        val primaryTrans = transPair?.first.orEmpty()
        val alternatives = transPair?.second.orEmpty()

        val (monoDefs, rawExamples) = monoAndExamplesJob.await()

        if (primaryTrans.isBlank() && monoDefs.isEmpty()) {
            return@withContext Result.failure(Exception("No definition found"))
        }

        // 3. Concurrently translate raw examples into target language (up to 2 examples)
        val bilingualExamples = mutableListOf<Pair<String, String>>()
        if (rawExamples.isNotEmpty()) {
            val transExampleJobs = rawExamples.take(2).map { rawEx ->
                async {
                    val exTranslated = translateTextToTarget(rawEx, targetCode)
                    Pair(rawEx, exTranslated)
                }
            }
            transExampleJobs.forEach { deferred ->
                try {
                    bilingualExamples.add(deferred.await())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resolve example translation", e)
                }
            }
        }

        val targetOption = SUPPORTED_LANGUAGES.find { it.code == targetCode }
        val targetLabel = targetOption?.displayName ?: targetCode

        // 4. Assemble formatted display
        val displayBuilder = StringBuilder()
        displayBuilder.append(word)
        if (!localPhonetic.isNullOrBlank()) {
            displayBuilder.append("  /").append(localPhonetic).append("/")
        }
        displayBuilder.append("\n\n")

        // ① 英文原语释义 (English-English Dictionary)
        if (monoDefs.isNotEmpty()) {
            displayBuilder.append("[📖 English Definition / 英文释义]\n")
            monoDefs.take(3).forEach { (pos, def) ->
                val posStr = if (pos.isNotBlank()) "($pos) " else ""
                displayBuilder.append("• ").append(posStr).append(def).append("\n")
            }
            displayBuilder.append("\n")
        }

        // ② 目标语言释义 (Target Language Translation)
        displayBuilder.append("[$targetLabel]\n")
        if (primaryTrans.contains("\n")) {
            displayBuilder.append(primaryTrans).append("\n")
        } else {
            displayBuilder.append("• ").append(primaryTrans).append("\n")
            alternatives.take(3).forEach { alt ->
                if (!alt.equals(primaryTrans, ignoreCase = true)) {
                    displayBuilder.append("• ").append(alt).append("\n")
                }
            }
        }

        // ③ 双语例句 (Bilingual Examples)
        if (bilingualExamples.isNotEmpty()) {
            displayBuilder.append("\n[📝 Examples / 例句]\n")
            bilingualExamples.forEach { (enEx, targetEx) ->
                displayBuilder.append("• ").append(enEx).append("\n")
                if (targetEx.isNotBlank() && !targetEx.equals(enEx, ignoreCase = true)) {
                    displayBuilder.append("  (").append(targetEx).append(")\n")
                }
            }
        }

        val formatted = displayBuilder.toString().trimEnd()

        val result = DictLookupResult(
            word = word,
            phonetic = localPhonetic,
            targetLangCode = targetCode,
            primaryTranslation = primaryTrans,
            alternativeTranslations = alternatives,
            monoDefinitions = monoDefs,
            examples = bilingualExamples,
            formattedDisplay = formatted
        )

        // Save to cache for offline repeat lookups
        saveResultToCache(prefs, cacheKey, result)

        Result.success(result)
    }

    /**
     * Primary fast channel: Google Translate API (aligned with Moreader Chrome Extension)
     */
    private fun fetchGoogleTranslation(encodedWord: String, targetCode: String): Pair<String, List<String>>? {
        val now = System.currentTimeMillis()
        if (isGoogleReachable == false && now - lastGoogleCheckTime < 60000) {
            return null
        }

        val clients = listOf("dict-chrome-ex", "gtx")
        for (client in clients) {
            try {
                val url = "$GOOGLE_GTX_URL?client=$client&dt=t&dt=bd&dj=1&ie=UTF-8&sl=auto&tl=$targetCode&q=$encodedWord"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/129.0.0.0 Mobile Safari/537.36")
                    .addHeader("Accept", "application/json")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: continue
                    val json = JSONObject(body)

                    var primary = ""
                    val sentences = json.optJSONArray("sentences")
                    if (sentences != null && sentences.length() > 0) {
                        primary = cleanTranslationText(sentences.getJSONObject(0).optString("trans", ""))
                    }

                    val alternatives = mutableListOf<String>()
                    val dictArr = json.optJSONArray("dict")
                    if (dictArr != null) {
                        for (i in 0 until dictArr.length()) {
                            val entryObj = dictArr.optJSONObject(i) ?: continue
                            val termsArr = entryObj.optJSONArray("terms")
                            if (termsArr != null) {
                                for (j in 0 until termsArr.length()) {
                                    val t = cleanTranslationText(termsArr.optString(j, ""))
                                    if (t.isNotBlank() && !t.equals(primary, ignoreCase = true) && !alternatives.contains(t)) {
                                        alternatives.add(t)
                                    }
                                }
                            }
                        }
                    }

                    if (primary.isNotBlank()) {
                        isGoogleReachable = true
                        lastGoogleCheckTime = System.currentTimeMillis()
                        return Pair(primary, alternatives)
                    }
                }
            } catch (e: Exception) {
                // Client attempt failed, proceed to next
            }
        }

        isGoogleReachable = false
        lastGoogleCheckTime = System.currentTimeMillis()
        return null
    }

    /**
     * Fallback channel: MyMemory Translation API
     */
    private fun fetchMyMemoryTranslation(url: String): Pair<String, List<String>>? {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)

            val responseData = json.optJSONObject("responseData")
            val rawPrimary = responseData?.optString("translatedText")?.trim() ?: ""
            val primary = cleanTranslationText(rawPrimary)

            val alternatives = mutableListOf<String>()
            val matches = json.optJSONArray("matches")
            if (matches != null) {
                for (i in 0 until matches.length()) {
                    val m = matches.optJSONObject(i) ?: continue
                    val t = cleanTranslationText(m.optString("translation", "").trim())
                    if (t.isNotBlank() && !t.equals(primary, ignoreCase = true) && !alternatives.contains(t)) {
                        alternatives.add(t)
                    }
                }
            }
            Pair(primary, alternatives)
        } catch (e: Exception) {
            Log.w(TAG, "MyMemory fetch error", e)
            null
        }
    }

    /**
     * Concurrently fetch English monolingual definitions + real-world example sentences
     */
    private fun fetchMonolingualDefsAndExamples(encodedWord: String): Pair<List<Pair<String, String>>, List<String>> {
        return try {
            val url = "$FREE_DICT_URL/$encodedWord"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return Pair(emptyList(), emptyList())
            val body = response.body?.string() ?: return Pair(emptyList(), emptyList())
            val array = JSONArray(body)
            if (array.length() == 0) return Pair(emptyList(), emptyList())

            val definitions = mutableListOf<Pair<String, String>>()
            val examples = mutableListOf<String>()

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val meanings = item.optJSONArray("meanings") ?: continue
                for (j in 0 until meanings.length()) {
                    val m = meanings.optJSONObject(j) ?: continue
                    val pos = m.optString("partOfSpeech", "").trim()
                    val defs = m.optJSONArray("definitions") ?: continue
                    for (k in 0 until defs.length()) {
                        val d = defs.optJSONObject(k) ?: continue
                        val defText = d.optString("definition", "").trim()
                        if (defText.isNotBlank() && definitions.none { it.second == defText }) {
                            definitions.add(Pair(pos, defText))
                        }
                        val ex = d.optString("example", "").trim()
                        if (ex.isNotBlank() && !examples.contains(ex)) {
                            examples.add(ex)
                        }
                    }
                }
            }

            // Clean examples: only real-world authentic examples from dictionary (strictly no boilerplate template synthesis)
            Pair(definitions.take(3), examples.take(2))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch mono defs & examples", e)
            Pair(emptyList(), emptyList())
        }
    }

    /**
     * Synthesize natural context sentence when standard dictionary definitions lack explicit example tags.
     */
    private fun synthesizeContextExample(encodedWord: String, pos: String, definition: String): String {
        val word = java.net.URLDecoder.decode(encodedWord, StandardCharsets.UTF_8.name()).trim()
        val p = pos.lowercase(Locale.ROOT)
        var cleanDef = definition.trim().removeSuffix(".")
        if (cleanDef.isNotEmpty()) {
            cleanDef = cleanDef.substring(0, 1).lowercase(Locale.ROOT) + cleanDef.substring(1)
        }

        return when {
            p.contains("noun") -> "The concept of $word reflects $cleanDef."
            p.contains("adj") -> "It is described as $word, conveying the quality of being $cleanDef."
            p.contains("verb") -> "To $word essentially means to $cleanDef."
            p.contains("adv") -> "The action was performed $word, in a manner characterized by $cleanDef."
            else -> "In context, '$word' illustrates $cleanDef."
        }
    }

    /**
     * Translate an example sentence into target language using Google / MyMemory
     */
    private fun translateTextToTarget(text: String, targetCode: String): String {
        val trimmed = text.trim()
        if (trimmed.isBlank() || targetCode.equals("en", ignoreCase = true)) return trimmed

        val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
        try {
            val url = "$GOOGLE_GTX_URL?client=gtx&dt=t&dj=1&ie=UTF-8&sl=auto&tl=$targetCode&q=$encoded"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val sentences = json.optJSONArray("sentences")
                if (sentences != null && sentences.length() > 0) {
                    val sb = StringBuilder()
                    for (i in 0 until sentences.length()) {
                        sb.append(sentences.getJSONObject(i).optString("trans", ""))
                    }
                    val trans = cleanTranslationText(sb.toString())
                    if (trans.isNotBlank()) return trans
                }
            }
        } catch (e: Exception) {
            // fallback
        }

        // Fallback to MyMemory
        return try {
            val url = "$MYMEMORY_URL?q=$encoded&langpair=autodetect|$targetCode"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val trans = json.optJSONObject("responseData")?.optString("translatedText", "") ?: ""
                cleanTranslationText(trans)
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun cleanTranslationText(text: String): String {
        return text
            .removeSurrounding("\"")
            .removeSurrounding("“", "”")
            .removeSurrounding("«", "»")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    private fun saveResultToCache(prefs: android.content.SharedPreferences, key: String, result: DictLookupResult) {
        try {
            val json = JSONObject().apply {
                put("word", result.word)
                put("phonetic", result.phonetic ?: "")
                put("targetLangCode", result.targetLangCode)
                put("primaryTranslation", result.primaryTranslation)
                put("alternatives", JSONArray(result.alternativeTranslations))
                val monoArr = JSONArray()
                result.monoDefinitions.forEach { (pos, def) ->
                    monoArr.put(JSONObject().apply {
                        put("pos", pos)
                        put("def", def)
                    })
                }
                put("monoDefinitions", monoArr)
                val exArr = JSONArray()
                result.examples.forEach { (en, target) ->
                    exArr.put(JSONObject().apply {
                        put("en", en)
                        put("target", target)
                    })
                }
                put("examples", exArr)
                put("formattedDisplay", result.formattedDisplay)
            }
            prefs.edit().putString(key, json.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save dict result to cache", e)
        }
    }

    private fun parseCachedResult(json: JSONObject): DictLookupResult? {
        val word = json.optString("word", "")
        if (word.isBlank()) return null
        val phonetic = json.optString("phonetic", "").ifBlank { null }
        val targetCode = json.optString("targetLangCode", "")
        val primary = json.optString("primaryTranslation", "")
        val alts = mutableListOf<String>()
        val altArr = json.optJSONArray("alternatives")
        if (altArr != null) {
            for (i in 0 until altArr.length()) {
                val item = altArr.optString(i, "").trim()
                if (item.isNotBlank()) alts.add(item)
            }
        }
        val monoDefs = mutableListOf<Pair<String, String>>()
        val monoArr = json.optJSONArray("monoDefinitions")
        if (monoArr != null) {
            for (i in 0 until monoArr.length()) {
                val obj = monoArr.optJSONObject(i) ?: continue
                val pos = obj.optString("pos", "")
                val def = obj.optString("def", "")
                if (def.isNotBlank()) monoDefs.add(Pair(pos, def))
            }
        }
        val examples = mutableListOf<Pair<String, String>>()
        val exArr = json.optJSONArray("examples")
        if (exArr != null) {
            for (i in 0 until exArr.length()) {
                val obj = exArr.optJSONObject(i) ?: continue
                val en = obj.optString("en", "")
                val target = obj.optString("target", "")
                if (en.isNotBlank()) examples.add(Pair(en, target))
            }
        }
        val formatted = json.optString("formattedDisplay", "")
        return DictLookupResult(
            word = word,
            phonetic = phonetic,
            targetLangCode = targetCode,
            primaryTranslation = primary,
            alternativeTranslations = alts,
            monoDefinitions = monoDefs,
            examples = examples,
            formattedDisplay = formatted
        )
    }
}
