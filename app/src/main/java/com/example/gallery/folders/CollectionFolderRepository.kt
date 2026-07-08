package com.example.gallery.folders

import android.net.Uri
import com.example.gallery.GalleryService
import com.example.gallery.SortMode
import com.example.gallery.db.daos.CollectionDao
import com.example.gallery.utils.toMediaUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class CollectionFolderRepository(
    private val collectionDao: CollectionDao,
    private val service: GalleryService
) : FolderSource {

    override fun getFoldersFlow(): Flow<List<FolderItem>> {
        return collectionDao.getCollectionsWithMediaIdsFlow().map { collections ->
            collections.map { (collection, mediaIds) ->
                val thumbnails = mediaIds.map { it.toMediaUri() }.topFourThumbnails()

                FolderItem(
                    bucketId = collection.id,
                    name = collection.name,
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
    ): Flow<com.example.gallery.SearchResult> {
        return collectionDao.getImagesIdsByCollectionFlow(bucketId).map { mediaIds ->
            service.searchWithin(mediaIds, prompt, useClip, fromDate, toDate, sortMode)
        }.flowOn(Dispatchers.IO)
    }

    suspend fun deleteCollection(bucketId: Long) = withContext(Dispatchers.IO) {
        collectionDao.deleteCollection(bucketId)
    }

    suspend fun getFolders(): List<FolderItem> = withContext(Dispatchers.IO) {
        val collections = collectionDao.getCollectionsWithMediaIds()
        collections.map { (collection, mediaIds) ->
            val thumbnails = mediaIds.map { it.toMediaUri() }.topFourThumbnails()

            FolderItem(
                bucketId = collection.id,
                name = collection.name,
                photoCount = mediaIds.size,
                thumbnailUris = thumbnails,
                null
            )
        }.sortedBy { it.name }
    }
}
