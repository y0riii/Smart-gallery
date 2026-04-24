package com.example.gallery.viewModels

import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.gallery.GalleryService
import com.example.gallery.db.daos.PersonDao
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GalleryViewModel(
    private val service: GalleryService,
    private val personDao: PersonDao
) : ViewModel() {

    var images by mutableStateOf<List<Uri>>(emptyList())

    var isSearching by mutableStateOf(false)

    var statusText by mutableStateOf("")

    var intentSenderRequest by mutableStateOf<IntentSenderRequest?>(null)

    val allNames = personDao.getAllNamesFlow().stateIn(
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

    private var pendingDeleteUri: Uri? = null

    fun deleteImage(uri: Uri) {
        viewModelScope.launch {
            val pendingIntent = service.prepareDeleteImage(uri)
            if (pendingIntent != null) {
                pendingDeleteUri = uri
                intentSenderRequest = IntentSenderRequest.Builder(pendingIntent).build()
            }
        }
    }

    fun onDeletionResult(success: Boolean) {
        val uri = pendingDeleteUri
        pendingDeleteUri = null
        intentSenderRequest = null

        if (success && uri != null) {
            viewModelScope.launch {
                service.finalizeDeleteImage(uri)
                // Refresh images list
                images = service.getAllDeviceImages()
            }
        }
    }
}