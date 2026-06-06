package com.moyue.app.tts

// Callback for TTS progress
interface TTSListener {
    fun onStart()
    fun onDone()
    fun onError(message: String)
    /** System TTS: called when engine supports UtteranceProgressListener.onRangeStart.
     *  start=char offset, end=end offset. Default no-op for non-System providers. */
    fun onRange(start: Int, end: Int) {}
}

// TTS engine interface
interface TTSProvider {
    fun speak(text: String, rate: Float, listener: TTSListener)
    fun stop()
    fun destroy()
    val isSpeaking: Boolean
    val type: com.moyue.app.data.models.TTSProviderType
}
