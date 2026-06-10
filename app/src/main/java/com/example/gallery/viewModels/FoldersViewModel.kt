package com.example.gallery.viewModels

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.viewModelScope
import com.example.gallery.GalleryService
import com.example.gallery.SortMode
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

    var prompt by mutableStateOf(TextFieldValue(""))
    var useClip by mutableStateOf(true)
    var fromDate by mutableStateOf<Long?>(null)
    var toDate by mutableStateOf<Long?>(null)
    var sortMode by mutableStateOf(SortMode.RELEVANCE)

    var shouldScrollToTop by mutableStateOf(false)

    private var imagesCollectionJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            folderSource.getFoldersFlow().collect { list ->
                folders = list
                selectedFolder?.let { current ->
                    selectedFolder = list.find { it.bucketId == current.bucketId }
                }
                isLoading = false
            }
        }
    }

    fun loadFolder(bucketId: Long) {
        // Clear previous folder state
        prompt = TextFieldValue("")
        fromDate = null
        toDate = null
        sortMode = SortMode.RELEVANCE
        
        selectedFolder = folders.find { it.bucketId == bucketId }
        applyFilters()
    }

    fun applyFilters() {
        val folder = selectedFolder ?: return
        imagesCollectionJob?.cancel()
        imagesCollectionJob = viewModelScope.launch {
            isLoading = true
            folderSource.getImagesFlow(
                bucketId = folder.bucketId,
                prompt = prompt.text.takeIf { it.isNotBlank() },
                useClip = useClip,
                fromDate = fromDate,
                toDate = toDate,
                sortMode = sortMode
            ).collect { list ->
                images = list
                isLoading = false
                shouldScrollToTop = true
            }
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
    }

    fun onSortModeChanged(mode: SortMode) {
        sortMode = mode
        applyFilters()
    }

    fun clearSelectedFolder() {
        imagesCollectionJob?.cancel()
        images = emptyList()
        selectedFolder = null
        prompt = TextFieldValue("")
        fromDate = null
        toDate = null
        sortMode = SortMode.RELEVANCE
    }

    fun clearSearch() {
        prompt = TextFieldValue("")
        fromDate = null
        toDate = null
        sortMode = SortMode.RELEVANCE
        shouldScrollToTop = true
        applyFilters()
    }

    override suspend fun onDeleteSuccess(uri: Uri) {
        applyFilters()
    }
}
