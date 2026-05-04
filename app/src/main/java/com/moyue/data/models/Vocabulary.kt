package com.moyue.app.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vocabulary")
data class Vocabulary(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val word: String,
    // Pronunciation: IPA for English, Pinyin for Chinese
    val pronunciation: String? = null,
    // Part of speech: "n. 名词" / "v. 动词" etc.
    val partOfSpeech: String? = null,
    // --- New structured fields (v1.3.0+) ---
    // Chinese definition (中文释义)
    val chineseDef: String? = null,
    // English definition
    val englishDef: String? = null,
    // Word forms / 组词: JSON array ["form1", "form2"]
    val wordForms: String? = null,
    // Example sentence: JSON {"text": "...", "translation": "..."}
    val exampleJson: String? = null,
    // --- Legacy fields (kept for migration) ---
    val definition: String? = null,
    val example: String? = null,
    // Metadata
    val bookId: Long? = null,
    val chapterIndex: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
)
