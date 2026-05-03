package com.moyue.app.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String? = null,
    val paragraphIndex: Int = 0,        // 精确定位到段落
    val paragraphText: String? = null,  // 段落内容预览
    val progress: Float = 0f,
    val createdAt: Long = System.currentTimeMillis()
)
