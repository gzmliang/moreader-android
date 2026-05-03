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
    val progress: Float = 0f,
    val cfi: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
