package com.moyue.app.tts

import org.json.JSONArray

/** Word-level boundary from Edge TTS or ONNX TTS */
data class WordBoundary(
    val offsetMs: Double,   // 词起始毫秒 (从音频开始)
    val text: String,       // 词文本
    val charStart: Int,     // 原文中的字符偏移
    val charEnd: Int,       // 原文中的字符结束 (exclusive)
)

/** Result of preloading audio + optional word boundaries */
data class PreloadResult(
    val audio: ByteArray,
    val boundaries: List<WordBoundary> = emptyList(),
)

// Callback for TTS progress
interface TTSListener {
    fun onStart()
    fun onDone()
    fun onError(message: String)
    fun onRange(start: Int, end: Int) {}                    // System TTS onRangeStart
    fun onWordBoundaries(boundaries: List<WordBoundary>) {} // Edge TTS 词边界
}

// TTS engine interface
interface TTSProvider {
    fun speak(text: String, rate: Float, listener: TTSListener)
    fun playRaw(audioData: ByteArray, listener: TTSListener) {}  // 播放预加载缓存
    fun stop()
    fun destroy()
    val isSpeaking: Boolean
    val type: com.moyue.app.data.models.TTSProviderType
}

/** Parse X-Word-Boundaries header JSON into WordBoundary list */
fun parseWordBoundaries(json: String): List<WordBoundary> {
    if (json.isBlank()) return emptyList()
    val arr = JSONArray(json)
    return (0 until arr.length()).map { i ->
        val obj = arr.getJSONObject(i)
        WordBoundary(
            offsetMs = obj.getDouble("o"),
            text = obj.getString("t"),
            charStart = obj.getInt("s"),
            charEnd = obj.getInt("e"),
        )
    }
}
