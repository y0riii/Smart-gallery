package com.example.gallery.viewModels.factories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.gallery.GalleryService
import com.example.gallery.folders.CategoryFolderRepository
import com.example.gallery.viewModels.CategoryViewModel

class CategoryViewModelFactory(
    private val categoryFolderRepository: CategoryFolderRepository,
    private val galleryService: GalleryService
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CategoryViewModel::class.java)) {
            return CategoryViewModel(categoryFolderRepository, galleryService) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}