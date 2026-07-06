package com.example.gallery.viewModels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.example.gallery.GalleryService
import com.example.gallery.folders.CategoryFolderRepository
import kotlinx.coroutines.launch

class CategoryViewModel(
    private val categoryFolderRepository: CategoryFolderRepository,
    service: GalleryService
) : FoldersViewModel(categoryFolderRepository, service) {
    var isCreatingCategory by mutableStateOf(false)
        private set

    fun createCategory(name: String) {
        viewModelScope.launch {
            isCreatingCategory = true
            try {
                service.createCategory(name)
            } catch (e: Exception) {
                android.util.Log.e("CategoryViewModel", "Failed to create category", e)
            } finally {
                isCreatingCategory = false
            }
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

    override suspend fun performDeleteFolders(context: android.content.Context, bucketIds: List<Long>) {
        bucketIds.forEach { categoryId ->
            categoryFolderRepository.deleteCategory(categoryId)
        }
    }
}