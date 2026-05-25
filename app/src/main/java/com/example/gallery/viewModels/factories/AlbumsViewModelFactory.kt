package com.example.gallery.viewModels.factories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.gallery.GalleryService
import com.example.gallery.folders.AlbumsFolderRepository
import com.example.gallery.viewModels.AlbumsViewModel

class AlbumsViewModelFactory(
    private val albumsFolderRepository: AlbumsFolderRepository,
    private val galleryService: GalleryService
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AlbumsViewModel::class.java)) {
            return AlbumsViewModel(albumsFolderRepository, galleryService) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}