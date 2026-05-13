package com.moyue.app.ui.components

import java.util.Locale

/**
 * Edge TTS voice definition with locale and gender metadata.
 * nameRes is a string resource ID for localized display.
 */
data class EdgeVoice(
    val id: String,
    val nameRes: Int,  // @StringRes
    val gender: String,  // "male", "female", or "child"
    val locale: Locale,
)

/**
 * Full list of Microsoft Edge TTS voices, mirroring the browser extension.
 * Display names are string resources for i18n support.
 * Grouping: by language, sorted alphabetically.
 */
internal val EDGE_VOICES: List<EdgeVoice> = listOf(
    // Chinese - zh-CN
    EdgeVoice("zh-CN-XiaoxiaoNeural", com.moyue.app.R.string.voice_xiaoxiao, "female", Locale.CHINA),
    EdgeVoice("zh-CN-YunxiNeural", com.moyue.app.R.string.voice_yunxi, "male", Locale.CHINA),
    EdgeVoice("zh-CN-YunjianNeural", com.moyue.app.R.string.voice_yunjian, "male", Locale.CHINA),
    EdgeVoice("zh-CN-XiaoyiNeural", com.moyue.app.R.string.voice_xiaoyi, "female", Locale.CHINA),

    // Chinese - zh-TW
    EdgeVoice("zh-TW-HsiaoChenNeural", com.moyue.app.R.string.voice_hsiaochen, "female", Locale.TAIWAN),
    EdgeVoice("zh-TW-HsiaoYuNeural", com.moyue.app.R.string.voice_hsiaochen, "female", Locale.TAIWAN),
    EdgeVoice("zh-TW-YunJheNeural", com.moyue.app.R.string.voice_yunjhe, "male", Locale.TAIWAN),

    // Chinese - zh-HK
    EdgeVoice("zh-HK-HiuMaanNeural", com.moyue.app.R.string.voice_hsiaochen, "female", Locale.forLanguageTag("zh-HK")),
    EdgeVoice("zh-HK-HiuGaaiNeural", com.moyue.app.R.string.voice_hsiaochen, "female", Locale.forLanguageTag("zh-HK")),
    EdgeVoice("zh-HK-WanLungNeural", com.moyue.app.R.string.voice_yunjhe, "male", Locale.forLanguageTag("zh-HK")),

    // English - US
    EdgeVoice("en-US-JennyNeural", 0, "female", Locale.US),
    EdgeVoice("en-US-GuyNeural", 0, "male", Locale.US),
    EdgeVoice("en-US-AriaNeural", 0, "female", Locale.US),
    EdgeVoice("en-US-DavisNeural", 0, "male", Locale.US),
    EdgeVoice("en-US-AndrewNeural", 0, "male", Locale.US),
    EdgeVoice("en-US-AshleyNeural", 0, "female", Locale.US),
    EdgeVoice("en-US-ChristopherNeural", 0, "male", Locale.US),
    EdgeVoice("en-US-CoraNeural", 0, "female", Locale.US),
    EdgeVoice("en-US-ElizabethNeural", 0, "female", Locale.US),
    EdgeVoice("en-US-EricNeural", 0, "male", Locale.US),
    EdgeVoice("en-US-JacobNeural", 0, "male", Locale.US),
    EdgeVoice("en-US-MichelleNeural", 0, "female", Locale.US),
    EdgeVoice("en-US-MonicaNeural", 0, "female", Locale.US),
    EdgeVoice("en-US-RogerNeural", 0, "male", Locale.US),
    EdgeVoice("en-US-SteffanNeural", 0, "male", Locale.US),

    // English - GB
    EdgeVoice("en-GB-SoniaNeural", 0, "female", Locale.UK),
    EdgeVoice("en-GB-RyanNeural", 0, "male", Locale.UK),
    EdgeVoice("en-GB-LibbyNeural", 0, "female", Locale.UK),
    EdgeVoice("en-GB-AlfieNeural", 0, "male", Locale.UK),
    EdgeVoice("en-GB-BellaNeural", 0, "female", Locale.UK),
    EdgeVoice("en-GB-ElliotNeural", 0, "male", Locale.UK),
    EdgeVoice("en-GB-EthanNeural", 0, "male", Locale.UK),
    EdgeVoice("en-GB-HollieNeural", 0, "female", Locale.UK),
    EdgeVoice("en-GB-MaisieNeural", 0, "child", Locale.UK),
    EdgeVoice("en-GB-NoahNeural", 0, "male", Locale.UK),
    EdgeVoice("en-GB-OliverNeural", 0, "male", Locale.UK),
    EdgeVoice("en-GB-OliviaNeural", 0, "female", Locale.UK),
    EdgeVoice("en-GB-ThomasNeural", 0, "male", Locale.UK),

    // Japanese
    EdgeVoice("ja-JP-NanamiNeural", com.moyue.app.R.string.voice_nanami, "female", Locale.JAPAN),
    EdgeVoice("ja-JP-KeitaNeural", com.moyue.app.R.string.voice_keita, "male", Locale.JAPAN),
    EdgeVoice("ja-JP-AoiNeural", 0, "female", Locale.JAPAN),
    EdgeVoice("ja-JP-DaichiNeural", 0, "male", Locale.JAPAN),
    EdgeVoice("ja-JP-MayuNeural", 0, "female", Locale.JAPAN),
    EdgeVoice("ja-JP-NaokiNeural", 0, "male", Locale.JAPAN),
    EdgeVoice("ja-JP-ShioriNeural", 0, "female", Locale.JAPAN),

    // Korean
    EdgeVoice("ko-KR-SunHiNeural", com.moyue.app.R.string.voice_sunhi, "female", Locale.KOREA),
    EdgeVoice("ko-KR-InJoonNeural", com.moyue.app.R.string.voice_injun, "male", Locale.KOREA),
    EdgeVoice("ko-KR-HyunsuNeural", 0, "male", Locale.KOREA),

    // German
    EdgeVoice("de-DE-KatjaNeural", 0, "female", Locale.GERMANY),
    EdgeVoice("de-DE-ConradNeural", 0, "male", Locale.GERMANY),
    EdgeVoice("de-DE-AmalaNeural", 0, "female", Locale.GERMANY),
    EdgeVoice("de-DE-BerndNeural", 0, "male", Locale.GERMANY),
    EdgeVoice("de-DE-ChristophNeural", 0, "male", Locale.GERMANY),
    EdgeVoice("de-DE-ElkeNeural", 0, "female", Locale.GERMANY),
    EdgeVoice("de-DE-GiselaNeural", 0, "child", Locale.GERMANY),
    EdgeVoice("de-DE-KasperNeural", 0, "male", Locale.GERMANY),
    EdgeVoice("de-DE-KillianNeural", 0, "male", Locale.GERMANY),
    EdgeVoice("de-DE-KlarissaNeural", 0, "female", Locale.GERMANY),
    EdgeVoice("de-DE-KlausNeural", 0, "male", Locale.GERMANY),
    EdgeVoice("de-DE-LouisaNeural", 0, "female", Locale.GERMANY),
    EdgeVoice("de-DE-MajaNeural", 0, "female", Locale.GERMANY),
    EdgeVoice("de-DE-RalfNeural", 0, "male", Locale.GERMANY),
    EdgeVoice("de-DE-SeraphinaNeural", 0, "child", Locale.GERMANY),
    EdgeVoice("de-DE-TanjaNeural", 0, "female", Locale.GERMANY),

    // French
    EdgeVoice("fr-FR-DeniseNeural", 0, "female", Locale.FRANCE),
    EdgeVoice("fr-FR-HenriNeural", 0, "male", Locale.FRANCE),
    EdgeVoice("fr-FR-EloiseNeural", 0, "child", Locale.FRANCE),
    EdgeVoice("fr-FR-AlainNeural", 0, "male", Locale.FRANCE),
    EdgeVoice("fr-FR-BrigitteNeural", 0, "female", Locale.FRANCE),
    EdgeVoice("fr-FR-CelesteNeural", 0, "female", Locale.FRANCE),
    EdgeVoice("fr-FR-ClaudeNeural", 0, "male", Locale.FRANCE),
    EdgeVoice("fr-FR-CoralieNeural", 0, "female", Locale.FRANCE),
    EdgeVoice("fr-FR-JacquelineNeural", 0, "female", Locale.FRANCE),
    EdgeVoice("fr-FR-JeromeNeural", 0, "male", Locale.FRANCE),
    EdgeVoice("fr-FR-JosephineNeural", 0, "female", Locale.FRANCE),
    EdgeVoice("fr-FR-MauriceNeural", 0, "male", Locale.FRANCE),
    EdgeVoice("fr-FR-YvesNeural", 0, "male", Locale.FRANCE),
    EdgeVoice("fr-FR-YvetteNeural", 0, "female", Locale.FRANCE),

    // Spanish
    EdgeVoice("es-ES-ElviraNeural", 0, "female", Locale.forLanguageTag("es-ES")),
    EdgeVoice("es-ES-AlvaroNeural", 0, "male", Locale.forLanguageTag("es-ES")),

    // Russian
    EdgeVoice("ru-RU-SvetlanaNeural", 0, "female", Locale.forLanguageTag("ru-RU")),
    EdgeVoice("ru-RU-DmitryNeural", 0, "male", Locale.forLanguageTag("ru-RU")),

    // Italian
    EdgeVoice("it-IT-ElsaNeural", 0, "female", Locale.ITALY),
    EdgeVoice("it-IT-DiegoNeural", 0, "male", Locale.ITALY),

    // Portuguese - Brazil
    EdgeVoice("pt-BR-FranciscaNeural", 0, "female", Locale.forLanguageTag("pt-BR")),
    EdgeVoice("pt-BR-AntonioNeural", 0, "male", Locale.forLanguageTag("pt-BR")),

    // Arabic
    EdgeVoice("ar-SA-ZariyahNeural", 0, "female", Locale.forLanguageTag("ar-SA")),
    EdgeVoice("ar-SA-HamedNeural", 0, "male", Locale.forLanguageTag("ar-SA")),

    // Hindi
    EdgeVoice("hi-IN-SwaraNeural", 0, "female", Locale.forLanguageTag("hi-IN")),
    EdgeVoice("hi-IN-MadhurNeural", 0, "male", Locale.forLanguageTag("hi-IN")),

    // Thai
    EdgeVoice("th-TH-PremwadeeNeural", 0, "female", Locale.forLanguageTag("th-TH")),
    EdgeVoice("th-TH-NiwatNeural", 0, "male", Locale.forLanguageTag("th-TH")),

    // Vietnamese
    EdgeVoice("vi-VN-HoaiMyNeural", 0, "female", Locale.forLanguageTag("vi-VN")),
    EdgeVoice("vi-VN-NamMinhNeural", 0, "male", Locale.forLanguageTag("vi-VN")),
)

/**
 * Grouped Edge voices by locale for UI display.
 * Returns a map of locale display name → list of voices.
 */
internal fun groupedEdgeVoices(): Map<String, List<EdgeVoice>> {
    return EDGE_VOICES.groupBy { voice ->
        voice.locale.displayName  // Automatically localized by system locale
    }.toSortedMap()
}

/**
 * Get display name for an Edge voice in current locale.
 * Format: "Name · gender symbol"
 */
internal fun EdgeVoice.displayName(context: android.content.Context): String {
    val nameStr = if (nameRes != 0) context.getString(nameRes) else id.substringAfterLast("-").removeSuffix("Neural")
    val genderLabel = when (gender) {
        "female" -> "♀"
        "male" -> "♂"
        else -> ""
    }
    return "$nameStr · $genderLabel"
}
