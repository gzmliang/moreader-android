package com.moyue.app.tts

import org.json.JSONArray

/**
 * A single word boundary from Edge TTS or other timing-aware engine.
 *
 * @param offsetMs  word start time in milliseconds from audio beginning
 * @param text      the word text
 * @param charStart character offset in the original speak() text
 * @param charEnd   character end offset (exclusive) in the original text
 */
data class WordBoundary(
    val offsetMs: Double,
    val text: String,
    val charStart: Int,
    val charEnd: Int,
)

/** Parse compact JSON word boundaries from Edge TTS server header.
 *  Format: [{"o":100.0,"t":"Hello","s":0,"e":5},...] */
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

// Callback for TTS progress
interface TTSListener {
    fun onStart()
    fun onDone()
    fun onError(message: String)
    /** System TTS: called when engine supports UtteranceProgressListener.onRangeStart.
     *  start=char offset, end=end offset. Default no-op for non-System providers. */
    fun onRange(start: Int, end: Int) {}
    /** Edge TTS: called with precise word boundary data from the server.
     *  Triggered before onStart, so the consumer can set up sentence tracking. */
    fun onWordBoundaries(boundaries: List<WordBoundary>) {}
}

// TTS engine interface
interface TTSProvider {
    fun speak(text: String, rate: Float, listener: TTSListener)
    fun stop()
    fun destroy()
    val isSpeaking: Boolean
    val type: com.moyue.app.data.models.TTSProviderType
}
