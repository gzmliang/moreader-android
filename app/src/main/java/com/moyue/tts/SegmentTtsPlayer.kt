package com.moyue.app.tts

import android.os.Handler
import android.os.Looper
import com.moyue.tts.LanguageVoiceDetector

/**
 * 短语朗读器 —— 专供「AI 伴读」的题目 / 选项 / 解析朗读使用。
 *
 * 为什么单独做一个：
 *  ① 一次读多段文字，按顺序衔接（双语排版的「先原文、后译文」也能自然连读）；
 *  ② 每一段按内容语言自动挑 Edge-TTS 音色（中文用中文音色、英文用英文音色）；
 *  ③ **自己持有独立的 provider 实例**，绝不改动阅读器正在用的音色 ——
 *     听书用的是 ReaderViewModel 里另一套实例，两边互不干扰、不会串音。
 */
class SegmentTtsPlayer(
    private val endpoint: String = "",
    private val baseVoice: String = "zh-CN-XiaoxiaoNeural",
    private val rate: Float = 1.0f,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val providers = HashMap<String, EdgeTTSProvider>()
    private var seq = 0
    private var speaking = false

    val isSpeaking: Boolean get() = speaking

    private fun providerFor(lang: String): EdgeTTSProvider {
        providers[lang]?.let { return it }
        val voice = LanguageVoiceDetector.getMatchingEdgeVoice(lang, baseVoice)
        return EdgeTTSProvider(endpoint, voice).also { providers[lang] = it }
    }

    /** 朗读若干段文字（自动跳过空白段）。重复调用会先停掉上一次，绝不叠音。 */
    fun speak(
        texts: List<String>,
        onStart: () -> Unit = {},
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        stop()
        val list = texts.map { it.trim() }.filter { it.isNotBlank() }
        if (list.isEmpty()) return

        val mySeq = ++seq
        speaking = true

        fun playAt(index: Int) {
            if (mySeq != seq) return
            if (index >= list.size) {
                speaking = false
                mainHandler.post {
                    if (mySeq == seq) onDone()
                }
                return
            }
            val text = list[index]
            val provider = providerFor(LanguageVoiceDetector.detectLanguage(text))
            provider.speak(text, rate, object : TTSListener {
                override fun onStart() {
                    if (mySeq != seq) return
                    mainHandler.post {
                        if (mySeq == seq) onStart()
                    }
                }

                override fun onDone() {
                    playAt(index + 1)
                }

                override fun onError(message: String) {
                    if (mySeq != seq) return
                    speaking = false
                    mainHandler.post {
                        if (mySeq == seq) onError(message)
                    }
                }
            })
        }
        playAt(0)
    }

    fun stop() {
        seq++
        speaking = false
        providers.values.forEach { try { it.stop() } catch (_: Exception) {} }
    }

    fun destroy() {
        stop()
        providers.values.forEach { try { it.destroy() } catch (_: Exception) {} }
        providers.clear()
    }
}
