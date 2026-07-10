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
import com.example.gallery.ui.theme.ThemeMode
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

    // True once a search/filter has been applied and NOT yet cleared. Kept separate from the
    // prompt/date fields so emptying the text box after a search doesn't hide the Clear button or
    // silently drop the (still-filtered) results — only pressing Clear resets it.
    var isSearchActive by mutableStateOf(false)
        private set

    var prompt by mutableStateOf(TextFieldValue(""))
    var useClip by mutableStateOf(true)
    // Opt-in: when off (default), semantic & OCR search cover photos only; on = also search videos.
    var searchVideos by mutableStateOf(false)
    var fromDate by mutableStateOf<Long?>(null)
    var toDate by mutableStateOf<Long?>(null)

    var shouldScrollToTop by mutableStateOf(false)

    // Settings: Arabic OCR opt-in. Reflects the persisted flag; toggling it on kicks off a one-time
    // re-scan so the existing library becomes Arabic-searchable (not just newly-added photos).
    var arabicOcrEnabled by mutableStateOf(service.arabicOcrEnabled)
        private set

    fun updateArabicOcrEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(GalleryService.PREF_ARABIC_OCR, enabled).apply()
        arabicOcrEnabled = enabled
        // Kick the pipeline so it runs index → cluster → Arabic OCR (the Arabic step now sees the
        // flag and scans the library). No-op indexing/clustering just chain straight through.
        if (enabled) service.startIndexingWorkManager()
    }

    // Settings: periodic full re-cluster (drift correction after every 300 new faces). On by default.
    var autoReclusterEnabled by mutableStateOf(
        prefs().getBoolean(GalleryService.PREF_AUTO_RECLUSTER, true)
    )
        private set

    fun updateAutoReclusterEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(GalleryService.PREF_AUTO_RECLUSTER, enabled).apply()
        autoReclusterEnabled = enabled
    }

    // Settings: app appearance. Defaults to SYSTEM on a fresh install; a user choice persists.
    var themeMode by mutableStateOf(loadThemeMode())
        private set

    private fun loadThemeMode(): ThemeMode {
        val saved = prefs().getString(GalleryService.PREF_THEME_MODE, null)
        return ThemeMode.entries.firstOrNull { it.name == saved } ?: ThemeMode.SYSTEM
    }

    fun updateThemeMode(mode: ThemeMode) {
        prefs().edit().putString(GalleryService.PREF_THEME_MODE, mode.name).apply()
        themeMode = mode
    }

    private fun prefs() = service.context
        .getSharedPreferences("gallery_prefs", android.content.Context.MODE_PRIVATE)

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
                // Only update images when the user isn't in an active search (guarding on
                // isSearchActive too, so an emptied-but-not-cleared search isn't clobbered).
                if (!isSearching && !isSearchActive && prompt.text.isBlank() &&
                    fromDate == null && toDate == null) {
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
                service.search(prompt.text, finalFrom, finalTo, searchVideos)
            else
                service.searchDocuments(prompt.text, finalFrom, finalTo, searchVideos)

            images = result.uris
            // Raw relevant count (null for non-scored searches). The grid decides when to actually
            // draw the separator; the search-count label reports this number.
            relevantCount = result.relevantCount
            // A search is "active" (Clear stays available) whenever it actually filtered the view.
            isSearchActive = prompt.text.isNotBlank() || finalFrom != null || finalTo != null

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
        isSearchActive = false
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
