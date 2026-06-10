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
        val oneTimeRequest = OneTimeWorkRequestBuilder<GalleryIndexerWorker>().build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "GalleryIndexing_OneTime",
            ExistingWorkPolicy.KEEP,
            oneTimeRequest
        )
        return Result.success()
    }
}
