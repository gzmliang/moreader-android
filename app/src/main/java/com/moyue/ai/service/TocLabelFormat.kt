package com.moyue.ai.service

/**
 * 目录（TOC）章节标题的展示与过滤规则。
 *
 * 存在的意义：很多 EPUB 的 NCX/nav 标题本身已带编号（如 "8. Night Riders"、"第一回 ..."），
 * 旧代码又无条件拼了一层序号，于是界面上出现 "14. 8. Night Riders" 这种双数字。
 * 同时目录里常混入封面、版权页、目录页等附属页，不应出现在"真实章节"选择列表里。
 */
object TocLabelFormat {

    /** 目录标题本身已带编号（"8. Night Riders" / "Chapter 3" / "第一回 ..."） */
    private val LEADING_CHAPTER_NUMBER = Regex(
        "^\\s*(?:(?:chapter|part|book)\\s*[0-9]{1,6}(?:\\s|$|[.．、,，:：)）])|(?:第\\s*)?[0-9一二三四五六七八九十百千]{1,6}\\s*(?:[.．、,，:：)）]|章|节|回|讲|课))",
        RegexOption.IGNORE_CASE
    )

    /** 明显不是正文的附属页（整条标题完全等于这些词才算，避免误杀 "Contents of the Heart" 之类章节名） */
    private val NON_BODY_PAGE = Regex(
        "^\\s*(?:cover|title\\s*page|copyright|copyright\\s*page|contents|table\\s*of\\s*contents|toc|index|colophon|imprint|also\\s*by|about\\s*the\\s*author|front\\s*matter|back\\s*matter|封面|版权(?:页)?|版權|目录|目錄|书名页|書名頁|扉页|扉頁)\\s*$",
        RegexOption.IGNORE_CASE
    )

    /** 清掉目录标题里的换行与多余空白 */
    fun clean(rawLabel: String): String = rawLabel.replace(Regex("\\s+"), " ").trim()

    /** 该目录条目是否属于正文 */
    fun isBodyLabel(rawLabel: String): Boolean {
        val t = clean(rawLabel)
        if (t.isEmpty()) return false
        return !NON_BODY_PAGE.matches(t)
    }

    /** 标题是否已自带章节编号 */
    fun hasLeadingNumber(rawLabel: String): Boolean =
        LEADING_CHAPTER_NUMBER.containsMatchIn(clean(rawLabel))

    /**
     * 统一的章节显示文案：
     * - 标题自带编号（"8. Night Riders"）→ 原样显示，绝不重复拼序号；
     * - 标题无编号 → 前面补上过滤后连续的真实章节序号。
     */
    fun format(seq: Int, rawLabel: String): String {
        val t = clean(rawLabel)
        if (t.isEmpty()) return "$seq"
        return if (hasLeadingNumber(t)) t else "$seq. $t"
    }

    /**
     * 正文目录索引列表。
     * 优先只保留“自带章节编号”的条目（如 Chapter 1..、第一回..）——这类书的前置页（书名/作者/
     * 封面/版权/目录/致谢/预览）全都没编号，一刀切干净；若全书目录都没有编号，则退回按附属页名称过滤。
     */
    fun bodyIndices(labels: List<String>): List<Int> {
        val all = labels.indices.toList()
        if (all.isEmpty()) return all
        val numbered = all.filter { hasLeadingNumber(labels[it]) }
        if (numbered.size >= 3) return numbered
        val kept = all.filter { isBodyLabel(labels[it]) }
        return if (kept.size >= 2) kept else all
    }
}
