package com.moyue.ai.model

/**
 * 测验会话规则（纯逻辑，便于单元测试）—— 界面只管画，判分/锁定/提交时机都由这里说了算。
 *
 * 口径（梁老师 2026-09-30 定）：
 *  · 默认「完卷提交」：做完再整体改卷；
 *  · 「即时反馈」：答过即锁定（错了也不给改），但仍当场亮出正确答案与解析；
 *  · 两种模式都统一「做完 → 整体提交 → 记一次成绩」，提交后可重做。
 */
object QuizRules {
    const val MODE_SUBMIT = "submit"
    const val MODE_INSTANT = "instant"

    /** 默认模式：完卷提交 */
    fun defaultMode(): String = MODE_SUBMIT

    /** 规范化：只认两个合法值，其余一律回落到默认值（防止旧数据/脏数据把界面搞死） */
    fun normalizeMode(mode: String?): String =
        if (mode == MODE_INSTANT) MODE_INSTANT else MODE_SUBMIT

    /**
     * 此刻能否点选某题的选项？
     * @param alreadyAnswered 该题是否已经答过
     */
    fun canSelectOption(mode: String, isSubmitted: Boolean, alreadyAnswered: Boolean): Boolean {
        if (isSubmitted) return false                                   // 已提交 → 全卷锁定
        if (normalizeMode(mode) == MODE_INSTANT && alreadyAnswered) return false  // 即时反馈 → 答过即锁定
        return true
    }

    /** 单选判对：兼容「A」vs「A. xxx」两种写法 */
    fun isCorrect(correctAnswer: String, selected: String?): Boolean {
        if (selected.isNullOrBlank()) return false
        return selected.equals(correctAnswer, ignoreCase = true) ||
            correctAnswer.startsWith(selected, ignoreCase = true)
    }

    fun score(questions: List<QuizQuestion>, answers: Map<Int, String>): Int =
        questions.count { isCorrect(it.correctAnswer, answers[it.id]) }

    fun scorePercent(correct: Int, total: Int): Int =
        if (total > 0) (correct * 100) / total else 0

    /**
     * 提交按钮该不该出现？
     * · 完卷提交：一直可见（没答完点了会提示）；
     * · 即时反馈：做完最后一题才出现（答完再整体提交）。
     */
    fun shouldShowSubmitButton(mode: String, isSubmitted: Boolean, answeredCount: Int, total: Int): Boolean {
        if (isSubmitted) return false
        return if (normalizeMode(mode) == MODE_INSTANT) answeredCount >= total else total > 0
    }

    /** 此刻点提交算不算数（未答完不准提交） */
    fun canSubmit(answeredCount: Int, total: Int): Boolean = total > 0 && answeredCount >= total
}
