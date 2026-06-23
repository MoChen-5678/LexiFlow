package com.example.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.roundToInt

class WordRepository(private val wordDao: WordDao) {

    val allSessions: Flow<List<TypingSession>> = wordDao.getAllSessions()
    val allWordsWithProgress: Flow<List<WordWithProgress>> = wordDao.getAllWordsWithProgress()

    fun getWordsWithProgressByCategory(category: String): Flow<List<WordWithProgress>> {
        return wordDao.getWordsWithProgressByCategory(category)
    }

    suspend fun ensureWordsPopulated(context: Context) {
        if (wordDao.getWordCount() > 0) return // Already populated with rich dicts

        val filenames = listOf("cet4.json", "cet6.json", "ielts.json", "toefl.json", "coder.json")
        val uniqueWordsMap = mutableMapOf<String, Word>()

        for (filename in filenames) {
            val jsonString = try {
                val inputStream = context.assets.open("dicts/$filename")
                val reader = BufferedReader(InputStreamReader(inputStream))
                val sb = StringBuilder()
                var line: String? = reader.readLine()
                while (line != null) {
                    sb.append(line)
                    line = reader.readLine()
                }
                reader.close()
                sb.toString()
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }

            if (jsonString != null) {
                val jsonArray = JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val wordStr = obj.getString("word").trim()
                    val key = wordStr.lowercase()

                    val phonetic = obj.optString("phonetic", "")
                    val translation = obj.optString("translation", "")
                    val category = obj.optString("category", "")
                    val bookIds = obj.optString("bookIds", "")
                    val exampleSentence = obj.optString("exampleSentence", "")
                    val exampleTranslation = obj.optString("exampleTranslation", "")
                    val exampleSentenceCloze = obj.optString("exampleSentenceCloze", "")
                    val examplesJson = obj.optString("examplesJson", "")

                    val existingWord = uniqueWordsMap[key]
                    if (existingWord != null) {
                        // Merge bookIds & category
                        val mergedBookIds = (existingWord.bookIds.split(",") + bookIds.split(","))
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                            .joinToString(",")

                        uniqueWordsMap[key] = existingWord.copy(
                            bookIds = mergedBookIds,
                            exampleSentence = if (exampleSentence.isNotEmpty()) exampleSentence else existingWord.exampleSentence,
                            exampleTranslation = if (exampleTranslation.isNotEmpty()) exampleTranslation else existingWord.exampleTranslation,
                            exampleSentenceCloze = if (exampleSentenceCloze.isNotEmpty()) exampleSentenceCloze else existingWord.exampleSentenceCloze,
                            examplesJson = if (examplesJson.isNotEmpty()) examplesJson else existingWord.examplesJson
                        )
                    } else {
                        uniqueWordsMap[key] = Word(
                            word = wordStr,
                            phonetic = phonetic,
                            translation = translation,
                            category = category,
                            exampleSentence = exampleSentence,
                            exampleTranslation = exampleTranslation,
                            bookIds = if (bookIds.isNotEmpty()) bookIds else filename.split(".")[0],
                            exampleSentenceCloze = exampleSentenceCloze,
                            examplesJson = examplesJson
                        )
                    }
                }
            }
        }

        if (uniqueWordsMap.isNotEmpty()) {
            wordDao.insertWords(uniqueWordsMap.values.toList())
        }

        // Fallback to static DefaultWords if still empty
        if (wordDao.getWordCount() == 0) {
            wordDao.insertWords(DefaultWords.list)
        }
    }

    /**
     * Updates word learning state using SuperMemo SM-2 Spaced Repetition Algorithm.
     * @param wordId Unique identifier of the word
     * @param quality Quality of response (0-5)
     *   - 5: Perfect (no typos / correct translation selected instantly)
     *   - 4: Correct after hesitation / 1 typo
     *   - 3: Correct with significant typos (2-3)
     *   - 2: Correct with heavy errors or after multiple attempts
     *   - 1: Wrong, but familiar
     *   - 0: Complete memory blank
     */
    suspend fun saveProgress(wordId: Int, quality: Int) {
        val existing = wordDao.getProgressForWord(wordId)
        val isCorrect = quality >= 3
        
        val correct = if (isCorrect) (existing?.correctAttempts ?: 0) + 1 else (existing?.correctAttempts ?: 0)
        val wrong = if (!isCorrect) (existing?.wrongAttempts ?: 0) + 1 else (existing?.wrongAttempts ?: 0)

        val prevRepetitions = existing?.repetitions ?: 0
        val prevIntervalDays = existing?.intervalDays ?: 0
        val prevEaseFactor = existing?.easeFactor ?: 2.5f
        val prevStability = existing?.stability ?: 1.0f
        val prevDifficulty = existing?.difficulty ?: 5.0f
        val prevConsecutiveCorrect = existing?.consecutiveCorrect ?: 0

        val repetitions: Int
        val intervalDays: Int
        var easeFactor = prevEaseFactor
        val consecutiveCorrect: Int
        val stability: Float
        val difficulty: Float
        val memoryLevel: Int

        if (isCorrect) {
            repetitions = prevRepetitions + 1
            consecutiveCorrect = prevConsecutiveCorrect + 1
            
            // Adjust ease factor based on answer quality
            easeFactor += 0.1f - (5 - quality) * (0.08f + (5 - quality) * 0.02f)
            if (easeFactor < 1.3f) easeFactor = 1.3f

            // Adjust difficulty: lower quality increases difficulty
            difficulty = (prevDifficulty + (5.0f - quality) * 0.3f).coerceIn(1.0f, 10.0f)

            // Memory Stability updates
            stability = when (repetitions) {
                1 -> 1.0f
                2 -> 3.0f
                else -> prevStability * easeFactor
            }

            intervalDays = Math.round(stability).coerceAtLeast(1)
            
            // Determine memoryLevel (1 to 5)
            memoryLevel = when {
                repetitions >= 5 -> 5 // Long term mastery
                repetitions >= 4 -> 4 // Highly proficient
                repetitions >= 3 -> 3 // Consolidating
                repetitions >= 2 -> 2 // Short-term memory
                else -> 1 // Newly introduced
            }
        } else {
            repetitions = 0
            consecutiveCorrect = 0
            
            // Severe decay for memory stability and ease factor on failure
            easeFactor = (prevEaseFactor - 0.25f).coerceAtLeast(1.3f)
            difficulty = (prevDifficulty + 0.5f).coerceIn(1.0f, 10.0f)
            stability = (prevStability * 0.35f).coerceAtLeast(0.5f)
            intervalDays = 1
            memoryLevel = 1 // Falls back to initial review level
        }

        // Spaced repetition interval in milliseconds (each interval day = 2 minutes for high real-time responsiveness)
        val intervalMs = intervalDays * 120 * 1000L
        val nextReviewTime = System.currentTimeMillis() + intervalMs

        val isMastered = memoryLevel == 5 || (correct >= 4 && wrong <= 1)

        wordDao.insertProgress(
            WordProgress(
                wordId = wordId,
                correctAttempts = correct,
                wrongAttempts = wrong,
                isMastered = isMastered,
                repetitions = repetitions,
                intervalDays = intervalDays,
                easeFactor = easeFactor,
                nextReviewTime = nextReviewTime,
                memoryLevel = memoryLevel,
                stability = stability,
                difficulty = difficulty,
                consecutiveCorrect = consecutiveCorrect,
                lastReviewedAt = System.currentTimeMillis(),
                totalReviewTimeMs = (existing?.totalReviewTimeMs ?: 0L) + 5000L // Estimating 5s review time
            )
        )
    }

    suspend fun saveSession(session: TypingSession) {
        wordDao.insertSession(session)
    }

    suspend fun clearProgress() {
        wordDao.clearAllProgress()
    }
}
