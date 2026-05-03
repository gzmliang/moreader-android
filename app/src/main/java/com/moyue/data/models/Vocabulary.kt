package com.moyue.app.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vocabulary")
data class Vocabulary(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val word: String,
    val pronunciation: String? = null,
    val partOfSpeech: String? = null,
    val definition: String? = null,
    val example: String? = null,
    val bookId: Long? = null,
    val chapterIndex: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
)
