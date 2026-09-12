package com.moyue.app.ui.components

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Edge TTS voice definition with locale and gender metadata.
 * nameRes is a string resource ID for localized display (0 if none).
 */
data class EdgeVoice(
    val id: String,
    val nameRes: Int = 0,  // @StringRes
    val gender: String = "female",  // "male", "female", or "child"
    val locale: Locale = Locale.US,
    val customName: String? = null,
)

/**
 * Get display name for an Edge voice in current locale.
 */
internal fun EdgeVoice.displayName(context: Context): String {
    if (nameRes != 0) {
        try {
            return context.getString(nameRes)
        } catch (_: Exception) {}
    }
    if (!customName.isNullOrBlank()) {
        return customName
    }
    return extractVoiceSimpleName(id)
}

/**
 * Helper to extract human-friendly voice name from shortName
 * e.g. "en-US-JennyNeural" -> "Jenny"
 *      "en-US-AndrewMultilingualNeural" -> "Andrew (Multi)"
 */
internal fun extractVoiceSimpleName(voiceId: String): String {
    val raw = voiceId.substringAfterLast("-").removeSuffix("Neural")
    return if (raw.endsWith("Multilingual")) {
        raw.removeSuffix("Multilingual") + " (Multi)"
    } else {
        raw
    }
}

/**
 * Built-in default voices guaranteed to be currently supported by Microsoft Edge-TTS.
 * Used for offline fallback and initial display before dynamic sync.
 */
internal val DEFAULT_EDGE_VOICES: List<EdgeVoice> = listOf(
    // Chinese - zh-CN
    EdgeVoice("zh-CN-XiaoxiaoNeural", com.moyue.app.R.string.voice_xiaoxiao, "female", Locale.CHINA),
    EdgeVoice("zh-CN-YunxiNeural", com.moyue.app.R.string.voice_yunxi, "male", Locale.CHINA),
    EdgeVoice("zh-CN-YunjianNeural", com.moyue.app.R.string.voice_yunjian, "male", Locale.CHINA),
    EdgeVoice("zh-CN-XiaoyiNeural", com.moyue.app.R.string.voice_xiaoyi, "female", Locale.CHINA),
    EdgeVoice("zh-CN-YunyangNeural", 0, "male", Locale.CHINA, "云扬"),
    EdgeVoice("zh-CN-YunxiaNeural", 0, "male", Locale.CHINA, "云霞"),
    EdgeVoice("zh-CN-liaoning-XiaobeiNeural", 0, "female", Locale.CHINA, "东北晓北"),
    EdgeVoice("zh-CN-shaanxi-XiaoniNeural", 0, "female", Locale.CHINA, "陕西晓妮"),

    // Chinese - zh-TW
    EdgeVoice("zh-TW-HsiaoChenNeural", com.moyue.app.R.string.voice_hsiaochen, "female", Locale.TAIWAN),
    EdgeVoice("zh-TW-HsiaoYuNeural", com.moyue.app.R.string.voice_hsiaochen, "female", Locale.TAIWAN),
    EdgeVoice("zh-TW-YunJheNeural", com.moyue.app.R.string.voice_yunjhe, "male", Locale.TAIWAN),

    // Chinese - zh-HK
    EdgeVoice("zh-HK-HiuMaanNeural", com.moyue.app.R.string.voice_hsiaochen, "female", Locale.forLanguageTag("zh-HK")),
    EdgeVoice("zh-HK-HiuGaaiNeural", com.moyue.app.R.string.voice_hsiaochen, "female", Locale.forLanguageTag("zh-HK")),
    EdgeVoice("zh-HK-WanLungNeural", com.moyue.app.R.string.voice_yunjhe, "male", Locale.forLanguageTag("zh-HK")),

    // English - US
    EdgeVoice("en-US-JennyNeural", 0, "female", Locale.US, "Jenny"),
    EdgeVoice("en-US-GuyNeural", 0, "male", Locale.US, "Guy"),
    EdgeVoice("en-US-AriaNeural", 0, "female", Locale.US, "Aria"),
    EdgeVoice("en-US-AndrewNeural", 0, "male", Locale.US, "Andrew"),
    EdgeVoice("en-US-ChristopherNeural", 0, "male", Locale.US, "Christopher"),
    EdgeVoice("en-US-EricNeural", 0, "male", Locale.US, "Eric"),
    EdgeVoice("en-US-MichelleNeural", 0, "female", Locale.US, "Michelle"),
    EdgeVoice("en-US-RogerNeural", 0, "male", Locale.US, "Roger"),
    EdgeVoice("en-US-SteffanNeural", 0, "male", Locale.US, "Steffan"),
    EdgeVoice("en-US-AnaNeural", 0, "female", Locale.US, "Ana"),
    EdgeVoice("en-US-AvaNeural", 0, "female", Locale.US, "Ava"),
    EdgeVoice("en-US-BrianNeural", 0, "male", Locale.US, "Brian"),
    EdgeVoice("en-US-EmmaNeural", 0, "female", Locale.US, "Emma"),

    // English - GB
    EdgeVoice("en-GB-SoniaNeural", 0, "female", Locale.UK, "Sonia"),
    EdgeVoice("en-GB-RyanNeural", 0, "male", Locale.UK, "Ryan"),
    EdgeVoice("en-GB-LibbyNeural", 0, "female", Locale.UK, "Libby"),
    EdgeVoice("en-GB-MaisieNeural", 0, "child", Locale.UK, "Maisie"),
    EdgeVoice("en-GB-ThomasNeural", 0, "male", Locale.UK, "Thomas"),

    // Japanese
    EdgeVoice("ja-JP-NanamiNeural", com.moyue.app.R.string.voice_nanami, "female", Locale.JAPAN),
    EdgeVoice("ja-JP-KeitaNeural", com.moyue.app.R.string.voice_keita, "male", Locale.JAPAN),

    // Korean
    EdgeVoice("ko-KR-SunHiNeural", com.moyue.app.R.string.voice_sunhi, "female", Locale.KOREA),
    EdgeVoice("ko-KR-InJoonNeural", com.moyue.app.R.string.voice_injun, "male", Locale.KOREA),
    EdgeVoice("ko-KR-HyunsuMultilingualNeural", 0, "male", Locale.KOREA, "Hyunsu (Multi)"),

    // German
    EdgeVoice("de-DE-KatjaNeural", 0, "female", Locale.GERMANY, "Katja"),
    EdgeVoice("de-DE-ConradNeural", 0, "male", Locale.GERMANY, "Conrad"),
    EdgeVoice("de-DE-AmalaNeural", 0, "female", Locale.GERMANY, "Amala"),
    EdgeVoice("de-DE-KillianNeural", 0, "male", Locale.GERMANY, "Killian"),

    // French
    EdgeVoice("fr-FR-DeniseNeural", 0, "female", Locale.FRANCE, "Denise"),
    EdgeVoice("fr-FR-HenriNeural", 0, "male", Locale.FRANCE, "Henri"),
    EdgeVoice("fr-FR-EloiseNeural", 0, "child", Locale.FRANCE, "Eloise"),

    // Spanish
    EdgeVoice("es-ES-ElviraNeural", 0, "female", Locale.forLanguageTag("es-ES"), "Elvira"),
    EdgeVoice("es-ES-AlvaroNeural", 0, "male", Locale.forLanguageTag("es-ES"), "Alvaro"),

    // Russian
    EdgeVoice("ru-RU-SvetlanaNeural", 0, "female", Locale.forLanguageTag("ru-RU"), "Svetlana"),
    EdgeVoice("ru-RU-DmitryNeural", 0, "male", Locale.forLanguageTag("ru-RU"), "Dmitry"),

    // Italian
    EdgeVoice("it-IT-ElsaNeural", 0, "female", Locale.ITALY, "Elsa"),
    EdgeVoice("it-IT-DiegoNeural", 0, "male", Locale.ITALY, "Diego"),

    // Portuguese - Brazil
    EdgeVoice("pt-BR-FranciscaNeural", 0, "female", Locale.forLanguageTag("pt-BR"), "Francisca"),
    EdgeVoice("pt-BR-AntonioNeural", 0, "male", Locale.forLanguageTag("pt-BR"), "Antonio"),
)

/**
 * Backward compatibility alias for EDGE_VOICES
 */
internal val EDGE_VOICES: List<EdgeVoice> get() = DEFAULT_EDGE_VOICES

/**
 * Dynamic Edge TTS Voice Manager:
 * - Automatically synchronizes with Microsoft official voices via the server GET /voices endpoint.
 * - Caches voices locally in SharedPreferences for instantaneous 0ms startup.
 * - Seamlessly falls back to DEFAULT_EDGE_VOICES if offline.
 */
object EdgeVoiceManager {
    private const val PREFS_NAME = "edge_voices_store"
    private const val KEY_VOICES_JSON = "cached_voices_json"

    private var memoryVoices: List<EdgeVoice>? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val builtinNameResMap = mapOf(
        "zh-CN-XiaoxiaoNeural" to com.moyue.app.R.string.voice_xiaoxiao,
        "zh-CN-YunxiNeural" to com.moyue.app.R.string.voice_yunxi,
        "zh-CN-YunjianNeural" to com.moyue.app.R.string.voice_yunjian,
        "zh-CN-XiaoyiNeural" to com.moyue.app.R.string.voice_xiaoyi,
        "zh-TW-HsiaoChenNeural" to com.moyue.app.R.string.voice_hsiaochen,
        "zh-TW-HsiaoYuNeural" to com.moyue.app.R.string.voice_hsiaochen,
        "zh-TW-YunJheNeural" to com.moyue.app.R.string.voice_yunjhe,
        "zh-HK-HiuMaanNeural" to com.moyue.app.R.string.voice_hsiaochen,
        "zh-HK-HiuGaaiNeural" to com.moyue.app.R.string.voice_hsiaochen,
        "zh-HK-WanLungNeural" to com.moyue.app.R.string.voice_yunjhe,
        "ja-JP-NanamiNeural" to com.moyue.app.R.string.voice_nanami,
        "ja-JP-KeitaNeural" to com.moyue.app.R.string.voice_keita,
        "ko-KR-SunHiNeural" to com.moyue.app.R.string.voice_sunhi,
        "ko-KR-InJoonNeural" to com.moyue.app.R.string.voice_injun,
    )

    fun getVoices(context: Context): List<EdgeVoice> {
        memoryVoices?.let { return it }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_VOICES_JSON, null)
        if (!jsonStr.isNullOrBlank()) {
            val parsed = parseVoicesJson(jsonStr)
            if (parsed.isNotEmpty()) {
                memoryVoices = parsed
                return parsed
            }
        }
        return DEFAULT_EDGE_VOICES.also { memoryVoices = it }
    }

    suspend fun syncVoices(context: Context, endpoint: String): List<EdgeVoice>? = withContext(Dispatchers.IO) {
        val clean = endpoint.removeSuffix("/")
        val defaultServer = "http://p-plus.duckdns.org:5001"
        val fallbackServer = "http://powerplus.blogsyte.com:5001"
        val endpoints = if (clean == defaultServer || clean == fallbackServer) {
            listOf(clean, if (clean == defaultServer) fallbackServer else defaultServer)
        } else {
            listOf(clean)
        }

        for (ep in endpoints) {
            try {
                val req = Request.Builder().url("$ep/voices").get().build()
                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string()
                    if (!bodyStr.isNullOrBlank()) {
                        val parsed = parseVoicesJson(bodyStr)
                        if (parsed.isNotEmpty()) {
                            memoryVoices = parsed
                            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putString(KEY_VOICES_JSON, bodyStr)
                                .apply()
                            return@withContext parsed
                        }
                    }
                }
            } catch (_: Exception) {
                // Try fallback endpoint
            }
        }
        null
    }

    private fun parseVoicesJson(jsonStr: String): List<EdgeVoice> {
        val list = mutableListOf<EdgeVoice>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val shortName = obj.optString("ShortName", "")
                if (shortName.isBlank()) continue
                val genderRaw = obj.optString("Gender", "female").lowercase()
                val localeTag = obj.optString("Locale", "")
                val locale = if (localeTag.isNotEmpty()) Locale.forLanguageTag(localeTag) else Locale.US
                val nameRes = builtinNameResMap[shortName] ?: 0
                val simpleName = extractVoiceSimpleName(shortName)

                list.add(
                    EdgeVoice(
                        id = shortName,
                        nameRes = nameRes,
                        gender = genderRaw,
                        locale = locale,
                        customName = simpleName,
                    )
                )
            }
        } catch (_: Exception) {}

        if (list.isEmpty()) return emptyList()

        // 智能排序：优先常用推荐音色排在各语种最前面
        val priorityIds = listOf(
            "zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural", "zh-CN-YunjianNeural", "zh-CN-XiaoyiNeural",
            "en-US-JennyNeural", "en-US-GuyNeural", "en-US-AriaNeural", "en-GB-RyanNeural",
            "ja-JP-NanamiNeural", "ja-JP-KeitaNeural", "ko-KR-SunHiNeural", "ko-KR-InJoonNeural"
        )
        return list.sortedWith(compareBy<EdgeVoice> { voice ->
            val pIndex = priorityIds.indexOf(voice.id)
            if (pIndex >= 0) pIndex else 1000
        }.thenBy { it.id })
    }
}
