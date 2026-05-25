package com.example.gallery.viewModels

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.example.gallery.GalleryService
import com.example.gallery.folders.FolderItem
import com.example.gallery.folders.FolderSource
import kotlinx.coroutines.launch

abstract class FoldersViewModel(
    private val folderSource: FolderSource,
    service: GalleryService
) : DeletableViewModel(service) {
    var folders by mutableStateOf<List<FolderItem>>(emptyList())
    var isLoading by mutableStateOf(true)

    var images by mutableStateOf<List<Uri>>(emptyList())

    var selectedFolder by mutableStateOf<FolderItem?>(null)

    init {
        viewModelScope.launch {
            folders = folderSource.getFolders()
            isLoading = false
        }
    }

    fun loadFolder(bucketId: Long) {
        // Avoid reloading same folder
        if (selectedFolder?.bucketId == bucketId) return

        selectedFolder = folders.find { it.bucketId == bucketId }

        viewModelScope.launch {
            isLoading = true
            images = folderSource.getImages(bucketId)
            isLoading = false
        }
    }

    fun clearSelectedFolder() {
        images = emptyList()
        selectedFolder = null
    }

    override suspend fun onDeleteSuccess(uri: Uri) {
        // Refresh images for the open folder
        selectedFolder?.let { folder ->
            images = folderSource.getImages(folder.bucketId)
        }
        // Refresh the folder list in case a folder became empty/too small
        folders = folderSource.getFolders()
    }
}
