package com.example.gallery.viewModels

import com.example.gallery.GalleryService
import com.example.gallery.folders.AlbumsFolderRepository

class AlbumsViewModel(
    albumsFolderRepository: AlbumsFolderRepository,
    service: GalleryService
) : FoldersViewModel(albumsFolderRepository, service)