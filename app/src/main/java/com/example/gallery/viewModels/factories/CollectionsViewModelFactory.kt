package com.example.gallery.viewModels.factories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.gallery.GalleryService
import com.example.gallery.db.daos.CollectionDao
import com.example.gallery.folders.CollectionFolderRepository
import com.example.gallery.viewModels.CollectionsViewModel

class CollectionsViewModelFactory(
    private val repository: CollectionFolderRepository,
    private val collectionDao: CollectionDao,
    private val service: GalleryService
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CollectionsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CollectionsViewModel(repository, collectionDao, service) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
