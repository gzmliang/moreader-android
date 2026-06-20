package com.moyue.app.data

import androidx.room.*
import com.moyue.app.data.models.Book
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastReadAt DESC, addedAt DESC")
    fun getAllBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books ORDER BY lastReadAt DESC, addedAt DESC")
    suspend fun getAllBooksOnce(): List<Book>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBook(id: String): Book?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: Book)

    @Delete
    suspend fun delete(book: Book)

    @Query("UPDATE books SET lastReadAt = :timestamp, currentChapterHref = :href, currentChapterIndex = :index, currentProgress = :progress, currentCfi = :cfi, currentParagraphIndex = :paraIdx, themeId = :theme, fontSize = :fSize WHERE id = :id")
    suspend fun updateProgress(id: String, timestamp: Long, href: String?, index: Int, progress: Float, cfi: String?, paraIdx: Int, theme: String, fSize: Int)

    @Query("UPDATE books SET ttsProvider = :provider, ttsVoice = :voice, ttsSpeed = :speed WHERE id = :id")
    suspend fun updateTtsConfig(id: String, provider: String, voice: String, speed: Float)
}
