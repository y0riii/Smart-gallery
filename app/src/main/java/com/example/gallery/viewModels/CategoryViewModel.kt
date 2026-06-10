package com.example.gallery.viewModels

import androidx.lifecycle.viewModelScope
import com.example.gallery.GalleryService
import com.example.gallery.folders.CategoryFolderRepository
import kotlinx.coroutines.launch

class CategoryViewModel(
    private val categoryFolderRepository: CategoryFolderRepository,
    service: GalleryService
) : FoldersViewModel(categoryFolderRepository, service) {
    fun createCategory(name: String) {
        viewModelScope.launch {
            service.createCategory(name)
        }
    }

    fun deleteCategory() {
        viewModelScope.launch {
            val id = selectedFolder?.bucketId
            if (id != null) {
                categoryFolderRepository.deleteCategory(id)
                selectedFolder = null
            }
        }
    }
}