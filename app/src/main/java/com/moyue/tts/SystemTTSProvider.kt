package com.moyue.app.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
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

        // Track which engine was used to create globalTts, for engine change detection
        @Volatile
        private var currentEngine: String? = null
        @Volatile
        private var currentVoiceName: String? = null

        // Shared across ALL instances — critical because setupTts() binds
        // UtteranceProgressListener to this map only ONCE (first init).
        // Instance-level maps would become stale after destroy/recreate.
        private val utteranceListeners = java.util.concurrent.ConcurrentHashMap<String, TTSListener>()
        private var counter = 0

        private val debugLog = StringBuilder()
        fun getDebugLog(): String = debugLog.toString()
        fun clearDebugLog() { debugLog.clear() }
        // Public accessor for WebView JS console capture
        private fun dlog(msg: String) {
            val ts = dateFmt.format(java.util.Date())
            debugLog.append("[$ts] $msg\n")
            Log.i(TAG, msg)
        }

        /** Get voice display info from the current engine (for UI) */
        data class VoiceInfo(val name: String, val displayName: String)
        fun getCurrentVoices(): List<VoiceInfo> {
            val tts = globalTts ?: return emptyList()
            return try {
                tts.voices?.filter { !it.name.isNullOrEmpty() }?.filter { v ->
                    // Only show voices that make sense: Chinese + English voices
                    val lang = v.locale?.language ?: ""
                    lang == "zh" || lang == "cmn" || lang == "eng" || lang == "en"
                }?.map { v ->
                    VoiceInfo(v.name, "${v.name.split("#").lastOrNull() ?: v.name} (${v.locale})")
                } ?: emptyList()
            } catch (e: Exception) { emptyList() }
        }
        fun getCurrentEngineName(): String = currentEngine ?: ""
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

    /** Read the current system default TTS engine from settings */
    private fun readCurrentEngine(): String? {
        return try {
            android.provider.Settings.Secure.getString(appContext.contentResolver, "tts_default_synth")
        } catch (e: Exception) { null }
    }

    /** Get all voices available from the current engine */
    fun getAvailableVoices(): List<Voice> {
        return try {
            globalTts?.voices?.toList()?.filter { !it.name.isNullOrEmpty() } ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    /** Set a specific voice by its name. Returns true if successful. */
    fun setVoice(voiceName: String): Boolean {
        val tts = globalTts ?: return false
        return try {
            val voices = tts.voices
            val match = voices?.find { it.name == voiceName }
            if (match != null) {
                val result = tts.setVoice(match)
                if (result == TextToSpeech.SUCCESS) {
                    currentVoiceName = voiceName
                    dlog("setVoice ✅ $voiceName")
                    true
                } else {
                    dlog("setVoice ❌ $voiceName → $result")
                    false
                }
            } else {
                dlog("setVoice ❌ \"$voiceName\" not found among ${voices?.size ?: 0} voices")
                false
            }
        } catch (e: Exception) {
            dlog("setVoice ❌ exception: ${e.message}")
            false
        }
    }

    private fun ensureInitialized() {
        val systemEngine = readCurrentEngine()

        // Engine already alive and ready — nothing to do.
        // Do NOT call setupTts() again: it resets language and listener,
        // which confuses Oppo/某些引擎导致高亮乱跳。
        if (globalTts != null && currentEngine == systemEngine && globalIsReady) return

        // Engine exists but not yet marked ready — mark it.
        // Language is set per-speak by setLanguageForText(), listener
        // was installed by onInit callback.
        if (globalTts != null && currentEngine == systemEngine) {
            globalIsReady = true
            return
        }

        // Engine changed or first time — shutdown old and recreate
        if (globalTts != null) {
            dlog("引擎变更 $currentEngine → $systemEngine, 重建中...")
            fullDestroyInternal()
        }

        synchronized(initLock) {
            if (globalIsReady || globalTts != null) return

            dlog("ensureInitialized()")
            dlog("系统默认引擎: $systemEngine")

            val ctx = activityContext ?: appContext
            val targetEngine = if (!systemEngine.isNullOrEmpty()) systemEngine else "com.google.android.tts"
            dlog("目标引擎: $targetEngine")

            try {
                globalTts = TextToSpeech(ctx, { status ->
                    dlog("onInit: status=$status")
                    if (status == TextToSpeech.SUCCESS) {
                        globalIsReady = true
                        currentEngine = systemEngine
                        val tts = globalTts
                        dlog("✅ TTS 就绪: ${tts?.defaultEngine}")
                        dlog("可用语音数: ${tts?.voices?.size ?: 0}")
                        // Log first few Chinese voices for debugging
                        tts?.voices?.filter { v ->
                            v.locale?.language == "zh" || v.locale?.language == "cmn"
                        }?.take(8)?.forEach { v ->
                            dlog("  语音: ${v.name} (${v.locale})")
                        }
                        if (tts != null) setupTts(tts)
                        // Re-apply voice if we have one stored
                        val savedVoice = currentVoiceName
                        if (savedVoice != null) {
                            val voices = tts?.voices
                            val match = voices?.find { it.name == savedVoice }
                            if (match != null) tts?.setVoice(match)
                        }
                    } else {
                        dlog("❌ TTS 初始化失败: $status")
                    }
                }, targetEngine)
                dlog("TTS 构造完成")
            } catch (e: Exception) {
                dlog("❌ TTS 构造异常: ${e.message}")
            }
        }
    }

    private fun setupTts(tts: TextToSpeech) {
        try {
            // CRITICAL: set listener BEFORE setLanguage.
            // Some engines (Google TTS) lose onRangeStart support if
            // language is changed after the listener is installed.
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    val listener = id?.let { utteranceListeners[it] }
                    listener?.onStart()
                }
                override fun onDone(id: String?) {
                    val listener = id?.let { utteranceListeners.remove(it) }
                    listener?.onDone()
                }
                override fun onError(id: String?) {
                    val listener = id?.let { utteranceListeners.remove(it) }
                    listener?.onError("TTS错误")
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?, code: Int) {
                    val listener = id?.let { utteranceListeners.remove(it) }
                    listener?.onError("TTS错误($code)")
                }
                override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) {
                    val listener = id?.let { utteranceListeners[it] }
                    listener?.onRange(start, end)
                }
            })
            dlog("Listener 设置完成")

            // Set language AFTER listener to preserve onRangeStart support
            tts.setLanguage(Locale.CHINESE)
            dlog("中文设置完成")
        } catch (e: Exception) {
            dlog("setup 异常: ${e.message}")
        }
    }

    // Cache last language to avoid unnecessary setLanguage() calls.
    // Frequent language switching between utterances can suppress
    // onRangeStart callbacks in Google TTS.
    private var cachedLocale: Locale? = null

    private fun setLanguageForText(text: String) {
        val tts = globalTts ?: return
        val hasChinese = text.any { it.code in 0x4E00..0x9FFF }
        val target = if (hasChinese) Locale.CHINA else Locale.US
        if (target == cachedLocale) return  // Skip if unchanged
        cachedLocale = target
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
            if (retryCount >= 60) { // 60 × 500ms = 30s timeout (generous for engine recreation)
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

        // IMPORTANT: setLanguage before setSpeechRate!
        // Some engines reset speech rate when language changes,
        // so we set language FIRST, then override the rate.
        setLanguageForText(text)
        dlog("speak: rate=$rate")
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
        utteranceListeners.clear()
    }

    override fun destroy() {
        // Only stop, don't shutdown engine.
        // Android TTS Service Binding release is async;
        // shutdown + immediate recreate causes bind conflict, onInit never fires.
        // Engine lives as singleton, next speak() reuses it.
        dlog("destroy() — 仅停止，保留引擎")
        globalTts?.stop()
        utteranceListeners.clear()
    }

    private fun fullDestroyInternal() {
        dlog("fullDestroyInternal()")
        globalTts?.stop()
        globalTts?.shutdown()
        globalTts = null
        globalIsReady = false
        utteranceListeners.clear()
    }

    fun fullDestroy() {
        dlog("fullDestroy()")
        fullDestroyInternal()
        currentEngine = null
        currentVoiceName = null
    }
}
