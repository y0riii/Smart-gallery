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
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.gallery.utils.isVideoUri

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
    // Monotonically increasing counter that guarantees LaunchedEffect restarts
    // for every new intentSenderRequest, even if two requests are referentially similar.
    var intentSenderVersion by mutableStateOf(0)
        private set

    var fullScreenIndex by mutableStateOf<Int?>(null)
        protected set

    // ── Single-delete pending URI ──────────────────────────────────────────────
    private var pendingDeleteUri: Uri? = null

    // ── Bulk-delete: remaining URI batches waiting for confirmation ────────────
    // Each inner list is one system-dialog batch (≤ BATCH_SIZE items).
    private var pendingDeleteBatches: ArrayDeque<List<Uri>> = ArrayDeque()
    // The batch that has been sent to the current system dialog.
    private var pendingBulkDeleteUris: List<Uri>? = null
    // Accumulates all URIs that have already been confirmed and finalized.
    private var confirmedDeleteUris: MutableList<Uri> = mutableListOf()

    companion object {
        private const val TAG = "DeletableViewModel"
        private const val BATCH_SIZE = 200
    }

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
                intentSenderVersion++
            }
        }
    }

    // ── Bulk delete (Feature 1) ────────────────────────────────────────────────

    /**
     * Kicks off a system delete-request for all currently selected images.
     * Large selections are split into batches of [BATCH_SIZE] to avoid a
     * TransactionTooLargeException in the Binder IPC call inside
     * MediaStore.createDeleteRequest.
     * The user confirms one system dialog per batch; [onDeletionResult] then
     * automatically triggers the next batch until all images are deleted.
     */
    fun deleteSelectedImages() {
        val uris = selectedUris.toList()
        if (uris.isEmpty()) return
        // Partition into batches and queue them; confirmedDeleteUris starts empty.
        pendingDeleteBatches = ArrayDeque(uris.chunked(BATCH_SIZE))
        confirmedDeleteUris = mutableListOf()
        triggerNextDeleteBatch()
    }

    /** Pops the next batch from the queue and raises the system dialog for it. */
    private fun triggerNextDeleteBatch() {
        val batch = pendingDeleteBatches.removeFirstOrNull()
        if (batch == null) {
            Log.d(TAG, "triggerNextDeleteBatch: no more batches in queue")
            return
        }
        Log.d(TAG, "triggerNextDeleteBatch: popped batch of ${batch.size}, ${pendingDeleteBatches.size} remaining")
        viewModelScope.launch {
            try {
                // Delay to allow the activity to finish the previous dialog transition and fully resume.
                delay(500)
                Log.d(TAG, "triggerNextDeleteBatch: delay done, preparing delete intent")
                val pendingIntent = service.prepareDeleteImages(batch)
                Log.d(TAG, "triggerNextDeleteBatch: prepareDeleteImages returned ${if (pendingIntent != null) "intent" else "null"}")
                if (pendingIntent != null) {
                    pendingBulkDeleteUris = batch
                    intentSenderRequest = IntentSenderRequest.Builder(pendingIntent).build()
                    intentSenderVersion++
                    Log.d(TAG, "triggerNextDeleteBatch: set intentSenderVersion=$intentSenderVersion")
                } else {
                    // Pre-R: no system dialog needed, finalize directly.
                    confirmedDeleteUris.addAll(batch)
                    if (pendingDeleteBatches.isEmpty()) {
                        finalizeBulkDelete()
                    } else {
                        triggerNextDeleteBatch()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "triggerNextDeleteBatch: exception", e)
                // On failure, continue with remaining batches to avoid stalling
                if (pendingDeleteBatches.isEmpty()) {
                    finalizeBulkDelete()
                } else {
                    triggerNextDeleteBatch()
                }
            }
        }
    }

    /** Called once all batches are confirmed; runs DB cleanup and notifies subclass. */
    private fun finalizeBulkDelete() {
        val allDeleted = confirmedDeleteUris.toList()
        confirmedDeleteUris = mutableListOf()
        Log.d(TAG, "finalizeBulkDelete: finalizing ${allDeleted.size} URIs")
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                service.finalizeDeleteImages(allDeleted)
            }
            clearSelection()
            onDeleteSuccess(allDeleted)
        }
    }

    // ── Bulk share (Feature 1) ─────────────────────────────────────────────────

    /**
     * Fires an ACTION_SEND_MULTIPLE intent to share all selected images at once.
     */
    fun shareSelectedImages(context: Context) {
        val uris = selectedUris.toList()
        if (uris.isEmpty()) return

        val hasVideo = uris.any { it.isVideoUri() }
        val hasImage = uris.any { !it.isVideoUri() }

        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND_MULTIPLE
            // Pass all selected URIs as an ArrayList (required by ACTION_SEND_MULTIPLE)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            type = when {
                hasVideo && hasImage -> "*/*"
                hasVideo -> "video/*"
                else -> "image/*"
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val title = when {
            hasVideo && hasImage -> "Share Media"
            hasVideo -> "Share Videos"
            else -> "Share Images"
        }
        context.startActivity(Intent.createChooser(shareIntent, title))
    }

    // ── Deletion result callback ───────────────────────────────────────────────

    fun onDeletionResult(success: Boolean) {
        Log.d(TAG, "onDeletionResult: success=$success, pendingBulk=${pendingBulkDeleteUris?.size}, pendingSingle=$pendingDeleteUri, batchesLeft=${pendingDeleteBatches.size}")
        val singleUri = pendingDeleteUri
        val confirmedBatch = pendingBulkDeleteUris

        // Clear per-batch state immediately
        pendingDeleteUri = null
        pendingBulkDeleteUris = null
        intentSenderRequest = null

        viewModelScope.launch {
            // Some Android devices/ROMs return RESULT_CANCELED (success=false) even when
            // the user successfully confirms deletion in the MediaStore dialog.
            // If success is false, check if the files were actually deleted before aborting.
            val actualSuccess = if (success) {
                true
            } else if (confirmedBatch != null) {
                val firstUri = confirmedBatch.firstOrNull()
                if (firstUri == null) {
                    true
                } else {
                    var isDeleted = false
                    // Poll for up to 15 times (3 seconds total) to allow slow background system deletion to start
                    for (i in 1..15) {
                        delay(200)
                        if (service.isUriDeleted(firstUri)) {
                            isDeleted = true
                            break
                        }
                    }
                    Log.d(TAG, "onDeletionResult: resultCode was CANCELED, polled deletion status=$isDeleted")
                    isDeleted
                }
            } else if (singleUri != null) {
                var isDeleted = false
                for (i in 1..15) {
                    delay(200)
                    if (service.isUriDeleted(singleUri)) {
                        isDeleted = true
                        break
                    }
                }
                isDeleted
            } else {
                false
            }

            Log.d(TAG, "onDeletionResult: actualSuccess=$actualSuccess, batchesLeft=${pendingDeleteBatches.size}")

            if (!actualSuccess) {
                // User cancelled this batch — discard remaining batches
                Log.d(TAG, "onDeletionResult: aborting — user cancelled")
                pendingDeleteBatches.clear()
                // Finalize any batches that were already confirmed and deleted from device
                if (confirmedDeleteUris.isNotEmpty()) {
                    Log.d(TAG, "onDeletionResult: finalizing ${confirmedDeleteUris.size} already-confirmed URIs")
                    finalizeBulkDelete()
                } else {
                    clearSelection()
                }
                return@launch
            }

            if (confirmedBatch != null) {
                // Accumulate this confirmed batch
                confirmedDeleteUris.addAll(confirmedBatch)
                if (pendingDeleteBatches.isNotEmpty()) {
                    // More batches remain — show the next system dialog
                    Log.d(TAG, "onDeletionResult: triggering next batch")
                    triggerNextDeleteBatch()
                } else {
                    // All batches confirmed — run DB cleanup once for everything
                    Log.d(TAG, "onDeletionResult: all batches done, finalizing")
                    finalizeBulkDelete()
                }
            } else if (singleUri != null) {
                service.finalizeDeleteImage(singleUri)
                onDeleteSuccess(listOf(singleUri))
            }
        }
    }

    /** Called after a deletion is confirmed and finalized. Subclasses refresh their own state here. */
    protected abstract suspend fun onDeleteSuccess(uris: List<Uri>)
}
