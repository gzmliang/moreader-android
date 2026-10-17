package com.moyue.app.translate

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Universal fast bilingual dictionary engine aligned with Moreader Web Extension.
 * Features:
 * - Dual-channel translation: Google Translate (primary, ~200ms) with circuit breaker + MyMemory (fallback)
 * - Local offline phonetic extraction via ECDICT (0ms, no network delay)
 * - Persistent disk caching via SharedPreferences for instantaneous repeat queries
 */
object OnlineBilingualDictionary {

    private const val TAG = "OnlineBilingualDict"
    private const val PREFS_CACHE = "online_dict_cache"
    private const val GOOGLE_GTX_URL = "https://translate.googleapis.com/translate_a/single"
    private const val MYMEMORY_URL = "https://api.mymemory.translated.net/get"

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
        .readTimeout(4, TimeUnit.SECONDS)
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
        val englishDefinitions: List<Pair<String, String>> = emptyList(),
        val formattedDisplay: String
    )

    /**
     * Query bilingual dictionary entry for a word.
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
        val cachedJson = prefs.getString(cacheKey, null)
        if (!cachedJson.isNullOrBlank()) {
            try {
                val parsed = parseCachedResult(JSONObject(cachedJson))
                if (parsed != null) return@withContext Result.success(parsed)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse cached dict entry", e)
            }
        }

        // 2. Instant local phonetic extraction via ECDICT (0ms, 100% offline)
        var localPhonetic: String? = null
        try {
            com.moyue.app.localai.DictionaryEngine.init(context)
            val localEntry = com.moyue.app.localai.DictionaryEngine.query(word)
            if (localEntry is com.moyue.app.data.models.DictionaryResult.Found) {
                localPhonetic = localEntry.entry.phonetic?.ifBlank { null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Local phonetic check failed", e)
        }

        val encodedWord = URLEncoder.encode(word, StandardCharsets.UTF_8.name())

        // 3. Fast Dual-Channel Translation (Google Translate first -> fallback MyMemory)
        var transPair = fetchGoogleTranslation(encodedWord, targetCode)
        if (transPair == null || transPair.first.isBlank()) {
            // Fallback to MyMemory
            val myMemoryUrl = "$MYMEMORY_URL?q=$encodedWord&langpair=autodetect|$targetCode"
            transPair = fetchMyMemoryTranslation(myMemoryUrl)
        }

        val primaryTrans = transPair?.first.orEmpty()
        val alternatives = transPair?.second.orEmpty()

        if (primaryTrans.isBlank()) {
            return@withContext Result.failure(Exception("No translation found"))
        }

        val targetOption = SUPPORTED_LANGUAGES.find { it.code == targetCode }
        val targetLabel = targetOption?.displayName ?: targetCode

        // Build formatted display markdown
        val displayBuilder = StringBuilder()
        displayBuilder.append(word)
        if (!localPhonetic.isNullOrBlank()) {
            displayBuilder.append("  /").append(localPhonetic).append("/")
        }
        displayBuilder.append("\n\n")

        displayBuilder.append("[$targetLabel]\n")
        displayBuilder.append("• ").append(primaryTrans).append("\n")
        alternatives.take(4).forEach { alt ->
            if (!alt.equals(primaryTrans, ignoreCase = true)) {
                displayBuilder.append("• ").append(alt).append("\n")
            }
        }

        val formatted = displayBuilder.toString().trimEnd()

        val result = DictLookupResult(
            word = word,
            phonetic = localPhonetic,
            targetLangCode = targetCode,
            primaryTranslation = primaryTrans,
            alternativeTranslations = alternatives,
            englishDefinitions = emptyList(),
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
                put("alternatives", org.json.JSONArray(result.alternativeTranslations))
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
        val formatted = json.optString("formattedDisplay", "")
        return DictLookupResult(
            word = word,
            phonetic = phonetic,
            targetLangCode = targetCode,
            primaryTranslation = primary,
            alternativeTranslations = alts,
            englishDefinitions = emptyList(),
            formattedDisplay = formatted
        )
    }
}
