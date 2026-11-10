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

    @Query("SELECT * FROM words ORDER BY id")
    suspend fun wordSnapshot(): List<Word>

    @Update
    suspend fun updateWords(words: List<Word>)

    @Transaction
    suspend fun mergeVocabulary(incoming: List<Word>) {
        val existing = wordSnapshot().associateBy { it.word.lowercase(java.util.Locale.ROOT) }
        val refreshedHighTags = incoming.flatMap { it.bookIds.split(",") }
            .filter { !it.endsWith("_high") }.toSet().map { it + "_high" }.toSet()
        val merged = linkedMapOf<String, Word>()
        for ((key, word) in existing) {
            val tags = word.bookIds.split(",").filter { it !in refreshedHighTags }
            if (tags.joinToString(",") != word.bookIds) merged[key] = word.copy(bookIds = tags.joinToString(","))
        }
        for (word in incoming) {
            val key = word.word.lowercase(java.util.Locale.ROOT)
            val old = merged[key] ?: existing[key]
            merged[key] = if (old == null) word.copy(id = 0) else old.copy(
                phonetic = word.phonetic.ifBlank { old.phonetic },
                translation = word.translation.ifBlank { old.translation },
                bookIds = (old.bookIds.split(",") + word.bookIds.split(",")).filter { it.isNotBlank() }.distinct().joinToString(",")
            )
        }
        updateWords(merged.values.filter { it.id != 0 })
        insertWords(merged.values.filter { it.id == 0 })
    }

    @Query("SELECT * FROM word_progress WHERE wordId = :wordId")
    suspend fun getProgressForWord(wordId: Int): WordProgress?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgress(progress: WordProgress)

    @Query("DELETE FROM word_progress")
    suspend fun clearAllProgress()

    @Query("DELETE FROM typing_sessions")
    suspend fun clearSessions()

    @Transaction
    suspend fun clearStudyHistory() {
        clearAllProgress()
        clearSessions()
    }

    @Query("SELECT * FROM typing_sessions ORDER BY timestamp DESC")
    fun getAllSessions(): Flow<List<TypingSession>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: TypingSession)
}
