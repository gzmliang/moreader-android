package com.moyue.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AIVoiceBaseUrlTest {

    @Test
    fun `空地址返回 null 而不是拼出 相对路径 导致崩溃`() {
        assertNull(AIVoiceTTSProvider.normalizeBaseUrl(""))
        assertNull(AIVoiceTTSProvider.normalizeBaseUrl("   "))
        assertNull(AIVoiceTTSProvider.normalizeBaseUrl("/audio"))
        assertNull(AIVoiceTTSProvider.normalizeBaseUrl("http://"))
    }

    @Test
    fun `正常地址保留并去掉末尾斜杠`() {
        assertEquals("https://api.siliconflow.cn/v1", AIVoiceTTSProvider.normalizeBaseUrl("https://api.siliconflow.cn/v1/"))
        assertEquals("http://192.168.199.159:5001", AIVoiceTTSProvider.normalizeBaseUrl(" http://192.168.199.159:5001 "))
    }

    @Test
    fun `漏写 scheme 自动补 https`() {
        assertEquals("https://api.openai.com/v1", AIVoiceTTSProvider.normalizeBaseUrl("api.openai.com/v1"))
    }
}
