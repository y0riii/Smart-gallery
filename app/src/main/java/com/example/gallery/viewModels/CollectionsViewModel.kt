package com.example.gallery.viewModels

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.example.gallery.GalleryService
import com.example.gallery.db.daos.CollectionDao
import com.example.gallery.db.entities.CollectionEntity
import com.example.gallery.db.entities.CollectionMediaCrossRef
import com.example.gallery.folders.CollectionFolderRepository
import kotlinx.coroutines.launch

class CollectionsViewModel(
    private val repository: CollectionFolderRepository,
    private val collectionDao: CollectionDao,
    service: GalleryService
) : FoldersViewModel(repository, service) {

    fun createCollection(name: String) {
        viewModelScope.launch {
            if (name.isNotBlank()) {
                val existing = collectionDao.getCollectionByName(name)
                if (existing == null) {
                    collectionDao.insertCollection(CollectionEntity(name = name))
                }
            }
        }
    }

    override suspend fun performDeleteFolders(context: android.content.Context, bucketIds: List<Long>) {
        for (id in bucketIds) {
            repository.deleteCollection(id)
        }
    }

    fun addMediaToCollection(uris: Set<Uri>, collectionId: Long) {
        viewModelScope.launch {
            val refs = uris.mapNotNull { uri ->
                uri.lastPathSegment?.toLongOrNull()?.let { mediaId ->
                    CollectionMediaCrossRef(
                        collectionId = collectionId,
                        mediaId = mediaId,
                        dateAddedMs = System.currentTimeMillis()
                    )
                }
            }
            if (refs.isNotEmpty()) {
                collectionDao.insertCrossRefs(refs)
            }
        }
    }

    suspend fun getCollectionsList() = collectionDao.getCollectionPreviews()

    fun deleteCollection() {
        viewModelScope.launch {
            val id = selectedFolder?.bucketId
            if (id != null) {
                repository.deleteCollection(id)
                selectedFolder = null
            }
        }
    }
}
