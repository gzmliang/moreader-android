package com.moreader.app.ui.components

import java.util.Locale

/**
 * Edge TTS voice definition with locale and gender metadata.
 */
data class EdgeVoice(
    val id: String,
    val name: String,
    val gender: String,  // "male", "female", or "child"
    val locale: Locale,
)

/**
 * Full list of Microsoft Edge TTS voices, mirroring the browser extension.
 * Display format: name (locale display language)
 * Grouping: by language, sorted alphabetically.
 */
internal val EDGE_VOICES: List<EdgeVoice> = listOf(
    // Chinese - zh-CN
    EdgeVoice("zh-CN-XiaoxiaoNeural", "晓晓", "female", Locale.CHINA),
    EdgeVoice("zh-CN-YunxiNeural", "云希", "male", Locale.CHINA),
    EdgeVoice("zh-CN-YunjianNeural", "云健", "male", Locale.CHINA),
    EdgeVoice("zh-CN-XiaoyiNeural", "晓伊", "female", Locale.CHINA),

    // Chinese - zh-TW
    EdgeVoice("zh-TW-HsiaoChenNeural", "曉臻", "female", Locale.TAIWAN),
    EdgeVoice("zh-TW-HsiaoYuNeural", "曉雨", "female", Locale.TAIWAN),
    EdgeVoice("zh-TW-YunJheNeural", "雲哲", "male", Locale.TAIWAN),

    // Chinese - zh-HK
    EdgeVoice("zh-HK-HiuMaanNeural", "曉曼", "female", Locale.forLanguageTag("zh-HK")),
    EdgeVoice("zh-HK-HiuGaaiNeural", "曉佳", "female", Locale.forLanguageTag("zh-HK")),
    EdgeVoice("zh-HK-WanLungNeural", "雲龍", "male", Locale.forLanguageTag("zh-HK")),

    // English - US
    EdgeVoice("en-US-JennyNeural", "Jenny", "female", Locale.US),
    EdgeVoice("en-US-GuyNeural", "Guy", "male", Locale.US),
    EdgeVoice("en-US-AriaNeural", "Aria", "female", Locale.US),
    EdgeVoice("en-US-DavisNeural", "Davis", "male", Locale.US),
    EdgeVoice("en-US-AndrewNeural", "Andrew", "male", Locale.US),
    EdgeVoice("en-US-AshleyNeural", "Ashley", "female", Locale.US),
    EdgeVoice("en-US-ChristopherNeural", "Christopher", "male", Locale.US),
    EdgeVoice("en-US-CoraNeural", "Cora", "female", Locale.US),
    EdgeVoice("en-US-ElizabethNeural", "Elizabeth", "female", Locale.US),
    EdgeVoice("en-US-EricNeural", "Eric", "male", Locale.US),
    EdgeVoice("en-US-JacobNeural", "Jacob", "male", Locale.US),
    EdgeVoice("en-US-MichelleNeural", "Michelle", "female", Locale.US),
    EdgeVoice("en-US-MonicaNeural", "Monica", "female", Locale.US),
    EdgeVoice("en-US-RogerNeural", "Roger", "male", Locale.US),
    EdgeVoice("en-US-SteffanNeural", "Steffan", "male", Locale.US),

    // English - GB
    EdgeVoice("en-GB-SoniaNeural", "Sonia", "female", Locale.UK),
    EdgeVoice("en-GB-RyanNeural", "Ryan", "male", Locale.UK),
    EdgeVoice("en-GB-LibbyNeural", "Libby", "female", Locale.UK),
    EdgeVoice("en-GB-AlfieNeural", "Alfie", "male", Locale.UK),
    EdgeVoice("en-GB-BellaNeural", "Bella", "female", Locale.UK),
    EdgeVoice("en-GB-ElliotNeural", "Elliot", "male", Locale.UK),
    EdgeVoice("en-GB-EthanNeural", "Ethan", "male", Locale.UK),
    EdgeVoice("en-GB-HollieNeural", "Hollie", "female", Locale.UK),
    EdgeVoice("en-GB-MaisieNeural", "Maisie", "child", Locale.UK),
    EdgeVoice("en-GB-NoahNeural", "Noah", "male", Locale.UK),
    EdgeVoice("en-GB-OliverNeural", "Oliver", "male", Locale.UK),
    EdgeVoice("en-GB-OliviaNeural", "Olivia", "female", Locale.UK),
    EdgeVoice("en-GB-ThomasNeural", "Thomas", "male", Locale.UK),

    // Japanese
    EdgeVoice("ja-JP-NanamiNeural", "七海", "female", Locale.JAPAN),
    EdgeVoice("ja-JP-KeitaNeural", "圭太", "male", Locale.JAPAN),
    EdgeVoice("ja-JP-AoiNeural", "葵", "female", Locale.JAPAN),
    EdgeVoice("ja-JP-DaichiNeural", "大智", "male", Locale.JAPAN),
    EdgeVoice("ja-JP-MayuNeural", "真由", "female", Locale.JAPAN),
    EdgeVoice("ja-JP-NaokiNeural", "直樹", "male", Locale.JAPAN),
    EdgeVoice("ja-JP-ShioriNeural", "詩織", "female", Locale.JAPAN),

    // Korean
    EdgeVoice("ko-KR-SunHiNeural", "선희", "female", Locale.KOREA),
    EdgeVoice("ko-KR-InJoonNeural", "인준", "male", Locale.KOREA),
    EdgeVoice("ko-KR-HyunsuNeural", "현수", "male", Locale.KOREA),

    // German
    EdgeVoice("de-DE-KatjaNeural", "Katja", "female", Locale.GERMANY),
    EdgeVoice("de-DE-ConradNeural", "Conrad", "male", Locale.GERMANY),
    EdgeVoice("de-DE-AmalaNeural", "Amala", "female", Locale.GERMANY),
    EdgeVoice("de-DE-BerndNeural", "Bernd", "male", Locale.GERMANY),
    EdgeVoice("de-DE-ChristophNeural", "Christoph", "male", Locale.GERMANY),
    EdgeVoice("de-DE-ElkeNeural", "Elke", "female", Locale.GERMANY),
    EdgeVoice("de-DE-GiselaNeural", "Gisela", "child", Locale.GERMANY),
    EdgeVoice("de-DE-KasperNeural", "Kasper", "male", Locale.GERMANY),
    EdgeVoice("de-DE-KillianNeural", "Killian", "male", Locale.GERMANY),
    EdgeVoice("de-DE-KlarissaNeural", "Klarissa", "female", Locale.GERMANY),
    EdgeVoice("de-DE-KlausNeural", "Klaus", "male", Locale.GERMANY),
    EdgeVoice("de-DE-LouisaNeural", "Louisa", "female", Locale.GERMANY),
    EdgeVoice("de-DE-MajaNeural", "Maja", "female", Locale.GERMANY),
    EdgeVoice("de-DE-RalfNeural", "Ralf", "male", Locale.GERMANY),
    EdgeVoice("de-DE-SeraphinaNeural", "Seraphina", "child", Locale.GERMANY),
    EdgeVoice("de-DE-TanjaNeural", "Tanja", "female", Locale.GERMANY),

    // French
    EdgeVoice("fr-FR-DeniseNeural", "Denise", "female", Locale.FRANCE),
    EdgeVoice("fr-FR-HenriNeural", "Henri", "male", Locale.FRANCE),
    EdgeVoice("fr-FR-EloiseNeural", "Eloise", "child", Locale.FRANCE),
    EdgeVoice("fr-FR-AlainNeural", "Alain", "male", Locale.FRANCE),
    EdgeVoice("fr-FR-BrigitteNeural", "Brigitte", "female", Locale.FRANCE),
    EdgeVoice("fr-FR-CelesteNeural", "Celeste", "female", Locale.FRANCE),
    EdgeVoice("fr-FR-ClaudeNeural", "Claude", "male", Locale.FRANCE),
    EdgeVoice("fr-FR-CoralieNeural", "Coralie", "female", Locale.FRANCE),
    EdgeVoice("fr-FR-JacquelineNeural", "Jacqueline", "female", Locale.FRANCE),
    EdgeVoice("fr-FR-JeromeNeural", "Jerome", "male", Locale.FRANCE),
    EdgeVoice("fr-FR-JosephineNeural", "Josephine", "female", Locale.FRANCE),
    EdgeVoice("fr-FR-MauriceNeural", "Maurice", "male", Locale.FRANCE),
    EdgeVoice("fr-FR-YvesNeural", "Yves", "male", Locale.FRANCE),
    EdgeVoice("fr-FR-YvetteNeural", "Yvette", "female", Locale.FRANCE),

    // Spanish
    EdgeVoice("es-ES-ElviraNeural", "Elvira", "female", Locale.forLanguageTag("es-ES")),
    EdgeVoice("es-ES-AlvaroNeural", "Alvaro", "male", Locale.forLanguageTag("es-ES")),

    // Russian
    EdgeVoice("ru-RU-SvetlanaNeural", "Светлана", "female", Locale.forLanguageTag("ru-RU")),
    EdgeVoice("ru-RU-DmitryNeural", "Дмитрий", "male", Locale.forLanguageTag("ru-RU")),

    // Italian
    EdgeVoice("it-IT-ElsaNeural", "Elsa", "female", Locale.ITALY),
    EdgeVoice("it-IT-DiegoNeural", "Diego", "male", Locale.ITALY),

    // Portuguese - Brazil
    EdgeVoice("pt-BR-FranciscaNeural", "Francisca", "female", Locale.forLanguageTag("pt-BR")),
    EdgeVoice("pt-BR-AntonioNeural", "Antonio", "male", Locale.forLanguageTag("pt-BR")),

    // Arabic
    EdgeVoice("ar-SA-ZariyahNeural", "زارية", "female", Locale.forLanguageTag("ar-SA")),
    EdgeVoice("ar-SA-HamedNeural", "حامد", "male", Locale.forLanguageTag("ar-SA")),

    // Hindi
    EdgeVoice("hi-IN-SwaraNeural", "स्वरा", "female", Locale.forLanguageTag("hi-IN")),
    EdgeVoice("hi-IN-MadhurNeural", "मधुर", "male", Locale.forLanguageTag("hi-IN")),

    // Thai
    EdgeVoice("th-TH-PremwadeeNeural", "เปรมวดี", "female", Locale.forLanguageTag("th-TH")),
    EdgeVoice("th-TH-NiwatNeural", "นิวัติ", "male", Locale.forLanguageTag("th-TH")),

    // Vietnamese
    EdgeVoice("vi-VN-HoaiMyNeural", "Hoài My", "female", Locale.forLanguageTag("vi-VN")),
    EdgeVoice("vi-VN-NamMinhNeural", "Nam Minh", "male", Locale.forLanguageTag("vi-VN")),
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
 * Format: "Name (locale - gender)" or "Name" for the active locale.
 */
internal fun EdgeVoice.displayName(): String {
    val genderLabel = when (gender) {
        "female" -> "♀"
        "male" -> "♂"
        else -> ""
    }
    return "$name · $genderLabel"
}
