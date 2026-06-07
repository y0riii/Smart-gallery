package com.example.gallery.viewModels

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
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

<<<<<<< Updated upstream
=======
    var prompt by mutableStateOf(TextFieldValue(""))
    var useClip by mutableStateOf(true)
    var fromDate by mutableStateOf<Long?>(null)
    var toDate by mutableStateOf<Long?>(null)
    var sortMode by mutableStateOf(SortMode.RELEVANCE)

    var shouldScrollToTop by mutableStateOf(false)

>>>>>>> Stashed changes
    init {
        viewModelScope.launch {
            folders = folderSource.getFolders()
            isLoading = false
        }
    }

    fun loadFolder(bucketId: Long) {
<<<<<<< Updated upstream
        // Avoid reloading same folder
        if (selectedFolder?.bucketId == bucketId) return

        selectedFolder = folders.find { it.bucketId == bucketId }
=======
        // Clear previous folder state
        prompt = TextFieldValue("")
        fromDate = null
        toDate = null
        sortMode = SortMode.RELEVANCE
        
        selectedFolder = folders.find { it.bucketId == bucketId }
        applyFilters()
    }
>>>>>>> Stashed changes

        viewModelScope.launch {
            isLoading = true
<<<<<<< Updated upstream
            images = folderSource.getImages(bucketId)
=======
            images = folderSource.getImages(
                bucketId = folder.bucketId,
                prompt = prompt.text.takeIf { it.isNotBlank() },
                useClip = useClip,
                fromDate = fromDate,
                toDate = toDate,
                sortMode = sortMode
            )
>>>>>>> Stashed changes
            isLoading = false
            // The UI will observe images change and scroll to top if shouldScrollToTop is true
        }
    }
<<<<<<< Updated upstream
=======

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
>>>>>>> Stashed changes

    fun clearSelectedFolder() {
        images = emptyList()
        selectedFolder = null
<<<<<<< Updated upstream
    }

    override suspend fun onDeleteSuccess(uri: Uri) {
        // Refresh images for the open folder
        selectedFolder?.let { folder ->
            images = folderSource.getImages(folder.bucketId)
        }
        // Refresh the folder list in case a folder became empty/too small
=======
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
>>>>>>> Stashed changes
        folders = folderSource.getFolders()
    }
}
