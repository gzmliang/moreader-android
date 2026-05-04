package com.moyue.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.moyue.app.data.models.Vocabulary
import kotlinx.coroutines.flow.Flow

@Dao
interface VocabularyDao {
    @Query("SELECT * FROM vocabulary ORDER BY createdAt DESC")
    fun getAllVocabulary(): Flow<List<Vocabulary>>

    @Query("SELECT * FROM vocabulary WHERE word = :word LIMIT 1")
    suspend fun getVocabularyByWord(word: String): Vocabulary?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVocabulary(vocabulary: Vocabulary): Long

    @Update
    suspend fun updateVocabulary(vocabulary: Vocabulary)

    @Query("UPDATE vocabulary SET pronunciation = :pronunciation, partOfSpeech = :partOfSpeech, definition = :definition, example = :example WHERE id = :id")
    suspend fun updateDefinition(id: Long, pronunciation: String?, partOfSpeech: String?, definition: String?, example: String?)

    @Query("UPDATE vocabulary SET pronunciation = :pronunciation, partOfSpeech = :partOfSpeech, chineseDef = :chineseDef, englishDef = :englishDef, wordForms = :wordForms, exampleJson = :exampleJson WHERE id = :id")
    suspend fun updateVocabularyStructured(id: Long, pronunciation: String?, partOfSpeech: String?, chineseDef: String?, englishDef: String?, wordForms: String?, exampleJson: String?)

    @Query("DELETE FROM vocabulary WHERE id = :id")
    suspend fun deleteVocabulary(id: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM vocabulary WHERE word = :word)")
    suspend fun isWordExists(word: String): Boolean
}
