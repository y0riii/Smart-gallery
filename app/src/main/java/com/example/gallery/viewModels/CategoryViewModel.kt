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
            isLoading = true
            service.createCategory(name)
            folders = categoryFolderRepository.getFolders()
            isLoading = false
        }
    }

    fun deleteCategory() {
        viewModelScope.launch {
            isLoading = true
            val id = selectedFolder?.bucketId
            if (id != null) {
                categoryFolderRepository.deleteCategory(id)
                folders = categoryFolderRepository.getFolders()
                selectedFolder = null
            }
            isLoading = false
        }
    }
}