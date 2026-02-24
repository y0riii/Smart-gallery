package com.example.gallery.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    // CRITICAL: Include both the main data entity and the FTS index entity.
    // Room will manage the creation and synchronization of the FTS table based on FtsMediaEntity.
    entities = [MediaEntity::class, FtsMediaEntity::class],
    version = 7,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        private const val DB_NAME = "media_ocr_db"

        fun getDatabase(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }

        private fun buildDatabase(appContext: Context): AppDatabase {
            return Room.databaseBuilder(
                appContext,
                AppDatabase::class.java,
                DB_NAME
            )
                // The manual SQL callback logic is removed as Room handles the FTS table automatically.
                // Using destructive migration to ensure a clean database setup
                // when moving from manual FTS to Room-managed FTS.
                .fallbackToDestructiveMigration(false)
                .build()
        }
    }
}