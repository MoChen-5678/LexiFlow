package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Word::class, WordProgress::class, TypingSession::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wordDao(): WordDao

    companion object {
        private val repairReviewDates = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""UPDATE word_progress SET nextReviewTime = lastReviewedAt + intervalDays * 86400000
                    WHERE intervalDays > 0 AND abs(nextReviewTime - lastReviewedAt - intervalDays * 120000) <= 2000""")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "qwerty_learn_db"
                )
                .addMigrations(repairReviewDates)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
