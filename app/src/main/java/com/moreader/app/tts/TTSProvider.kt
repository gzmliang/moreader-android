package com.moreader.app.tts

// Callback for TTS progress
interface TTSListener {
    fun onStart()
    fun onDone()
    fun onError(message: String)
}

// TTS engine interface
interface TTSProvider {
    fun speak(text: String, rate: Float, listener: TTSListener)
    fun stop()
    fun destroy()
    val isSpeaking: Boolean
    val type: com.moreader.app.data.models.TTSProviderType
}
