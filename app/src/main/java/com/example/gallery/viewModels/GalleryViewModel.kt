package com.example.gallery.viewModels

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.viewModelScope
import com.example.gallery.GalleryService
import com.example.gallery.db.daos.PersonDao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GalleryViewModel(
    service: GalleryService,
    private val personDao: PersonDao
) : DeletableViewModel(service) {

    var images by mutableStateOf<List<Uri>>(emptyList())

    var isSearching by mutableStateOf(false)

    var statusText by mutableStateOf("")

    var prompt by mutableStateOf(TextFieldValue(""))
    var useClip by mutableStateOf(true)
    var fromDate by mutableStateOf<Long?>(null)
    var toDate by mutableStateOf<Long?>(null)

    val allNames = personDao.getAllNamesFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun onPermissionGranted() {
        viewModelScope.launch {
            images = service.getAllDeviceImages()
            service.indexImagesBackground()
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
            statusText = "Searching..."
            
            // Final check on dates: if from > to, treat as empty
            val finalFrom = if (fromDate != null && toDate != null && fromDate!! > toDate!!) null else fromDate
            val finalTo = if (fromDate != null && toDate != null && fromDate!! > toDate!!) null else toDate
            
            // Sync UI state if they were invalid
            if (fromDate != null && toDate != null && fromDate!! > toDate!!) {
                fromDate = null
                toDate = null
            }

            images = if (useClip)
                service.search(prompt.text, finalFrom, finalTo)
            else
                service.searchDocuments(prompt.text, finalFrom, finalTo)

            statusText = if (useClip)
                "Showing CLIP image search results"
            else
                "Showing OCR document search results"

            isSearching = false
        }
    }

    fun clearSearch() {
        prompt = TextFieldValue("")
        fromDate = null
        toDate = null
        useClip = true
        viewModelScope.launch {
            images = service.getAllDeviceImages()
            statusText = "Showing all images."
        }
    }

    override suspend fun onDeleteSuccess(uri: Uri) {
        // Refresh images list after deletion is confirmed
        images = service.getAllDeviceImages()
    }
}
