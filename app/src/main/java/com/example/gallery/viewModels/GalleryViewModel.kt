package com.example.gallery.viewModels

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.example.gallery.GalleryService
import com.example.gallery.db.daos.PersonDao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GalleryViewModel(
    service: GalleryService,
    personDao: PersonDao
) : DeletableViewModel(service) {

    var images by mutableStateOf<List<Uri>>(emptyList())

    var isSearching by mutableStateOf(false)

    var statusText by mutableStateOf("")

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

    fun search(prompt: String, useClip: Boolean) {
        viewModelScope.launch {
            isSearching = true
            statusText = "Searching..."

            images = if (useClip)
                service.search(prompt)
            else
                service.searchDocuments(prompt)

            statusText = if (useClip)
                "Showing CLIP image search results"
            else
                "Showing OCR document search results"

            isSearching = false
        }
    }

    override suspend fun onDeleteSuccess(uri: Uri) {
        // Refresh images list after deletion is confirmed
        images = service.getAllDeviceImages()
    }
}