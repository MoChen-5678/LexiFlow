package com.example

import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import com.example.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opt in with LEXIFLOW_LIVE_API=http://127.0.0.1:5000 against the WSL container. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveVocabularyTest {
    @Test fun downloadsRealContainerBooksAndKeepsProgress() = runBlocking {
        val base = System.getenv("LEXIFLOW_LIVE_API").orEmpty()
        assumeTrue(base == "http://127.0.0.1:5000")
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        context.getSharedPreferences("vocabulary_sync", 0).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.wordDao()
            dao.insertWords(listOf(Word(id=15, word="cancel", phonetic="", translation="old", category="CET-4", bookIds="cet4")))
            dao.insertProgress(WordProgress(wordId=15, correctAttempts=9))
            val repo = WordRepository(dao) { path ->
                val connection = java.net.URL(base + path).openConnection() as java.net.HttpURLConnection
                try {
                    connection.connectTimeout = 5000
                    connection.readTimeout = 15000
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally { connection.disconnect() }
            }
            repo.syncVocabulary(context, true)
            assertTrue(repo.vocabularyStatus.value, repo.vocabularyStatus.value.startsWith("已同步"))
            assertEquals(13014, dao.getWordCount())
            assertEquals(15, dao.wordSnapshot().first { it.word == "cancel" }.id)
            assertEquals(9, dao.getProgressForWord(15)!!.correctAttempts)
        } finally { db.close() }
    }
}
