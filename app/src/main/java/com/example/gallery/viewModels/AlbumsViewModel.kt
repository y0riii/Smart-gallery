package com.example.gallery.viewModels

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.example.gallery.GalleryService
import com.example.gallery.db.daos.CollectionDao
import com.example.gallery.db.entities.CollectionEntity
import com.example.gallery.db.entities.CollectionMediaCrossRef
import com.example.gallery.folders.AlbumsFolderRepository
import kotlinx.coroutines.launch

/**
 * The Albums tab. Shows the device's MediaStore folders AND user-created albums (Room-backed
 * "collections"), and owns the CRUD for the user-created ones — create, delete, and add media.
 * User albums are keyed by a negative bucketId (= -collectionId) in the folder list; the positive
 * collection id is used by the add-to-album picker.
 */
class AlbumsViewModel(
    albumsFolderRepository: AlbumsFolderRepository,
    private val collectionDao: CollectionDao,
    service: GalleryService
) : FoldersViewModel(albumsFolderRepository, service) {

    // Enable the grid's multi-select delete. Device folders can't actually be deleted, so
    // performDeleteFolders below simply skips them.
    override val canDeleteFolders: Boolean
        get() = true

    fun createAlbum(name: String) {
        viewModelScope.launch {
            if (name.isNotBlank() && collectionDao.getCollectionByName(name) == null) {
                collectionDao.insertCollection(CollectionEntity(name = name))
                // Scroll the folder grid up so the new album (in "Your albums" near the top) is visible.
                shouldScrollFoldersToTop = true
            }
        }
    }

    /**
     * Adds media to an album. [albumId] is the POSITIVE collection id (from the picker). Duplicate
     * (album, media) pairs are ignored by the cross-ref primary key + IGNORE conflict strategy, so
     * an image already in the album is never added twice.
     */
    fun addMediaToAlbum(uris: Set<Uri>, albumId: Long) {
        viewModelScope.launch {
            val refs = uris.mapNotNull { uri ->
                uri.lastPathSegment?.toLongOrNull()?.let { mediaId ->
                    CollectionMediaCrossRef(
                        collectionId = albumId,
                        mediaId = mediaId,
                        dateAddedMs = System.currentTimeMillis()
                    )
                }
            }
            if (refs.isNotEmpty()) collectionDao.insertCrossRefs(refs)
        }
    }

    /** Album previews for the add-to-album picker (positive collection ids). */
    suspend fun getAlbumsList() = collectionDao.getCollectionPreviews()

    /** Deletes the currently open album; no-op if the open folder is a device folder. */
    fun deleteSelectedAlbum() {
        val folder = selectedFolder ?: return
        if (!folder.isUserAlbum) return
        viewModelScope.launch {
            collectionDao.deleteCollection(-folder.bucketId)
            selectedFolder = null
        }
    }

    override suspend fun performDeleteFolders(context: android.content.Context, bucketIds: List<Long>) {
        // Only user albums (negative bucketIds) are deletable; device folders are silently skipped.
        for (bucketId in bucketIds) {
            if (bucketId < 0) collectionDao.deleteCollection(-bucketId)
        }
    }
}
