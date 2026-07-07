package com.example.gallery.folders

import android.net.Uri
import com.example.gallery.GalleryService
import com.example.gallery.SortMode
import com.example.gallery.db.daos.CategoryDao
import com.example.gallery.utils.toMediaUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class CategoryFolderRepository(
    private val categoryDao: CategoryDao,
    private val service: GalleryService
) : FolderSource {

    override fun getFoldersFlow(): Flow<List<FolderItem>> {
        return categoryDao.getCategoriesWithMediaIdsFlow().map { categories ->
            categories.map { (category, mediaIds) ->
                val thumbnails = mediaIds.map { it.toMediaUri() }.padToFour()

                FolderItem(
                    bucketId = category.id,
                    name = category.name,
                    photoCount = mediaIds.size,
                    thumbnailUris = thumbnails,
                    null
                )
            }.sortedBy { it.name }
        }.flowOn(Dispatchers.IO)
    }

    override fun getImagesFlow(
        bucketId: Long,
        prompt: String?,
        useClip: Boolean,
        fromDate: Long?,
        toDate: Long?,
        sortMode: SortMode
    ): Flow<List<Uri>> {
        return categoryDao.getImagesIdsByCategoryFlow(bucketId).map { mediaIds ->
            service.searchWithin(mediaIds, prompt, useClip, fromDate, toDate, sortMode)
        }.flowOn(Dispatchers.IO)
    }

    suspend fun deleteCategory(bucketId: Long) = withContext(Dispatchers.IO) {
        categoryDao.deleteCategory(bucketId)
    }

    suspend fun getFolders(): List<FolderItem> = withContext(Dispatchers.IO) {
        val categories = categoryDao.getCategoriesWithMediaIds()
        categories.map { (category, mediaIds) ->
            val thumbnails = mediaIds.map { it.toMediaUri() }.padToFour()

            FolderItem(
                bucketId = category.id,
                name = category.name,
                photoCount = mediaIds.size,
                thumbnailUris = thumbnails,
                null
            )
        }.sortedBy { it.name }
    }
}
