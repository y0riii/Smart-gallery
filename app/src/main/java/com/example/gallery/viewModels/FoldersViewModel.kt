package com.example.gallery.viewModels

import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.gallery.GalleryService
import com.example.gallery.folders.FolderItem
import com.example.gallery.folders.FolderSource
import kotlinx.coroutines.launch

class FoldersViewModel(
    private val folderSource: FolderSource,
    private val service: GalleryService
) : ViewModel() {
    var folders by mutableStateOf<List<FolderItem>>(emptyList())
    var isLoading by mutableStateOf(true)

    var images by mutableStateOf<List<Uri>>(emptyList())

    var selectedFolderId by mutableStateOf<Long?>(null)

    var intentSenderRequest by mutableStateOf<IntentSenderRequest?>(null)


    init {
        viewModelScope.launch {
            folders = folderSource.getFolders()
            isLoading = false
        }
    }

    fun loadFolder(bucketId: Long) {
        // Avoid reloading same folder
        if (selectedFolderId == bucketId) return

        selectedFolderId = bucketId

        viewModelScope.launch {
            isLoading = true
            images = folderSource.getImages(bucketId)
            isLoading = false
        }
    }

    fun clear() {
        images = emptyList()
        selectedFolderId = null
    }

    fun deleteImage(uri: Uri) {
        viewModelScope.launch {
            val pendingIntent = service.deleteImage(uri)
            if (pendingIntent != null) {
                intentSenderRequest = IntentSenderRequest.Builder(pendingIntent).build()
            } else {
                images = images.filter { it != uri }
            }
        }
    }
}