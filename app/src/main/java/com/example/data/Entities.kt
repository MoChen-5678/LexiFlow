package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "words")
data class Word(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val word: String,
    val phonetic: String,
    val translation: String,
    val category: String, // e.g. "CET-4", "CET-6", "IELTS", "TOEFL", "Coder"
    val exampleSentence: String = "",
    val exampleTranslation: String = "",
    val exampleSentence2: String = "",
    val exampleTranslation2: String = "",
    val exampleSentence3: String = "",
    val exampleTranslation3: String = "",
    val bookIds: String = "", // Comma-separated categories/books (e.g. "cet4,cet6,ielts")
    val exampleSentenceCloze: String = "", // Sentence with blanked out word "_______"
    val examplesJson: String = "" // Serialized array of examples for extra questions
)

@Entity(tableName = "word_progress")
data class WordProgress(
    @PrimaryKey val wordId: Int,
    val correctAttempts: Int = 0,
    val wrongAttempts: Int = 0,
    val isMastered: Boolean = false,
    
    // SuperMemo SM-2 Spaced Repetition Metrics
    val repetitions: Int = 0,
    val intervalDays: Int = 0,
    val easeFactor: Float = 2.5f,
    val nextReviewTime: Long = 0L,

    // LexiFlow memory model metrics
    val memoryLevel: Int = 1, // Current memory level 1-5
    val stability: Float = 1.0f, // Stability of memory (higher means slower decay)
    val difficulty: Float = 5.0f, // Difficulty of word (1.0 to 10.0)
    val consecutiveCorrect: Int = 0, // Current streak of correct answers
    val lastReviewedAt: Long = System.currentTimeMillis(),
    val totalReviewTimeMs: Long = 0L
)

@Entity(tableName = "typing_sessions")
data class TypingSession(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val category: String,
    val wordsTyped: Int,
    val wpm: Int,
    val accuracy: Float,
    val timestamp: Long = System.currentTimeMillis()
)
