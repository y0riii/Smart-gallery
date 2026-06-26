package com.example.gallery.db

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

class GalleryPeriodicTriggerWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val indexRequest = OneTimeWorkRequestBuilder<GalleryIndexerWorker>()
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.LINEAR,
                5,
                java.util.concurrent.TimeUnit.MINUTES
            ).build()
            
        WorkManager.getInstance(applicationContext)
            .beginUniqueWork(
                "GalleryIndexing_OneTime",
                ExistingWorkPolicy.REPLACE,
                indexRequest
            )
            .enqueue()
            
        return Result.success()
    }
}
