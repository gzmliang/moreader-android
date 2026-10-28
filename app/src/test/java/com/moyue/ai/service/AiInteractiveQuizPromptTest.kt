package com.moyue.ai.service

import com.moyue.ai.model.AiConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiInteractiveQuizPromptTest {

    @Test
    fun testBuildStudyMaterialPrompt() {
        val config = AiConfig(targetLang = "Chinese")
        val title = "物理学导论"
        val text = "光电效应是物理学中一个重要现象。当光线照射在金属表面时，金属内部的电子会吸收光子的能量并逸出表面..."

        val (sys, usr) = AiPromptBuilder.buildStudyMaterialPrompt(config, title, text)
        assertTrue(sys.contains("You are an expert reading comprehension tutor"))
        assertTrue(sys.contains("OUTPUT FORMAT"))
        assertTrue(usr.contains(title))
        assertTrue(usr.contains(text))
    }

    @Test
    fun testParseStudyMaterialResponse() {
        val mockJson = """
            {
              "summary": "介绍了光电效应的基本原理及其与经典波动说的矛盾。",
              "keyPoints": [
                "1. 光电效应中电子的逸出取决于光的频率而非强度。",
                "2. 爱因斯坦引入光子假说成功解释了临界频率。",
                "3. 逸出电子的最大初动能与入射光频率成线性关系。"
              ]
            }
        """.trimIndent()

        val parsed = AiPromptBuilder.parseStudyMaterialResponse(mockJson)
        assertEquals("介绍了光电效应的基本原理及其与经典波动说的矛盾。", parsed.summary)
        assertEquals(3, parsed.keyPoints.size)
        assertTrue(parsed.keyPoints[0].contains("光的频率"))
    }

    @Test
    fun testBuildInteractiveQuizPromptWithSpecialInstructions() {
        val config = AiConfig(targetLang = "Chinese")
        val title = "量子力学漫谈"
        val text = "这是正文材料..."
        val keyPoints = listOf("光子假说", "逸出功概念", "光电倍增管应用")
        val userInstruction = "重点考查光电倍增管的工程应用，出题通俗易懂"
        val avoidQuestions = listOf("光电效应是谁解释的？")

        val (sys, usr) = AiPromptBuilder.buildInteractiveQuizPrompt(
            config = config,
            title = title,
            text = text,
            keyPoints = keyPoints,
            count = 5,
            difficulty = "Basic",
            scope = "interactive",
            userInstruction = userInstruction,
            avoidQuestions = avoidQuestions
        )

        assertTrue(sys.contains("AGREED AND VERIFIED CORE INSIGHTS"))
        assertTrue(sys.contains("光子假说"))
        assertTrue(sys.contains("SPECIAL READER INSTRUCTION"))
        assertTrue(sys.contains(userInstruction))
        assertTrue(sys.contains("AVOID DUPLICATING PREVIOUS QUESTIONS"))
        assertTrue(sys.contains("光电效应是谁解释的？"))
    }
}
