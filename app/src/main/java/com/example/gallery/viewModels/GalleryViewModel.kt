package com.example.gallery.viewModels

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import com.example.gallery.GalleryService
import com.example.gallery.db.daos.PersonDao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.io.File

class GalleryViewModel(
    service: GalleryService,
    private val personDao: PersonDao
) : DeletableViewModel(service) {

    var images by mutableStateOf<List<Uri>>(emptyList())
    /** Maps each URI to its DATE_ADDED timestamp (ms) for date-header grouping in the grid. */
    var mediaTimestamps by mutableStateOf<Map<Uri, Long>>(emptyMap())
        private set

    /**
     * After a semantic search, how many leading [images] are strong matches (above the relevance
     * threshold). The grid draws a "less relevant" separator at this index. null = no separator.
     */
    var relevantCount by mutableStateOf<Int?>(null)
        private set

    private var mediaObserveJob: Job? = null
    private var allDeviceMedia: List<Uri> = emptyList()
    private var allDeviceTimestamps: Map<Uri, Long> = emptyMap()

    var isSearching by mutableStateOf(false)

    var prompt by mutableStateOf(TextFieldValue(""))
    var useClip by mutableStateOf(true)
    var fromDate by mutableStateOf<Long?>(null)
    var toDate by mutableStateOf<Long?>(null)

    var shouldScrollToTop by mutableStateOf(false)

    // Feature 4: use the sort-order-consistent query so the @mention dropdown everywhere
    // shows named people A-Z first and #p1/#p2… placeholders last — same as the folder grid.
    val allNames = personDao.getAllNamesSortedFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Feature 5: map each person name → thumbnail URI so the @mention dropdown on the home
    // screen can show the person's photo. Same thumbnail used by the folder tile caption.
    // thumbnailPath is guarded against empty strings to avoid passing an invalid URI to Coil.
    val nameThumbnails = personDao.getPersonPreviewsFlow()
        .map { previews ->
            previews
                .filter { it.thumbnailPath.isNotEmpty() }
                .associate { preview ->
                    (preview.name ?: "") to File(preview.thumbnailPath).toUri()
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    fun onPermissionGranted() {
        mediaObserveJob?.cancel()
        mediaObserveJob = viewModelScope.launch {
            service.getAllDeviceMediaFlow().collect { pairs ->
                val uris = pairs.map { it.first }
                val timestamps = pairs.associate { it.first to it.second }
                allDeviceMedia = uris
                allDeviceTimestamps = timestamps
                // Only update images when the user is not actively searching
                if (!isSearching && prompt.text.isBlank() && fromDate == null && toDate == null) {
                    images = uris
                    mediaTimestamps = timestamps
                    relevantCount = null   // browsing all media — no relevance separator
                }
            }
        }
        viewModelScope.launch {
            service.startIndexingWorkManager()
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

    fun search() {
        viewModelScope.launch {
            isSearching = true

            // Validate dates: if from > to, treat as empty
            val finalFrom =
                if (fromDate != null && toDate != null && fromDate!! > toDate!!) null else fromDate
            val finalTo =
                if (fromDate != null && toDate != null && fromDate!! > toDate!!) null else toDate

            // Sync UI state if they were invalid
            if (fromDate != null && toDate != null && fromDate!! > toDate!!) {
                fromDate = null
                toDate = null
            }

            val result = if (useClip)
                service.search(prompt.text, finalFrom, finalTo)
            else
                service.searchDocuments(prompt.text, finalFrom, finalTo)

            images = result.uris
            // Raw relevant count (null for non-scored searches). The grid decides when to actually
            // draw the separator; the search-count label reports this number.
            relevantCount = result.relevantCount

            isSearching = false
            // Set flag last so LaunchedEffect fires after all state is settled
            shouldScrollToTop = true
        }
    }

    fun clearSearch() {
        prompt = TextFieldValue("")
        fromDate = null
        toDate = null
        useClip = true
        images = allDeviceMedia
        mediaTimestamps = allDeviceTimestamps
        relevantCount = null
        // Set flag last so LaunchedEffect fires after images list is ready
        shouldScrollToTop = true
    }

    override suspend fun onDeleteSuccess(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val deletedIndex = images.indexOf(uris[0])
        val deletedSet = uris.toSet()
        images = images.filterNot { it in deletedSet }
        if (fullScreenIndex != null) {
            fullScreenIndex = when {
                images.isEmpty() -> null
                deletedIndex >= images.size -> images.size - 1
                else -> deletedIndex
            }
        }
    }
}
