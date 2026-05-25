package com.example.gallery.viewModels

import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.gallery.GalleryService
import kotlinx.coroutines.launch

/**
 * Base ViewModel that owns the image-deletion flow shared between
 * [GalleryViewModel] and [FoldersViewModel].
 *
 * Subclasses implement [onDeleteSuccess] to refresh their own state
 * after a deletion is confirmed and finalized.
 */
abstract class DeletableViewModel(protected val service: GalleryService) : ViewModel() {

    var intentSenderRequest by mutableStateOf<IntentSenderRequest?>(null)
        private set

    var fullScreenIndex by mutableStateOf<Int?>(null)
        private set

    private var pendingDeleteUri: Uri? = null

    fun openFullScreen(index: Int) {
        fullScreenIndex = index
    }

    fun closeFullScreen() {
        fullScreenIndex = null
    }

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
                onDeleteSuccess(uri)
            }
        }
    }

    /** Called after a deletion is confirmed and finalized. Subclasses refresh their own state here. */
    protected abstract suspend fun onDeleteSuccess(uri: Uri)
}
