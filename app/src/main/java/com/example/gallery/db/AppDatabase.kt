package com.example.gallery.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.gallery.db.daos.CategoryDao
import com.example.gallery.db.daos.MediaDao
import com.example.gallery.db.daos.PersonDao
import com.example.gallery.db.entities.CategoryEntity
import com.example.gallery.db.entities.FtsMediaEntity
import com.example.gallery.db.entities.MediaCategoryCrossRef
import com.example.gallery.db.entities.MediaEntity
import com.example.gallery.db.entities.MediaPersonCrossRef
import com.example.gallery.db.entities.PersonEntity

@Database(
    entities = [
        MediaEntity::class,
        FtsMediaEntity::class,
        PersonEntity::class,
        MediaPersonCrossRef::class,
        CategoryEntity::class,
        MediaCategoryCrossRef::class
    ],
    version = 10,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun personDao(): PersonDao
    abstract fun categoryDao(): CategoryDao

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
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
