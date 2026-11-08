package com.moyue.ai.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TocLabelFormatTest {

    @Test
    fun `自带编号的标题不再重复拼序号`() {
        assertEquals("8. Night Riders", TocLabelFormat.format(14, "8. Night Riders"))
        assertEquals("10. A Place of Honor", TocLabelFormat.format(16, "10. A Place of Honor"))
        assertEquals("Chapter 3", TocLabelFormat.format(9, "Chapter 3"))
        assertEquals("第一回 张天师祈禳瘟疫", TocLabelFormat.format(5, "第一回 张天师祈禳瘟疫"))
        assertEquals("3、初见", TocLabelFormat.format(2, "3、初见"))
    }

    @Test
    fun `无编号的标题补上真实章节序号`() {
        assertEquals("3. Night Riders", TocLabelFormat.format(3, "Night Riders"))
        assertEquals("1. Prologue", TocLabelFormat.format(1, "  Prologue  "))
    }

    @Test
    fun `封面版权目录页被识别为非正文`() {
        listOf(
            "Cover", "Title Page", "Copyright", "Table of Contents", "Contents",
            "封面", "版权页", "目录", "扉页"
        ).forEach { assertFalse("应过滤: $it", TocLabelFormat.isBodyLabel(it)) }
    }

    @Test
    fun `正常的正文章节不会被误杀`() {
        listOf(
            "8. Night Riders", "Contents of the Heart", "Index of Suspects",
            "Prologue", "第一章 出发", "关于作者的话"
        ).forEach { assertTrue("应保留: $it", TocLabelFormat.isBodyLabel(it)) }
    }

    @Test
    fun `过滤后索引仍指向原始目录位置`() {
        val labels = listOf("Cover", "8. Night Riders", "Copyright", "10. A Place of Honor")
        assertEquals(listOf(1, 3), TocLabelFormat.bodyIndices(labels))
    }

    @Test
    fun `真实 Magic Tree House 目录只留标了 Chapter 的正文`() {
        val labels = listOf(
            "Magic Tree House #42: A Good Night for Ghosts",
            "Mary Pope Osborne",
            "Cover", "Title Page", "Dear Reader", "Copyright", "Dedication", "Contents",
            "Prologue",
            "Chapter 1 - Way Down Yonder in New Orleans",
            "Chapter 2 - Money Blues",
            "Chapter 3 - Coal Cart Blues",
            "More Facts from Jack and Annie"
        )
        val kept = TocLabelFormat.bodyIndices(labels)
        assertEquals(listOf(9, 10, 11), kept)
        assertEquals(
            listOf("Chapter 1 - Way Down Yonder in New Orleans", "Chapter 2 - Money Blues", "Chapter 3 - Coal Cart Blues"),
            kept.mapIndexed { pos, orig -> TocLabelFormat.format(pos + 1, labels[orig]) }
        )
    }

    @Test
    fun `全是附属页时退回完整目录避免空列表`() {
        val labels = listOf("Cover", "Copyright", "Contents")
        assertEquals(listOf(0, 1, 2), TocLabelFormat.bodyIndices(labels))
    }
}
