package com.example.gallery.viewModels

import android.content.Context
import android.content.Intent
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
 *
 * Also owns multi-select state for Feature 1: long-press selection,
 * bulk share, and bulk delete.
 */
abstract class DeletableViewModel(protected val service: GalleryService) : ViewModel() {

    var intentSenderRequest by mutableStateOf<IntentSenderRequest?>(null)
        private set

    var fullScreenIndex by mutableStateOf<Int?>(null)
        protected set

    // ── Single-delete pending URI ──────────────────────────────────────────────
    private var pendingDeleteUri: Uri? = null

    // ── Bulk-delete pending URIs ───────────────────────────────────────────────
    private var pendingBulkDeleteUris: List<Uri>? = null

    // ── Multi-select state (Feature 1) ─────────────────────────────────────────
    /** Set of URIs currently marked as selected by the user. */
    var selectedUris by mutableStateOf<Set<Uri>>(emptySet())
        private set

    /** True whenever at least one image is selected. */
    val isSelecting: Boolean get() = selectedUris.isNotEmpty()


    /**
     * Adds [uri] to the selection if not present, or removes it if already selected.
     */
    fun toggleSelection(uri: Uri) {
        selectedUris = if (uri in selectedUris) {
            selectedUris - uri
        } else {
            selectedUris + uri
        }
    }

    /** Replaces the current selection with a new set of URIs. */
    fun updateSelection(uris: Set<Uri>) {
        selectedUris = uris
    }

    /** Exits selection mode and clears all selected images. */
    fun clearSelection() {
        selectedUris = emptySet()
    }

    // ── Full-screen navigation ─────────────────────────────────────────────────

    fun openFullScreen(index: Int) {
        fullScreenIndex = index
    }

    fun closeFullScreen() {
        fullScreenIndex = null
    }

    // ── Single-image delete ────────────────────────────────────────────────────

    fun deleteImage(uri: Uri) {
        viewModelScope.launch {
            val pendingIntent = service.prepareDeleteImage(uri)
            if (pendingIntent != null) {
                pendingDeleteUri = uri
                intentSenderRequest = IntentSenderRequest.Builder(pendingIntent).build()
            }
        }
    }

    // ── Bulk delete (Feature 1) ────────────────────────────────────────────────

    /**
     * Kicks off a system delete-request for all currently selected images.
     * The user must confirm the system dialog; result is handled in [onDeletionResult].
     */
    fun deleteSelectedImages() {
        val uris = selectedUris.toList()
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val pendingIntent = service.prepareDeleteImages(uris)
            if (pendingIntent != null) {
                // Store as bulk so onDeletionResult knows which path to take
                pendingBulkDeleteUris = uris
                intentSenderRequest = IntentSenderRequest.Builder(pendingIntent).build()
            }
        }
    }

    // ── Bulk share (Feature 1) ─────────────────────────────────────────────────

    /**
     * Fires an ACTION_SEND_MULTIPLE intent to share all selected images at once.
     */
    fun shareSelectedImages(context: Context) {
        val uris = selectedUris.toList()
        if (uris.isEmpty()) return

        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND_MULTIPLE
            // Pass all selected URIs as an ArrayList (required by ACTION_SEND_MULTIPLE)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Images"))
    }

    // ── Deletion result callback ───────────────────────────────────────────────

    fun onDeletionResult(success: Boolean) {
        val singleUri = pendingDeleteUri
        val bulkUris = pendingBulkDeleteUris

        // Clear pending state immediately
        pendingDeleteUri = null
        pendingBulkDeleteUris = null
        intentSenderRequest = null

        if (!success) return

        viewModelScope.launch {
            if (bulkUris != null) {
                // Finalize each deleted URI in the database
                service.finalizeDeleteImages(bulkUris)
                clearSelection() // exit selection mode after bulk delete
                onDeleteSuccess(bulkUris.first()) // notify subclass to refresh
            } else if (singleUri != null) {
                service.finalizeDeleteImage(singleUri)
                onDeleteSuccess(singleUri)
            }
        }
    }

    /** Called after a deletion is confirmed and finalized. Subclasses refresh their own state here. */
    protected abstract suspend fun onDeleteSuccess(uri: Uri)
}
