package com.example.gallery.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.gallery.db.entities.CategoryEntity
import com.example.gallery.db.entities.MediaCategoryCrossRef
import com.example.gallery.db.previews.CategoryPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Query("SELECT * FROM category")
    suspend fun getAllCategories(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRefs(refs: List<MediaCategoryCrossRef>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(ref: MediaCategoryCrossRef)

    @Delete
    suspend fun deleteCrossRef(ref: MediaCategoryCrossRef)

    @Query("DELETE FROM category WHERE id = :categoryId")
    suspend fun deleteCategory(categoryId: Long)

    @Query(
        """
        SELECT
            id,
            name
        FROM category
        """
    )
    suspend fun getCategoryPreviews(): List<CategoryPreview>

    @Query(
        """
        SELECT
            *
        FROM media_category_join
        ORDER BY similarity DESC
        """
    )
    suspend fun getCategoryMediaRefs(): List<MediaCategoryCrossRef>

    @Transaction
    suspend fun getCategoriesWithMediaIds():
            List<Pair<CategoryPreview, List<Long>>> {
        return associateMediaIds(
            previews = getCategoryPreviews(),
            refs = getCategoryMediaRefs(),
            previewId = { it.id },
            refOwnerId = { it.categoryId },
            refMediaId = { it.mediaId }
        )
    }

    @Query(
        """
        SELECT
            id,
            name
        FROM category
        """
    )
    fun getCategoryPreviewsFlow(): Flow<List<CategoryPreview>>

    @Query(
        """
        SELECT
            *
        FROM media_category_join
        ORDER BY similarity DESC
        """
    )
    fun getCategoryMediaRefsFlow(): Flow<List<MediaCategoryCrossRef>>

    @Query(
        """
        SELECT mediaId FROM media_category_join
        WHERE categoryId = :categoryId
        ORDER BY similarity DESC
        """
    )
    fun getImagesIdsByCategoryFlow(categoryId: Long): Flow<List<Long>>

    fun getCategoriesWithMediaIdsFlow(): Flow<List<Pair<CategoryPreview, List<Long>>>> {
        return combine(
            getCategoryPreviewsFlow(),
            getCategoryMediaRefsFlow()
        ) { categories, refs ->
            associateMediaIds(categories, refs, { it.id }, { it.categoryId }, { it.mediaId })
        }
    }
}