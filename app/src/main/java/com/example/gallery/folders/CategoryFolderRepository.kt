package com.example.gallery.folders

import android.net.Uri
import com.example.gallery.db.daos.CategoryDao
import com.example.gallery.db.entities.MediaEntity
import com.example.gallery.utils.toMediaUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CategoryFolderRepository(
    private val categoryDao: CategoryDao
) : FolderSource {

    override suspend fun getFolders(): List<FolderItem> = withContext(Dispatchers.IO) {
        val categories = categoryDao.getCategoriesWithMediaIds()

        categories.map { (category, mediaIds) ->
            val thumbnails = mediaIds.take(4).map { it.toMediaUri() }

            FolderItem(
                bucketId = category.id,
                name = category.name,
                photoCount = mediaIds.size,
                thumbnailUris = thumbnails,
                null
            )
        }.sortedBy { it.name }
    }

    override suspend fun getImages(
        bucketId: Long,
        fromDate: Long?,
        toDate: Long?,
        sortMode: SortMode
    ): List<Uri> = withContext(Dispatchers.IO) {
        val images = if (sortMode == SortMode.DATE_DESC) {
            categoryDao.getImagesByCategorySortedByDate(bucketId)
        } else {
            categoryDao.getImagesByCategory(bucketId)
        }

        val filtered = if (fromDate == null && toDate == null) {
            images
        } else {
            images.filter { entity ->
                val afterFrom = fromDate == null || entity.timestampMs >= fromDate
                val beforeTo = toDate == null || entity.timestampMs <= toDate
                afterFrom && beforeTo
            }
        }
        filtered.map { it.mediaId.toMediaUri() }
    }

    suspend fun deleteCategory(bucketId: Long) = withContext(Dispatchers.IO) {
        categoryDao.deleteCategory(bucketId)
    }
}
