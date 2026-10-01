package com.moyue.ai.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测验会话规则的行为验证（纯 JVM，不需要手机）。
 *
 * 对应梁老师 2026-09-30 定的口径：
 *  · 默认完卷提交；
 *  · 即时反馈答过即锁定（错了也不给改）；
 *  · 两种模式统一「做完 → 提交 → 记一次成绩」，提交后可重做。
 */
class QuizRulesTest {

    private fun q(id: Int, correct: String, options: List<String> = listOf("A. x", "B. y", "C. z", "D. w")) =
        QuizQuestion(
            id = id,
            questionOriginal = "Q$id",
            questionTranslation = "题$id",
            options = options,
            correctAnswer = correct
        )

    private val questions = listOf(q(1, "A"), q(2, "B"), q(3, "C"), q(4, "D"), q(5, "B"))

    // ── 默认模式 ──────────────────────────────────────────────
    @Test
    fun `default mode is submit`() {
        assertEquals(QuizRules.MODE_SUBMIT, QuizRules.defaultMode())
    }

    @Test
    fun `garbage or null mode falls back to submit`() {
        assertEquals(QuizRules.MODE_SUBMIT, QuizRules.normalizeMode(null))
        assertEquals(QuizRules.MODE_SUBMIT, QuizRules.normalizeMode(""))
        assertEquals(QuizRules.MODE_SUBMIT, QuizRules.normalizeMode("Instant"))
        assertEquals(QuizRules.MODE_INSTANT, QuizRules.normalizeMode("instant"))
    }

    // ── 选项锁定 ──────────────────────────────────────────────
    @Test
    fun `submit mode allows changing answer before submit`() {
        assertTrue(QuizRules.canSelectOption(QuizRules.MODE_SUBMIT, isSubmitted = false, alreadyAnswered = false))
        assertTrue(QuizRules.canSelectOption(QuizRules.MODE_SUBMIT, isSubmitted = false, alreadyAnswered = true))
    }

    @Test
    fun `instant mode locks the question right after the first tap`() {
        assertTrue(QuizRules.canSelectOption(QuizRules.MODE_INSTANT, isSubmitted = false, alreadyAnswered = false))
        assertFalse(QuizRules.canSelectOption(QuizRules.MODE_INSTANT, isSubmitted = false, alreadyAnswered = true))
    }

    @Test
    fun `submitted quiz is locked in both modes`() {
        assertFalse(QuizRules.canSelectOption(QuizRules.MODE_SUBMIT, isSubmitted = true, alreadyAnswered = false))
        assertFalse(QuizRules.canSelectOption(QuizRules.MODE_INSTANT, isSubmitted = true, alreadyAnswered = false))
        assertFalse(QuizRules.canSelectOption(QuizRules.MODE_INSTANT, isSubmitted = true, alreadyAnswered = true))
    }

    // ── 判分 ──────────────────────────────────────────────────
    @Test
    fun `single choice scoring matches letter and letter-dot-prefix answers`() {
        assertTrue(QuizRules.isCorrect("A", "A"))
        assertTrue(QuizRules.isCorrect("A. 兔子", "A"))
        assertTrue(QuizRules.isCorrect("a", "A"))
        assertFalse(QuizRules.isCorrect("A", "B"))
        assertFalse(QuizRules.isCorrect("A", null))
        assertFalse(QuizRules.isCorrect("A", ""))
    }

    @Test
    fun `score counts only correct answers and ignores unanswered`() {
        val answers = mapOf(1 to "A", 2 to "C", 3 to "C")   // 2 错，4/5 未答
        assertEquals(2, QuizRules.score(questions, answers))
    }

    @Test
    fun `full marks and zero marks`() {
        val perfect = mapOf(1 to "A", 2 to "B", 3 to "C", 4 to "D", 5 to "B")
        assertEquals(5, QuizRules.score(questions, perfect))
        assertEquals(0, QuizRules.score(questions, emptyMap()))
    }

    @Test
    fun `score percent guards divide by zero`() {
        assertEquals(0, QuizRules.scorePercent(0, 0))
        assertEquals(60, QuizRules.scorePercent(3, 5))
        assertEquals(100, QuizRules.scorePercent(5, 5))
    }

    // ── 提交按钮的出现时机 ────────────────────────────────────
    @Test
    fun `submit button is always visible in submit mode until submitted`() {
        assertTrue(QuizRules.shouldShowSubmitButton(QuizRules.MODE_SUBMIT, isSubmitted = false, answeredCount = 0, total = 5))
        assertTrue(QuizRules.shouldShowSubmitButton(QuizRules.MODE_SUBMIT, isSubmitted = false, answeredCount = 5, total = 5))
        assertFalse(QuizRules.shouldShowSubmitButton(QuizRules.MODE_SUBMIT, isSubmitted = true, answeredCount = 5, total = 5))
    }

    @Test
    fun `instant mode shows submit button only after the last question is answered`() {
        assertFalse(QuizRules.shouldShowSubmitButton(QuizRules.MODE_INSTANT, isSubmitted = false, answeredCount = 4, total = 5))
        assertTrue(QuizRules.shouldShowSubmitButton(QuizRules.MODE_INSTANT, isSubmitted = false, answeredCount = 5, total = 5))
        assertFalse(QuizRules.shouldShowSubmitButton(QuizRules.MODE_INSTANT, isSubmitted = true, answeredCount = 5, total = 5))
    }

    @Test
    fun `cannot submit before every question is answered`() {
        assertFalse(QuizRules.canSubmit(answeredCount = 4, total = 5))
        assertTrue(QuizRules.canSubmit(answeredCount = 5, total = 5))
        assertFalse(QuizRules.canSubmit(answeredCount = 0, total = 0))
    }
}
