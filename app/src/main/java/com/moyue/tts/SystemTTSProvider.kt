package com.moyue.app.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.moyue.app.data.models.TTSProviderType
import java.util.Locale

/**
 * System TTS provider — uses Android's built-in TextToSpeech engine.
 * 
 * Robust implementation that:
 * - Initializes on main thread (required by Android)
 * - Queues speak requests until engine is ready
 * - Handles Google TTS engine specifically
 * - Has proper error handling and logging
 */
class SystemTTSProvider(context: Context) : TTSProvider {

    companion object {
        private const val TAG = "SystemTTS"
    }

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var isReady = false
    private var currentListener: TTSListener? = null
    
    // Queue for pending speak requests
    private data class PendingSpeak(
        val text: String,
        val rate: Float,
        val listener: TTSListener
    )
    private val pendingQueue = mutableListOf<PendingSpeak>()

    override val type: TTSProviderType get() = TTSProviderType.SYSTEM
    override val isSpeaking: Boolean get() = tts?.isSpeaking ?: false

    init {
        Log.d(TAG, "Creating SystemTTSProvider on thread: ${Thread.currentThread().name}")
        
        // Must initialize on main thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            initTts()
        } else {
            // Wait for main thread initialization to complete
            val latch = java.util.concurrent.CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                initTts()
                latch.countDown()
            }
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun initTts() {
        Log.d(TAG, "initTts() called on thread: ${Thread.currentThread().name}")
        
        try {
            // Create TTS with default engine (system default, usually Google TTS)
            tts = TextToSpeech(appContext, object : TextToSpeech.OnInitListener {
                override fun onInit(status: Int) {
                    Log.d(TAG, "TextToSpeech onInit callback: status=$status (SUCCESS=0, ERROR=-1)")
                    
                    if (status == TextToSpeech.SUCCESS) {
                        isReady = true
                        Log.d(TAG, "TTS initialized successfully")
                        
                        // Set up utterance progress listener
                        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                Log.d(TAG, "onStart: $utteranceId")
                                currentListener?.onStart()
                            }

                            override fun onDone(utteranceId: String?) {
                                Log.d(TAG, "onDone: $utteranceId")
                                currentListener?.onDone()
                                processNextInQueue()
                            }

                            override fun onError(utteranceId: String?) {
                                Log.e(TAG, "onError: $utteranceId")
                                currentListener?.onError("TTS error: $utteranceId")
                                processNextInQueue()
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?, errorCode: Int) {
                                Log.e(TAG, "onError: $utteranceId code=$errorCode")
                                currentListener?.onError("TTS error code: $errorCode")
                                processNextInQueue()
                            }
                        })

                        // Try to set Chinese language, fallback to default
                        setLanguageForText("test")
                        
                        // Log available voices
                        val voices = tts?.voices
                        Log.d(TAG, "Available TTS voices: ${voices?.size ?: 0}")
                        voices?.take(3)?.forEach { v ->
                            Log.d(TAG, "  Voice: ${v.name} (${v.locale}, ${if (v.isNetworkConnectionRequired) "network" else "offline"})")
                        }
                        
                        // Process any pending speak requests
                        processNextInQueue()
                    } else {
                        Log.e(TAG, "TTS initialization failed: status=$status")
                        // Try to get error details
                        val errorCode = when (status) {
                            TextToSpeech.ERROR -> "ERROR"
                            else -> "Unknown ($status)"
                        }
                        Log.e(TAG, "Error code: $errorCode")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating TextToSpeech: ${e.message}", e)
        }
    }

    private fun setLanguageForText(text: String) {
        val tts = tts ?: return
        
        val hasChinese = text.any { it.code in 0x4E00..0x9FFF }
        val hasJapanese = text.any { it.code in 0x3040..0x30FF }
        val hasKorean = text.any { it.code in 0xAC00..0xD7AF }
        
        val targetLocale = when {
            hasChinese -> Locale.CHINA
            hasJapanese -> Locale.JAPAN
            hasKorean -> Locale.KOREA
            else -> Locale.US
        }
        
        val result = tts.setLanguage(targetLocale)
        val langName = when (result) {
            TextToSpeech.LANG_COUNTRY_AVAILABLE -> "${targetLocale.language}_${targetLocale.country} (available)"
            TextToSpeech.LANG_AVAILABLE -> "${targetLocale.language} (available)"
            TextToSpeech.LANG_MISSING_DATA -> "${targetLocale.language} (missing data)"
            TextToSpeech.LANG_NOT_SUPPORTED -> "${targetLocale.language} (not supported)"
            else -> "unknown result: $result"
        }
        Log.d(TAG, "setLanguage result: $langName")
        
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Fallback to default locale
            Log.w(TAG, "Target language not supported, trying default locale")
            tts.language = Locale.getDefault()
        }
    }

    override fun speak(text: String, rate: Float, listener: TTSListener) {
        if (text.isBlank()) {
            listener.onDone()
            return
        }
        
        Log.d(TAG, "speak() called: text='${text.take(50)}...', rate=$rate, isReady=$isReady")
        
        if (tts == null) {
            Log.e(TAG, "speak() called but TTS engine is null")
            listener.onError("TTS engine not initialized")
            return
        }
        
        // If not ready yet, queue the request
        if (!isReady) {
            Log.d(TAG, "TTS not ready, queuing request")
            pendingQueue.add(PendingSpeak(text, rate, listener))
            return
        }
        
        // Set language based on text content
        setLanguageForText(text)
        
        // Set speech rate
        tts?.setSpeechRate(rate)
        
        // Store listener for callbacks
        currentListener = listener
        
        // Speak the text
        val utteranceId = "sys_tts_${System.currentTimeMillis()}"
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        
        Log.d(TAG, "speak() result: $result (SUCCESS=0, ERROR=-1)")
        
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "TTS speak returned ERROR")
            listener.onError("TTS speak returned error")
            currentListener = null
        }
    }

    private fun processNextInQueue() {
        if (pendingQueue.isEmpty()) {
            currentListener = null
            return
        }
        
        val next = pendingQueue.removeAt(0)
        Log.d(TAG, "Processing queued speak request: ${next.text.take(30)}...")
        speak(next.text, next.rate, next.listener)
    }

    override fun stop() {
        Log.d(TAG, "stop()")
        tts?.stop()
        pendingQueue.clear()
        currentListener = null
    }

    override fun destroy() {
        Log.d(TAG, "destroy()")
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        currentListener = null
        pendingQueue.clear()
    }
}
