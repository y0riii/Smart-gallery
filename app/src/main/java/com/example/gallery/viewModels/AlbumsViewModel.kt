package com.example.gallery.viewModels

import com.example.gallery.GalleryService
import com.example.gallery.folders.AlbumsFolderRepository

class AlbumsViewModel(
    albumsFolderRepository: AlbumsFolderRepository,
    service: GalleryService
) : FoldersViewModel(albumsFolderRepository, service) {
    override val canDeleteFolders: Boolean
        get() = false

    override suspend fun performDeleteFolders(context: android.content.Context, bucketIds: List<Long>) {
        // No-op for albums
    }
}