package com.example.gallery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.gallery.db.FaceClusteringWorker
import com.example.gallery.db.GalleryIndexerWorker
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            Log.d("BootReceiver", "Boot completed, scheduling indexing work")

            val indexRequest = OneTimeWorkRequestBuilder<GalleryIndexerWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    5,
                    TimeUnit.MINUTES
                ).build()

            val clusterRequest = OneTimeWorkRequestBuilder<FaceClusteringWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    5,
                    TimeUnit.MINUTES
                ).build()

            WorkManager.getInstance(context)
                .beginUniqueWork(
                    "GalleryIndexing_OneTime",
                    ExistingWorkPolicy.REPLACE, // Replace any stale work from before reboot
                    indexRequest
                )
                .then(clusterRequest)
                .enqueue()

            Log.d("BootReceiver", "Indexing work enqueued successfully")
        }
    }
}
