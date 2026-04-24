package com.example.gallery.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.gallery.db.entities.CategoryEntity
import com.example.gallery.db.entities.MediaCategoryCrossRef
import com.example.gallery.db.entities.MediaEntity

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Query("SELECT * FROM category")
    suspend fun getAllCategories(): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE name = :name LIMIT 1")
    suspend fun getCategoryByName(name: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(ref: MediaCategoryCrossRef)

    @Delete
    suspend fun deleteCrossRef(ref: MediaCategoryCrossRef)

    @Query("DELETE FROM media_category_join WHERE mediaId = :mediaId AND categoryId = :categoryId")
    suspend fun removeImageFromCategory(mediaId: Long, categoryId: Long)

    @Transaction
    @Query(
        """
        SELECT c.* FROM category AS c
        JOIN media_category_join AS j ON c.id = j.categoryId
        WHERE j.mediaId = :mediaId
    """
    )
    suspend fun getCategoriesForImage(mediaId: Long): List<CategoryEntity>

    @Transaction
    @Query(
        """
        SELECT m.* FROM media_items AS m
        JOIN media_category_join AS j ON m.mediaId = j.mediaId
        WHERE j.categoryId = :categoryId
    """
    )
    suspend fun getImagesByCategory(categoryId: Long): List<MediaEntity>
}