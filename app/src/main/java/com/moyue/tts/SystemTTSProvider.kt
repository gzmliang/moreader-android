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

    private val utteranceListeners = mutableMapOf<String, TTSListener>()
    private var counter = 0

    override val type: TTSProviderType get() = TTSProviderType.SYSTEM
    override val isSpeaking: Boolean get() = globalTts?.isSpeaking ?: false

    init {
        dlog("=== SystemTTSProvider 创建 ===")
        dlog("context: ${context.javaClass.simpleName}")
        if (context is android.app.Activity) activityContext = context
    }

    private fun ensureInitialized() {
        if (globalIsReady) return
        if (globalTts != null) return

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
                        dlog("✅ TTS 就绪: ${globalTts?.defaultEngine}")
                        setupTts()
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

    private fun setupTts() {
        try {
            globalTts?.setLanguage(Locale.CHINESE)
            dlog("中文设置完成")

            globalTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
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
        if (text.isBlank()) { listener.onDone(); return }
        ensureInitialized()

        if (!globalIsReady) {
            dlog("speak: 未就绪，延迟")
            Handler(Looper.getMainLooper()).postDelayed({
                if (globalIsReady) speak(text, rate, listener)
                else listener.onError("TTS未就绪")
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
            // Post via handler to break synchronous recursion loop
            Handler(Looper.getMainLooper()).post { listener.onError("speak() failed") }
        }
    }

    override fun stop() {
        dlog("stop()")
        globalTts?.stop()
        utteranceListeners.clear()
    }

    override fun destroy() {
        dlog("destroy()")
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
