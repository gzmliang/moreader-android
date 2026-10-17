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
 * Universal online bilingual dictionary engine for overseas and multilingual users.
 * Supports arbitrary source language -> arbitrary target language translation (e.g. EN->ES, FR->EN, DE->JA, etc.).
 * Features:
 * - Free, public APIs without API keys (MyMemory + Free Dictionary API)
 * - Automatic IPA phonetics and English definition breakdown for English words
 * - Local disk caching via SharedPreferences for instantaneous offline repeat lookups
 */
object OnlineBilingualDictionary {

    private const val TAG = "OnlineBilingualDict"
    private const val PREFS_CACHE = "online_dict_cache"

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
        LanguageOption("ar", "Arabic", "🇸🇦 العربية"),
        LanguageOption("vi", "Vietnamese", "🇻🇳 Tiếng Việt"),
        LanguageOption("th", "Thai", "🇹🇭 ไทย"),
        LanguageOption("id", "Indonesian", "🇮🇩 Bahasa Indonesia"),
        LanguageOption("hi", "Hindi", "🇮🇳 हिन्दी")
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Detect default target language code based on device system locale.
     * E.g. device in Spain -> "es", device in France -> "fr", device in China -> "zh-CN".
     * Defaults to "en" if no specific match.
     */
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
            "ar" -> "ar"
            "vi" -> "vi"
            "th" -> "th"
            "id" -> "id"
            "hi" -> "hi"
            else -> "en"
        }
    }

    /**
     * Map standard language name or code to two-letter code.
     */
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
        val word = rawWord.trim().trim('"', '\'', '.', ',', '!', '?', ';', ':')
        if (word.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Empty word"))
        }

        val targetCode = normalizeLangCode(targetLang)
        val cacheKey = "${word.lowercase(Locale.ROOT)}|$targetCode"
        val prefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)

        // 1. Check local persistent cache
        val cachedJson = prefs.getString(cacheKey, null)
        if (!cachedJson.isNullOrBlank()) {
            try {
                val parsed = parseCachedResult(JSONObject(cachedJson))
                if (parsed != null) return@withContext Result.success(parsed)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse cached dict entry", e)
            }
        }

        // 2. Query online APIs concurrently
        try {
            val isLatinWord = word.all { it in 'a'..'z' || it in 'A'..'Z' || it == '-' || it == '\'' || it == ' ' }

            val encodedWord = URLEncoder.encode(word, StandardCharsets.UTF_8.name())
            val myMemoryUrl = "https://api.mymemory.translated.net/get?q=$encodedWord&langpair=autodetect|$targetCode"

            // Concurrently fetch MyMemory translation + optional English phonetics/definitions
            val translationDeferred = async { fetchMyMemoryTranslation(myMemoryUrl) }
            val phoneticDeferred = async {
                if (isLatinWord) fetchDictionaryApiPhonetic(encodedWord) else null
            }

            val transPair = translationDeferred.await()
            val primaryTrans = transPair?.first.orEmpty()
            val alternatives = transPair?.second.orEmpty()

            val dictApiData = phoneticDeferred.await()
            val phonetic = dictApiData?.first
            val enDefinitions = dictApiData?.second.orEmpty()

            if (primaryTrans.isBlank() && enDefinitions.isEmpty()) {
                return@withContext Result.failure(Exception("No translation found"))
            }

            val targetOption = SUPPORTED_LANGUAGES.find { it.code == targetCode }
            val targetLabel = targetOption?.displayName ?: targetCode

            // Build formatted display markdown
            val displayBuilder = StringBuilder()
            displayBuilder.append(word)
            if (!phonetic.isNullOrBlank()) {
                displayBuilder.append("  ").append(phonetic)
            }
            displayBuilder.append("\n\n")

            displayBuilder.append("[$targetLabel]\n")
            if (primaryTrans.isNotBlank()) {
                displayBuilder.append("• ").append(primaryTrans).append("\n")
            }
            alternatives.take(4).forEach { alt ->
                if (!alt.equals(primaryTrans, ignoreCase = true)) {
                    displayBuilder.append("• ").append(alt).append("\n")
                }
            }

            if (enDefinitions.isNotEmpty()) {
                displayBuilder.append("\n[English Definitions]\n")
                enDefinitions.take(3).forEach { (pos, def) ->
                    val posLabel = if (pos.isNotBlank()) "($pos) " else ""
                    displayBuilder.append("• ").append(posLabel).append(def).append("\n")
                }
            }

            val formatted = displayBuilder.toString().trimEnd()

            val result = DictLookupResult(
                word = word,
                phonetic = phonetic,
                targetLangCode = targetCode,
                primaryTranslation = primaryTrans,
                alternativeTranslations = alternatives,
                englishDefinitions = enDefinitions,
                formattedDisplay = formatted
            )

            // Cache result to disk
            saveResultToCache(prefs, cacheKey, result)

            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up word '$word': ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun fetchMyMemoryTranslation(url: String): Pair<String, List<String>>? {
        return try {
            val request = Request.Builder().url(url).build()
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

    private fun fetchDictionaryApiPhonetic(encodedWord: String): Pair<String?, List<Pair<String, String>>>? {
        return try {
            val url = "https://api.dictionaryapi.dev/api/v2/entries/en/$encodedWord"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val array = JSONArray(body)
            if (array.length() == 0) return null
            val first = array.getJSONObject(0)

            var phonetic = first.optString("phonetic", "").trim()
            if (phonetic.isBlank()) {
                val phoneticsArr = first.optJSONArray("phonetics")
                if (phoneticsArr != null) {
                    for (i in 0 until phoneticsArr.length()) {
                        val pObj = phoneticsArr.optJSONObject(i) ?: continue
                        val text = pObj.optString("text", "").trim()
                        if (text.isNotBlank()) {
                            phonetic = text
                            break
                        }
                    }
                }
            }

            val definitions = mutableListOf<Pair<String, String>>()
            val meanings = first.optJSONArray("meanings")
            if (meanings != null) {
                for (i in 0 until meanings.length()) {
                    val m = meanings.optJSONObject(i) ?: continue
                    val pos = m.optString("partOfSpeech", "").trim()
                    val defs = m.optJSONArray("definitions")
                    if (defs != null && defs.length() > 0) {
                        val defObj = defs.optJSONObject(0)
                        val defText = defObj?.optString("definition", "")?.trim() ?: ""
                        if (defText.isNotBlank()) {
                            definitions.add(Pair(pos, defText))
                        }
                    }
                }
            }

            Pair(phonetic.ifBlank { null }, definitions)
        } catch (e: Exception) {
            Log.w(TAG, "DictionaryAPI fetch error", e)
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
                put("alternatives", JSONArray(result.alternativeTranslations))
                val enDefsArr = JSONArray()
                result.englishDefinitions.forEach { (pos, def) ->
                    enDefsArr.put(JSONObject().apply {
                        put("pos", pos)
                        put("def", def)
                    })
                }
                put("englishDefinitions", enDefsArr)
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
        val enDefs = mutableListOf<Pair<String, String>>()
        val enDefsArr = json.optJSONArray("englishDefinitions")
        if (enDefsArr != null) {
            for (i in 0 until enDefsArr.length()) {
                val obj = enDefsArr.optJSONObject(i) ?: continue
                val pos = obj.optString("pos", "")
                val def = obj.optString("def", "")
                if (def.isNotBlank()) enDefs.add(Pair(pos, def))
            }
        }
        val formatted = json.optString("formattedDisplay", "")
        return DictLookupResult(
            word = word,
            phonetic = phonetic,
            targetLangCode = targetCode,
            primaryTranslation = primary,
            alternativeTranslations = alts,
            englishDefinitions = enDefs,
            formattedDisplay = formatted
        )
    }
}
