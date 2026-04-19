package com.example.gallery.components

import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.gallery.GalleryService
import com.example.gallery.db.FaceDao
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GalleryViewModel(
    private val service: GalleryService,
    private val faceDao: FaceDao
) : ViewModel() {

    var images by mutableStateOf<List<Uri>>(emptyList())
        private set

    var isSearching by mutableStateOf(false)
        private set

    var statusText by mutableStateOf("")

    var intentSenderRequest by mutableStateOf<IntentSenderRequest?>(null)
        private set

    val allNames = faceDao.getAllNamesFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

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

    fun deleteImage(uri: Uri) {
        viewModelScope.launch {
            val pendingIntent = service.deleteImage(uri)
            if (pendingIntent != null) {
                intentSenderRequest = IntentSenderRequest.Builder(pendingIntent).build()
            } else {
                // If deleted immediately (Pre-R or DB only)
                images = images.filter { it != uri }
            }
        }
    }

    fun onDeletionResult(success: Boolean) {
        intentSenderRequest = null
        if (success) {
            viewModelScope.launch {
                // Refresh images list
                images = service.getAllDeviceImages()
            }
        }
    }
}