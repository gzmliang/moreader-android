package com.moyue.app.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.moyue.app.data.models.TTSProviderType
import java.text.SimpleDateFormat
import java.util.Locale

class SystemTTSProvider(context: Context) : TTSProvider {

    companion object {
        private const val TAG = "SystemTTS"
        private val dateFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

        @Volatile
        private var globalTts: TextToSpeech? = null
        @Volatile
        private var globalIsReady = false
        private val initLock = Object()

        // Shared across ALL instances — critical because setupTts() binds
        // UtteranceProgressListener to this map only ONCE (first init).
        // Instance-level maps would become stale after destroy/recreate.
        private val utteranceListeners = java.util.concurrent.ConcurrentHashMap<String, TTSListener>()
        private var counter = 0

        private val debugLog = StringBuilder()
        fun getDebugLog(): String = debugLog.toString()
        fun clearDebugLog() { debugLog.clear() }
        private fun dlog(msg: String) {
            val ts = dateFmt.format(java.util.Date())
            debugLog.append("[$ts] $msg\n")
            Log.i(TAG, msg)
        }
    }

    private val appContext = context.applicationContext
    private var activityContext: Context? = null

    override val type: TTSProviderType get() = TTSProviderType.SYSTEM
    override val isSpeaking: Boolean get() = globalTts?.isSpeaking ?: false

    init {
        dlog("=== SystemTTSProvider 创建 ===")
        dlog("context: ${context.javaClass.simpleName}")
        if (context is android.app.Activity) activityContext = context
    }

    private fun ensureInitialized() {
        // If engine already exists, just re-bind listener to this instance
        // Critical: each SystemTTSProvider instance has its own utteranceListeners map.
        // Without re-binding, the old listener fires but looks in the old (empty) map,
        // and the play chain silently breaks.
        if (globalTts != null) {
            setupTts()
            globalIsReady = true
            return
        }

        synchronized(initLock) {
            if (globalIsReady || globalTts != null) return

            dlog("ensureInitialized()")
            val defaultEngine = try {
                android.provider.Settings.Secure.getString(appContext.contentResolver, "tts_default_synth")
            } catch (e: Exception) { null }

            val ctx = activityContext ?: appContext
            val targetEngine = if (!defaultEngine.isNullOrEmpty()) defaultEngine else "com.google.android.tts"
            dlog("引擎: $targetEngine")

            try {
                globalTts = TextToSpeech(ctx, { status ->
                    dlog("onInit: status=$status")
                    if (status == TextToSpeech.SUCCESS) {
                        globalIsReady = true
                        // Use the companion field directly — this callback
                        // only fires for the engine we just created.
                        val tts = globalTts
                        dlog("✅ TTS 就绪: ${tts?.defaultEngine}")
                        if (tts != null) setupTts(tts)
                    } else {
                        dlog("❌ 失败: $status")
                    }
                }, targetEngine)
                dlog("构造完成")
            } catch (e: Exception) {
                dlog("❌ 异常: ${e.message}")
            }
        }
    }

    private fun setupTts(tts: TextToSpeech) {
        try {
            tts.setLanguage(Locale.CHINESE)
            dlog("中文设置完成")

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    val listener = id?.let { utteranceListeners[it] }
                    dlog("onStart: $id")
                    listener?.onStart()
                }
                override fun onDone(id: String?) {
                    val listener = id?.let { utteranceListeners.remove(it) }
                    dlog("onDone: $id")
                    listener?.onDone()
                }
                override fun onError(id: String?) {
                    val listener = id?.let { utteranceListeners.remove(it) }
                    dlog("onError: $id")
                    listener?.onError("TTS错误")
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?, code: Int) {
                    val listener = id?.let { utteranceListeners.remove(it) }
                    dlog("onError: $id code=$code")
                    listener?.onError("TTS错误($code)")
                }
            })
            dlog("Listener 设置完成")
        } catch (e: Exception) {
            dlog("setup 异常: ${e.message}")
        }
    }

    private fun setLanguageForText(text: String) {
        val tts = globalTts ?: return
        val hasChinese = text.any { it.code in 0x4E00..0x9FFF }
        val target = if (hasChinese) Locale.CHINA else Locale.US
        val result = tts.setLanguage(target)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.language = Locale.getDefault()
        }
    }

    override fun speak(text: String, rate: Float, listener: TTSListener) {
        speakWithRetry(text, rate, listener, 0)
    }

    private fun speakWithRetry(text: String, rate: Float, listener: TTSListener, retryCount: Int) {
        if (text.isBlank()) { Handler(Looper.getMainLooper()).post { listener.onDone() }; return }
        ensureInitialized()

        if (!globalIsReady) {
            if (retryCount >= 40) { // 40 × 500ms = 20s timeout
                dlog("speak: 初始化超时")
                Handler(Looper.getMainLooper()).post { listener.onError("TTS初始化超时") }
                return
            }
            dlog("speak: 未就绪，重试#$retryCount")
            Handler(Looper.getMainLooper()).postDelayed({
                speakWithRetry(text, rate, listener, retryCount + 1)
            }, 500)
            return
        }

        setLanguageForText(text)
        globalTts?.setSpeechRate(rate)

        val id = "u_${++counter}"
        utteranceListeners[id] = listener

        dlog("speak: #$counter \"${text.take(60)}\"")
        val result = globalTts?.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        dlog("speak()=$result id=$id")

        if (result == TextToSpeech.ERROR) {
            utteranceListeners.remove(id)
            Handler(Looper.getMainLooper()).post { listener.onError("speak() failed") }
        }
    }

    override fun stop() {
        dlog("stop()")
        globalTts?.stop()
        // Flush queue — critical for clean restart after stop
        // Without flush, next speak() may succeed but subsequent ones fail
        globalTts?.speak("", TextToSpeech.QUEUE_FLUSH, null, null)
        utteranceListeners.clear()
    }

    override fun destroy() {
        // 只停止当前朗读，不 shutdown 引擎。
        // Android TTS Service Binding 释放是异步的，
        // shutdown 后立即创建新实例会导致 bind 冲突，onInit 永远不回调。
        // 引擎作为单例保持存活，下次 speak 时直接复用。
        dlog("destroy() — 仅停止，保留引擎")
        globalTts?.stop()
        utteranceListeners.clear()
    }

    fun fullDestroy() {
        dlog("fullDestroy()")
        globalTts?.stop()
        globalTts?.shutdown()
        globalTts = null
        globalIsReady = false
        utteranceListeners.clear()
    }
}
