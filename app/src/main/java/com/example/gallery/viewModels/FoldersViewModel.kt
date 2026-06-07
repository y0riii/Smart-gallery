package com.example.gallery.viewModels

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.example.gallery.GalleryService
import com.example.gallery.folders.FolderItem
import com.example.gallery.folders.FolderSource
import com.example.gallery.folders.SortMode
import kotlinx.coroutines.launch

abstract class FoldersViewModel(
    private val folderSource: FolderSource,
    service: GalleryService
) : DeletableViewModel(service) {
    var folders by mutableStateOf<List<FolderItem>>(emptyList())
    var isLoading by mutableStateOf(true)

    var images by mutableStateOf<List<Uri>>(emptyList())

    var selectedFolder by mutableStateOf<FolderItem?>(null)

    var fromDate by mutableStateOf<Long?>(null)
    var toDate by mutableStateOf<Long?>(null)
    var sortMode by mutableStateOf(SortMode.RELEVANCE)

    init {
        viewModelScope.launch {
            folders = folderSource.getFolders()
            isLoading = false
        }
    }

    fun loadFolder(bucketId: Long) {
        selectedFolder = folders.find { it.bucketId == bucketId }
        applyFilters()
    }

    fun applyFilters() {
        val folder = selectedFolder ?: return
        viewModelScope.launch {
            isLoading = true
            images = folderSource.getImages(folder.bucketId, fromDate, toDate, sortMode)
            isLoading = false
        }
    }
    
    fun onDateRangeChanged(from: Long?, to: Long?) {
        if (from != null && to != null && from > to) {
            fromDate = null
            toDate = null
        } else {
            fromDate = from
            toDate = to
        }
        applyFilters()
    }
    
    fun onSortModeChanged(mode: SortMode) {
        sortMode = mode
        applyFilters()
    }

    fun clearSelectedFolder() {
        images = emptyList()
        selectedFolder = null
        fromDate = null
        toDate = null
        sortMode = SortMode.RELEVANCE
    }

    fun clearSearch() {
        fromDate = null
        toDate = null
        sortMode = SortMode.RELEVANCE
        applyFilters()
    }

    override suspend fun onDeleteSuccess(uri: Uri) {
        // Refresh images for the open folder
        selectedFolder?.let { _ ->
            applyFilters()
        }
        // Refresh the folder list in case a folder became empty/too small
        folders = folderSource.getFolders()
    }
}
