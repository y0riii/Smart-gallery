package com.example.gallery.db

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.gallery.GalleryService

class GalleryPeriodicTriggerWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        // Route through the service so the priority flag (indexingRequested) is raised too — this
        // makes any in-progress Arabic OCR pass yield so the 6-hour indexing runs first, exactly
        // like an app-open trigger. KEEP semantics (force = false) so it won't stomp a running pass.
        GalleryService.getInstance(applicationContext).startIndexingWorkManager(force = false)
        return Result.success()
    }
}
