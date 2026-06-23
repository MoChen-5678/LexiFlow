package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class WordWithProgress(
    @Embedded val word: Word,
    @Relation(
        parentColumn = "id",
        entityColumn = "wordId"
    )
    val progress: WordProgress?
)

@Dao
interface WordDao {
    @Transaction
    @Query("""
        SELECT * FROM words 
        WHERE :category = 'all' OR :category = 'ALL' 
           OR category = :category 
           OR bookIds LIKE '%' || :category || '%' 
           OR (:category = 'CET-4' AND (bookIds LIKE '%cet4%' OR category = 'CET-4'))
           OR (:category = 'CET-6' AND (bookIds LIKE '%cet6%' OR category = 'CET-6'))
           OR (:category = 'IELTS' AND (bookIds LIKE '%ielts%' OR category = 'IELTS'))
           OR (:category = 'TOEFL' AND (bookIds LIKE '%toefl%' OR category = 'TOEFL'))
           OR (:category = 'Coder' AND (bookIds LIKE '%coder%' OR category = 'Coder'))
        ORDER BY id ASC
    """)
    fun getWordsWithProgressByCategory(category: String): Flow<List<WordWithProgress>>

    @Transaction
    @Query("SELECT * FROM words ORDER BY id ASC")
    fun getAllWordsWithProgress(): Flow<List<WordWithProgress>>

    @Query("SELECT COUNT(*) FROM words")
    suspend fun getWordCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWords(words: List<Word>)

    @Query("SELECT * FROM word_progress WHERE wordId = :wordId")
    suspend fun getProgressForWord(wordId: Int): WordProgress?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgress(progress: WordProgress)

    @Query("DELETE FROM word_progress")
    suspend fun clearAllProgress()

    @Query("SELECT * FROM typing_sessions ORDER BY timestamp DESC")
    fun getAllSessions(): Flow<List<TypingSession>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: TypingSession)
}
