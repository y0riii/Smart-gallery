package com.example.gallery.folders

import android.net.Uri
import com.example.gallery.GalleryService
import com.example.gallery.SortMode
import com.example.gallery.db.daos.CategoryDao
import com.example.gallery.utils.VideoUtils
import com.example.gallery.utils.toMediaUri
import com.example.gallery.utils.toVideoUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class CategoryFolderRepository(
    private val categoryDao: CategoryDao,
    private val service: GalleryService
) : FolderSource {

    // Builds up to 4 preview URIs for a category tile, using the video scheme for any video members
    // (AI albums can now contain videos). Only the first 4 ids are type-checked, so it's cheap.
    private fun previewThumbnails(mediaIds: List<Long>): List<Uri> {
        val topIds = mediaIds.take(4)
        val videoIds = VideoUtils.videoIdsAmong(service.context, topIds)
        return topIds.map { if (it in videoIds) it.toVideoUri() else it.toMediaUri() }
    }

    override fun getFoldersFlow(): Flow<List<FolderItem>> {
        return categoryDao.getCategoriesWithMediaIdsFlow().map { categories ->
            categories.map { (category, mediaIds) ->
                val thumbnails = previewThumbnails(mediaIds)

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
        sortMode: SortMode,
        includeVideos: Boolean
    ): Flow<com.example.gallery.SearchResult> {
        // AI albums are matched from CLIP embeddings, so they can contain both photos and indexed
        // videos. The category already stores video member ids; searchWithin builds each member's
        // correct (image/video) URI. includeVideos here only governs the extra semantic/OCR search
        // toggle, not membership, so it's passed straight through.
        return categoryDao.getImagesIdsByCategoryFlow(bucketId).map { mediaIds ->
            service.searchWithin(mediaIds, prompt, useClip, fromDate, toDate, sortMode, includeVideos)
        }.flowOn(Dispatchers.IO)
    }

    suspend fun deleteCategory(bucketId: Long) = withContext(Dispatchers.IO) {
        categoryDao.deleteCategory(bucketId)
    }

    /** Removes media from this AI album's membership (bucketId is the category id). */
    override suspend fun removeMediaFromFolder(bucketId: Long, mediaIds: List<Long>) {
        if (mediaIds.isNotEmpty()) {
            withContext(Dispatchers.IO) { categoryDao.removeImagesFromCategory(bucketId, mediaIds) }
        }
    }

    suspend fun getFolders(): List<FolderItem> = withContext(Dispatchers.IO) {
        val categories = categoryDao.getCategoriesWithMediaIds()
        categories.map { (category, mediaIds) ->
            val thumbnails = previewThumbnails(mediaIds)

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
