package com.example

import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import com.example.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VocabularySyncTest {
    @Test fun changingHighFrequencyMembershipKeepsSharedProgress() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.wordDao()
            dao.insertWords(listOf(Word(id=11, word="apple", phonetic="", translation="苹果", category="CET-4", bookIds="cet4,cet4_high,ielts,ielts_high")))
            dao.insertProgress(WordProgress(wordId=11, correctAttempts=5))
            assertTrue(dao.wordSnapshot().single().belongsTo("CET-4", "high"))
            dao.mergeVocabulary(listOf(Word(word="apple", phonetic="", translation="苹果", category="CET-4", bookIds="cet4")))
            val word = dao.wordSnapshot().single()
            assertFalse(word.belongsTo("CET-4", "high"))
            assertTrue(word.belongsTo("CET-4", "full"))
            assertTrue(word.belongsTo("IELTS", "high"))
            assertEquals(11, word.id)
            assertEquals(5, dao.getProgressForWord(word.id)!!.correctAttempts)
        } finally { db.close() }
    }

    @Test fun updateIsAtomicChecksumsAreVerifiedAndProgressKeepsItsId() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        context.getSharedPreferences("vocabulary_sync", 0).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.wordDao()
            dao.insertWords(listOf(Word(id = 42, word = "APPLE", translation = "old", phonetic = "", category = "CET-4", bookIds = "cet4")))
            dao.insertProgress(WordProgress(wordId = 42, correctAttempts = 7, memoryLevel = 4))
            val books = listOf("cet4", "cet6", "ielts", "toefl", "coder")
            val bodies = books.associateWith { book ->
                JSONArray().apply {
                    for (i in 0 until 100) put(JSONObject().put("word", if (i == 0) "apple" else "entry$i")
                        .put("phonetic", "test").put("translation", "new").put("category", "CET-4").put("bookIds", book))
                }.toString()
            }
            val versions = bodies.mapValues { (_, body) -> java.security.MessageDigest.getInstance("SHA-256")
                .digest(body.toByteArray()).joinToString("") { "%02x".format(it) } }
            val manifest = JSONObject().put("books", JSONObject().apply {
                books.forEach { put(it, JSONObject().put("version", versions[it]).put("count", 100)) }
            }).toString()
            var corrupt = true
            var requests = 0
            val repo = WordRepository(dao) { path ->
                requests++
                if (path == "/api/vocabulary") manifest else {
                    val book = path.substringAfterLast('/').substringBefore('?')
                    if (corrupt && book == "coder") "[]" else bodies.getValue(book)
                }
            }
            repo.syncVocabulary(context, true)
            assertEquals(1, dao.getWordCount())
            assertEquals("old", dao.wordSnapshot().single().translation)
            corrupt = false
            repo.syncVocabulary(context, true)
            assertEquals(100, dao.getWordCount())
            val apple = dao.wordSnapshot().first { it.word.equals("apple", true) }
            assertEquals(42, apple.id)
            assertEquals("new", apple.translation)
            assertEquals(books.toSet(), apple.bookIds.split(',').toSet())
            assertEquals(7, dao.getProgressForWord(42)!!.correctAttempts)
            requests = 0
            repo.syncVocabulary(context, true)
            assertEquals(1, requests)
        } finally { db.close() }
    }

    @Test fun bundledUpgradeAddsWordsWithoutResettingStudyHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        context.getSharedPreferences("vocabulary_sync", 0).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.wordDao()
            dao.insertWords(listOf(Word(id=8, word="cancel", phonetic="", translation="old", category="CET-4", bookIds="cet4")))
            dao.insertProgress(WordProgress(wordId=8, wrongAttempts=3))
            context.getSharedPreferences("vocabulary_sync", 0).edit()
                .putString("versions", "{\"cet4\":\"old-server-version\"}").putLong("attempt", System.currentTimeMillis()).commit()
            WordRepository(dao).ensureWordsPopulated(context)
            assertTrue(dao.getWordCount() >= 13000)
            assertEquals(8, dao.wordSnapshot().first { it.word == "cancel" }.id)
            assertEquals(3, dao.getProgressForWord(8)!!.wrongAttempts)
            assertEquals("{}", context.getSharedPreferences("vocabulary_sync", 0).getString("versions", ""))
            assertEquals(0L, context.getSharedPreferences("vocabulary_sync", 0).getLong("attempt", 0L))
        } finally { db.close() }
    }
}
