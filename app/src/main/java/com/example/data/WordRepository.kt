package com.example.data

import android.content.Context
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import kotlin.math.roundToInt

class WordRepository(private val wordDao: WordDao, private val networkFetch: ((String) -> String)? = null) {

    val allSessions: Flow<List<TypingSession>> = wordDao.getAllSessions()
    val allWordsWithProgress: Flow<List<WordWithProgress>> = wordDao.getAllWordsWithProgress()

    fun getWordsWithProgressByCategory(category: String): Flow<List<WordWithProgress>> {
        return wordDao.getWordsWithProgressByCategory(category)
    }

    private val populateLock = kotlinx.coroutines.sync.Mutex()

    suspend fun ensureWordsPopulated(context: Context) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        populateLock.withLock { populateWords(context) }
    }

    val vocabularyStatus = kotlinx.coroutines.flow.MutableStateFlow("等待同步")
    val vocabularySyncing = kotlinx.coroutines.flow.MutableStateFlow(false)

    private suspend fun populateWords(context: Context) {
        val prefs = context.getSharedPreferences("vocabulary_sync", Context.MODE_PRIVATE)
        if (wordDao.getWordCount() > 0 && prefs.getString("bundled", "") == "1.4-editions") return
        val words = listOf("cet4", "cet6", "ielts", "toefl", "coder").flatMap { book ->
            val json = context.assets.open("dicts/$book.json").bufferedReader().use { it.readText() }
            parseVocabulary(json)
        }
        wordDao.mergeVocabulary(words)
        prefs.edit().putString("bundled", "1.4-editions").putString("versions", "{}").remove("attempt").commit()
    }

    suspend fun syncVocabulary(context: Context, force: Boolean = false) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        populateLock.withLock {
            val prefs = context.getSharedPreferences("vocabulary_sync", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (!force && now - prefs.getLong("attempt", 0) < 15 * 60 * 1000) return@withLock
            vocabularySyncing.value = true
            vocabularyStatus.value = "正在检查词库更新…"
            prefs.edit().putLong("attempt", now).commit()
            try {
                val manifest = org.json.JSONObject(fetchVocabulary("/api/vocabulary")).getJSONObject("books")
                val versions = org.json.JSONObject(prefs.getString("versions", "{}") ?: "{}")
                val incoming = mutableListOf<Word>()
                for (book in listOf("cet4", "cet6", "ielts", "toefl", "coder")) {
                    val entry = manifest.getJSONObject(book)
                    val version = entry.getString("version")
                    require(version.matches(Regex("[a-f0-9]{64}")))
                    if (versions.optString(book) == version) continue
                    val body = fetchVocabulary("/api/vocabulary/$book?version=$version")
                    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    require(hash == version) { "vocabulary checksum mismatch" }
                    val words = parseVocabulary(body)
                    require(words.size == entry.getInt("count") && words.size in 100..30000)
                    require(words.all { word -> word.bookIds.split(",").let { tags ->
                        book in tags && tags.all { it == book || it == book + "_high" }
                    } })
                    incoming.addAll(words)
                    versions.put(book, version)
                }
                wordDao.mergeVocabulary(incoming)
                prefs.edit().putString("versions", versions.toString()).putLong("success", now).commit()
                val count = wordDao.getWordCount()
                val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(now))
                vocabularyStatus.value = "已同步 · $count 词 · $time"
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                vocabularyStatus.value = "暂时无法连接，已保留本地词库，稍后可重试"
            } finally {
                vocabularySyncing.value = false
            }
        }
    }

    private fun fetchVocabulary(path: String): String {
        networkFetch?.let { return it(path) }
        val connection = java.net.URL(com.example.BuildConfig.API_BASE + path).openConnection() as java.net.HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 15000
            require(connection.responseCode == 200)
            val bytes = connection.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = stream.read(buffer)
                    if (n == -1) break
                    require(out.size() + n <= 12_000_000)
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            require(bytes.size <= 12_000_000)
            return bytes.toString(Charsets.UTF_8)
        } finally { connection.disconnect() }
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

        val intervalMs = java.util.concurrent.TimeUnit.DAYS.toMillis(intervalDays.toLong())
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
        wordDao.clearStudyHistory()
    }
}

internal fun parseVocabulary(json: String): List<Word> {
    val rows = JSONArray(json)
    require(rows.length() in 1..30000)
    return (0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        val word = row.getString("word").trim()
        val translation = row.getString("translation").trim()
        require(word.isNotBlank() && word.length <= 120 && translation.isNotBlank() && translation.length <= 3000)
        Word(word = word, phonetic = row.optString("phonetic"), translation = translation,
            category = row.getString("category"), bookIds = row.getString("bookIds"))
    }
}
