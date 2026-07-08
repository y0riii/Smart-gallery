package com.example.gallery.viewModels

import android.content.Context
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

    var folderSearchQuery by mutableStateOf("")

    var favoriteFolderIds by mutableStateOf<Set<Long>>(emptySet())
        private set

    open val filteredFolders: List<FolderItem>
        get() {
            val query = folderSearchQuery.trim()
            val baseList = if (query.isEmpty()) {
                folders
            } else {
                folders.filter { it.name.contains(query, ignoreCase = true) }
            }
            return baseList.sortedWith(
                compareByDescending<FolderItem> { favoriteFolderIds.contains(it.bucketId) }
                    .thenBy { it.name.lowercase() }
            )
        }

    var images by mutableStateOf<List<Uri>>(emptyList())

    /** Relevance-separator boundary for an open folder's semantic search (null = no separator). */
    var relevantCount by mutableStateOf<Int?>(null)
        private set

    var selectedFolder by mutableStateOf<FolderItem?>(null)

    var prompt by mutableStateOf(TextFieldValue(""))
    var useClip by mutableStateOf(true)
    var fromDate by mutableStateOf<Long?>(null)
    var toDate by mutableStateOf<Long?>(null)
    var sortMode by mutableStateOf(SortMode.RELEVANCE)

    var shouldScrollToTop by mutableStateOf(false)

    // Feature 3: remembers the folders-grid scroll position so we can restore it when the user
    // navigates back from an open folder.
    var savedGridFirstIndex by mutableStateOf(0)
    var savedGridFirstScrollOffset by mutableStateOf(0)

    // Folder selection state
    var selectedFolderBucketIds by mutableStateOf<Set<Long>>(emptySet())
        private set

    val isSelectingFolders: Boolean
        get() = selectedFolderBucketIds.isNotEmpty()

    open val canDeleteFolders: Boolean
        get() = true

    fun toggleFolderSelection(bucketId: Long) {
        selectedFolderBucketIds = if (selectedFolderBucketIds.contains(bucketId)) {
            selectedFolderBucketIds - bucketId
        } else {
            selectedFolderBucketIds + bucketId
        }
    }

    fun clearFolderSelection() {
        selectedFolderBucketIds = emptySet()
    }

    fun deleteSelectedFolders(context: android.content.Context) {
        val bucketIdsToDelete = selectedFolderBucketIds.toList()
        if (bucketIdsToDelete.isEmpty()) return
        viewModelScope.launch {
            performDeleteFolders(context, bucketIdsToDelete)
            clearFolderSelection()
        }
    }

    abstract suspend fun performDeleteFolders(context: android.content.Context, bucketIds: List<Long>)

    private var foldersJob: kotlinx.coroutines.Job? = null
    private var imagesCollectionJob: kotlinx.coroutines.Job? = null

    init {
        val prefs = service.context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE)
        val key = "fav_folders_${this::class.java.simpleName}"
        val savedSet = prefs.getStringSet(key, emptySet()) ?: emptySet()
        favoriteFolderIds = savedSet.mapNotNull { it.toLongOrNull() }.toSet()
        startCollectingFolders()
    }

    fun toggleFavoriteFolder(bucketId: Long) {
        val updated = if (favoriteFolderIds.contains(bucketId)) {
            favoriteFolderIds - bucketId
        } else {
            favoriteFolderIds + bucketId
        }
        favoriteFolderIds = updated

        val prefs = service.context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE)
        val key = "fav_folders_${this::class.java.simpleName}"
        prefs.edit().putStringSet(key, updated.map { it.toString() }.toSet()).apply()
    }

    fun startCollectingFolders() {
        foldersJob?.cancel()
        foldersJob = viewModelScope.launch {
            isLoading = true
            folderSource.getFoldersFlow().collect { list ->
                folders = list
                selectedFolder?.let { current ->
                    selectedFolder = list.find { it.bucketId == current.bucketId }
                }
                isLoading = false
            }
        }
    }

    fun onPermissionGranted() {
        startCollectingFolders()
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

    fun applyFilters(scrollToTop: Boolean = false) {
        val folder = selectedFolder ?: return
        var firstEmission = scrollToTop  // local flag: only scroll on first emit of this job
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
            ).collect { result ->
                images = result.uris
                // Raw relevant count (null for non-scored searches); the grid guards when to draw
                // the separator, and the count label reports this number.
                relevantCount = result.relevantCount
                isLoading = false
                if (firstEmission) {
                    firstEmission = false
                    shouldScrollToTop = true
                }
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
        applyFilters(scrollToTop = true)
    }

    fun clearSelectedFolder() {
        imagesCollectionJob?.cancel()
        images = emptyList()
        relevantCount = null
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
        applyFilters(scrollToTop = true)
    }

    override suspend fun onDeleteSuccess(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val deletedIndex = images.indexOf(uris[0])
        val deletedSet = uris.toSet()
        // Eagerly remove the URI so the pager never sees a stale index
        images = images.filterNot { it in deletedSet }
        // Adjust the fullscreen viewer
        if (fullScreenIndex != null) {
            fullScreenIndex = when {
                images.isEmpty() -> null
                deletedIndex >= images.size -> images.size - 1
                else -> deletedIndex
            }
        }
        applyFilters()
    }
}
