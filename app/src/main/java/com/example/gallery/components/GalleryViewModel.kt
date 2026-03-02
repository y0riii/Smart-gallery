package com.example.gallery.components

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.gallery.GalleryService
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class GalleryViewModel(
    private val service: GalleryService
) : ViewModel() {

    var images by mutableStateOf<List<Uri>>(emptyList())
        private set

    var isSearching by mutableStateOf(false)
        private set

    var statusText by mutableStateOf("")

    private var preloadJob: Deferred<Unit>

    init {
        preloadJob = viewModelScope.async(Dispatchers.IO) {
            service.preloadTextModel()
        }
    }

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

            preloadJob.await()   // 🔒 Ensure model is ready

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
}