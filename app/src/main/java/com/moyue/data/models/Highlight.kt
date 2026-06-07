package com.moyue.app.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "highlights")
data class Highlight(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: String,
    val chapterIndex: Int,
    val startParagraph: Int,
    val startOffset: Int,
    val endParagraph: Int,
    val endOffset: Int,
    val text: String,
    val note: String? = null,
    val color: Int = 0xFFFFFF00.toInt(), // Default yellow
    val createdAt: Long = System.currentTimeMillis()
)
