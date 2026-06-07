package com.moyue.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.moyue.app.data.models.Highlight
import kotlinx.coroutines.flow.Flow

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights ORDER BY createdAt DESC")
    fun getAllHighlights(): Flow<List<Highlight>>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapterIndex ASC, startParagraph ASC, startOffset ASC")
    fun getHighlightsForBook(bookId: String): Flow<List<Highlight>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addHighlight(highlight: Highlight): Long

    @Update
    suspend fun updateHighlight(highlight: Highlight)

    @Query("UPDATE highlights SET note = :note WHERE id = :id")
    suspend fun updateHighlightNote(id: Long, note: String?)

    @Delete
    suspend fun deleteHighlight(highlight: Highlight)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteHighlightById(id: Long)

    @Query("DELETE FROM highlights WHERE bookId = :bookId AND chapterIndex = :chapterIndex AND startParagraph = :startParagraph AND startOffset = :startOffset AND endParagraph = :endParagraph AND endOffset = :endOffset")
    suspend fun deleteHighlightByPosition(
        bookId: String,
        chapterIndex: Int,
        startParagraph: Int,
        startOffset: Int,
        endParagraph: Int,
        endOffset: Int
    )
}
