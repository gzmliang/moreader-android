package com.moyue.app.tts

import android.content.Context
import android.os.Bundle
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
 * Key fix: Tries known engine packages when the default engine fails.
 * Common engine packages: com.google.android.tts, com.samsung.SMT, etc.
 */
class SystemTTSProvider(private val context: Context) : TTSProvider {

    companion object {
        private const val TAG = "SystemTTS"
    // Common TTS engine packages to try
    private val ENGINE_CANDIDATES = listOf(
        null,  // null = system default
        "com.google.android.tts",          // Google TTS
        "com.samsung.SMT",                 // Samsung TTS
        "com.iflytek.speechcloud",         // iFlytek (common in Chinese ROMs)
        "com.baidu.duersdk.tts",           // Baidu
        "com.xiaomi.micolauncher.tts",     // Xiaomi
        "com.oppo.tts",                    // OPPO
    )
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var initError: String? = null
    private var currentListener: TTSListener? = null
    private var activeEnginePackage: String? = null

    // Public accessors for debug logging
    val isReadyPublic: Boolean get() = isReady
    val initErrorPublic: String? get() = initError
    val activeEngine: String? get() = activeEnginePackage

    /** Check installed TTS engines via PackageManager */
    fun getAllInstalledEngines(): List<String> {
        return try {
            val pm = context.applicationContext.packageManager
            val intent = android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            val services = pm.queryIntentServices(intent, android.content.pm.PackageManager.GET_META_DATA)
            services.map { "${it.serviceInfo.packageName} (${it.serviceInfo.loadLabel(pm)})" }
        } catch (e: Exception) {
            Log.e(TAG, "getEngines failed", e)
            listOf("Error: ${e.message}")
        }
    }

    override val type: TTSProviderType get() = TTSProviderType.SYSTEM

    init {
        Log.d(TAG, "Creating SystemTTSProvider on thread: ${Thread.currentThread().name}")

        val looper = Looper.myLooper()
        if (looper == Looper.getMainLooper()) {
            tryInitTts(context.applicationContext)
        } else {
            val lock = Object()
            var done = false
            Handler(Looper.getMainLooper()).post {
                tryInitTts(context.applicationContext)
                synchronized(lock) { done = true; lock.notifyAll() }
            }
            synchronized(lock) {
                try { if (!done) lock.wait(3000) } catch (_: InterruptedException) {}
            }
        }

        // Even after the constructor, the callback might be delayed.
        // Try a second init attempt if first failed and there are candidate engines.
        if (!isReady && initError != null) {
            val installed = getAllInstalledEngines()
            Log.d(TAG, "Installed engines: $installed")
            if (installed.isNotEmpty()) {
                // Try each candidate engine
                for (enginePkg in ENGINE_CANDIDATES) {
                    if (enginePkg == null) continue // already tried default
                    if (isReady) break
                    Log.d(TAG, "Trying engine: $enginePkg")
                    retryWithEngine(context.applicationContext, enginePkg)
                    waitForReady(2000)
                }
            }
        }
    }

    private fun tryInitTts(context: Context) {
        tts = TextToSpeech(context) { status ->
            handleInitResult(status)
        }
    }

    private fun retryWithEngine(context: Context, enginePackage: String) {
        // Destroy previous instance
        tts?.stop(); tts?.shutdown(); tts = null
        isReady = false
        initError = null

        try {
            tts = TextToSpeech(context, { status ->
                handleInitResult(status)
            }, enginePackage)
            activeEnginePackage = enginePackage
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init with engine $enginePackage: ${e.message}")
            initError = "Engine $enginePackage init failed: ${e.message}"
        }
    }

    private fun handleInitResult(status: Int) {
        Log.d(TAG, "TextToSpeech init callback: status=$status (0=SUCCESS, -1=ERROR)")
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            initError = null

            // Try Chinese locale
            val langResult = tts?.setLanguage(Locale.CHINA)
            Log.d(TAG, "setLanguage(zh-CN) result: $langResult " +
                    "(${TextToSpeech.LANG_COUNTRY_AVAILABLE}=AVAIL, ${TextToSpeech.LANG_NOT_SUPPORTED}=NOT_SUP)")

            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "zh-CN not supported, trying default locale")
                tts?.language = Locale.getDefault()
            }

            // Log voices
            val voices = tts?.voices
            Log.d(TAG, "Available TTS voices: ${voices?.size ?: 0}")
            voices?.take(5)?.forEach { v ->
                Log.d(TAG, "  Voice: ${v.name} (${v.locale})")
            }

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { Log.d(TAG, "onStart: $utteranceId"); currentListener?.onStart() }
                override fun onDone(utteranceId: String?) { Log.d(TAG, "onDone: $utteranceId"); currentListener?.onDone() }
                override fun onError(utteranceId: String?) { Log.e(TAG, "onError: $utteranceId"); currentListener?.onError("TTS error") }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?, errorCode: Int) { Log.e(TAG, "onError: $utteranceId code=$errorCode"); currentListener?.onError("TTS error: $errorCode") }
            })
        } else {
            initError = "TTS engine init failed (status=$status)"
            Log.e(TAG, initError!!)
        }
    }

    fun waitForReady(timeoutMs: Long = 5000): Boolean {
        if (isReady) return true
        if (initError != null && tts == null) return false

        val start = System.currentTimeMillis()
        while (!isReady && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(100)
        }
        return isReady
    }

    override fun speak(text: String, rate: Float, listener: TTSListener) {
        if (tts == null) {
            listener.onError("TTS engine not initialized")
            return
        }

        if (!isReady) {
            val ready = waitForReady(3000)
            if (!ready) {
                val msg = initError ?: "TTS engine init timeout"
                listener.onError("$msg. No TTS engine available, use Edge TTS or AI Voice")
                return
            }
        }

        currentListener = listener

        // Set language
        val hasChinese = text.any { it.code in 0x4E00..0x9FFF }
        if (hasChinese) {
            val r = tts?.setLanguage(Locale.CHINA)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.getDefault())
            }
        } else {
            val r = tts?.setLanguage(Locale.US)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.getDefault())
            }
        }

        tts?.setSpeechRate(rate)

        val utteranceId = "mr_para_${System.currentTimeMillis()}"
        val bundle = Bundle()
        bundle.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)

        val speakResult = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, bundle, utteranceId)
        Log.d(TAG, "speak() returned: $speakResult (0=SUCCESS, -1=ERROR)")
        if (speakResult == TextToSpeech.ERROR) {
            listener.onError("TTS speak returned error")
        }
    }

    override fun stop() { Log.d(TAG, "stop()"); tts?.stop() }

    override fun destroy() {
        Log.d(TAG, "destroy()")
        tts?.stop(); tts?.shutdown(); tts = null; isReady = false
    }

    override val isSpeaking: Boolean get() = tts?.isSpeaking ?: false
}
