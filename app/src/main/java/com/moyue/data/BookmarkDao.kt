package com.moyue.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.moyue.app.data.models.Bookmark
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun getAllBookmarks(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapterIndex ASC, progress ASC")
    fun getBookmarksForBook(bookId: String): Flow<List<Bookmark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addBookmark(bookmark: Bookmark): Long

    @Update
    suspend fun updateBookmark(bookmark: Bookmark)

    @Query("UPDATE bookmarks SET chapterTitle = :title WHERE id = :id")
    suspend fun updateBookmarkTitle(id: Long, title: String)

    @Delete
    suspend fun deleteBookmark(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmarkById(id: Long)
}
