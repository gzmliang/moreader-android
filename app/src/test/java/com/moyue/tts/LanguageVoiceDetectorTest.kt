package com.moyue.tts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 音色选择回归测试：用户把音色设成英文时，读中文必须自动切中文音色，
 * 否则 Edge-TTS 返回空音频（服务端 500: No audio was received）。
 */
class LanguageVoiceDetectorTest {

    @Test
    fun testChineseTextWithEnglishBaseVoicePicksChineseVoice() {
        val v = LanguageVoiceDetector.pickEdgeVoiceForText("后果", "en-US-JennyNeural")
        assertEquals("zh-CN-XiaoxiaoNeural", v)
    }

    @Test
    fun testEnglishTextWithEnglishBaseVoiceKeepsIt() {
        val v = LanguageVoiceDetector.pickEdgeVoiceForText("autonomously", "en-US-JennyNeural")
        assertEquals("en-US-JennyNeural", v)
    }

    @Test
    fun testEnglishTextWithChineseBaseVoiceSwitchesToEnglish() {
        val v = LanguageVoiceDetector.pickEdgeVoiceForText("measure the consequence", "zh-CN-XiaoxiaoNeural")
        assertEquals("en-US-JennyNeural", v)
    }

    @Test
    fun testChineseTextWithChineseBaseVoiceKeepsUserChoice() {
        val v = LanguageVoiceDetector.pickEdgeVoiceForText("衡量其中行为的后果", "zh-CN-YunxiNeural")
        assertEquals("zh-CN-YunxiNeural", v)
    }

    @Test
    fun testBlankTextKeepsBaseVoice() {
        val v = LanguageVoiceDetector.pickEdgeVoiceForText("   ", "en-US-JennyNeural")
        assertEquals("en-US-JennyNeural", v)
    }
}
