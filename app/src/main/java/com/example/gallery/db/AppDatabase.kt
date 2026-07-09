package com.example.gallery.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.gallery.db.entities.ArabicOcrDoneEntity
import com.example.gallery.db.daos.CategoryDao
import com.example.gallery.db.daos.FaceDao
import com.example.gallery.db.daos.MediaDao
import com.example.gallery.db.daos.PersonDao
import com.example.gallery.db.entities.CategoryEntity
import com.example.gallery.db.entities.FaceEntity
import com.example.gallery.db.entities.FtsMediaEntity
import com.example.gallery.db.entities.MediaCategoryCrossRef
import com.example.gallery.db.entities.MediaEntity
import com.example.gallery.db.entities.PersonEntity

import com.example.gallery.db.daos.CollectionDao
import com.example.gallery.db.entities.CollectionEntity
import com.example.gallery.db.entities.CollectionMediaCrossRef

@Database(
    entities = [
        MediaEntity::class,
        FtsMediaEntity::class,
        PersonEntity::class,
        CategoryEntity::class,
        MediaCategoryCrossRef::class,
        FaceEntity::class,
        CollectionEntity::class,
        CollectionMediaCrossRef::class,
        ArabicOcrDoneEntity::class
    ],
    version = 22,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun personDao(): PersonDao
    abstract fun categoryDao(): CategoryDao
    abstract fun faceDao(): FaceDao
    abstract fun collectionDao(): CollectionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        private const val DB_NAME = "media_ocr_db"

        // v21 → v22: add the arabic_ocr_done marker table (additive; nothing else changes, so
        // existing media/persons/collections/FTS are preserved).
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `arabic_ocr_done` " +
                        "(`mediaId` INTEGER NOT NULL, PRIMARY KEY(`mediaId`))"
                )
            }
        }

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
                .addMigrations(MIGRATION_21_22)
                .fallbackToDestructiveMigration(false)
                .build()
        }
    }
}
