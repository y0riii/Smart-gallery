package com.example.gallery.db

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.gallery.GalleryService
import com.example.gallery.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GalleryIndexerWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "gallery_indexing_channel"
    }

    override suspend fun doWork(): Result {
        // Promote to a foreground worker so Android doesn't kill it mid-index.
        // Wrapped in try-catch: on some devices setForeground throws if the OS
        // hasn't allocated the foreground service slot yet.
        try {
            setForeground(createForegroundInfo(0, 0))
        } catch (e: Exception) {
            Log.e("GalleryIndexerWorker", "setForeground failed (non-fatal)", e)
        }

        val notificationManager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        return try {
            val service = GalleryService(applicationContext)
            service.indexImagesBackground { processed, total ->
                withContext(Dispatchers.Main) {
                    setProgress(
                        workDataOf(
                            "processed" to processed,
                            "total" to total
                        )
                    )
                }
                // Update the notification directly — much cheaper than calling setForeground
                // in a loop (avoids repeated IPC round-trips).
                notificationManager.notify(
                    NOTIFICATION_ID,
                    createForegroundInfo(processed, total).notification
                )
            }
            
            // Indexing completed successfully. Enqueue clustering as a SEPARATE
            // unique work chain, so that if the indexer is replaced by a periodic
            // trigger later, it doesn't cancel the clustering run mid-flight.
            val clusterRequest = OneTimeWorkRequestBuilder<FaceClusteringWorker>()
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.LINEAR,
                    5,
                    java.util.concurrent.TimeUnit.MINUTES
                ).build()
                
            WorkManager.getInstance(applicationContext)
                .beginUniqueWork(
                    "GalleryClustering_OneTime",
                    ExistingWorkPolicy.KEEP,
                    clusterRequest
                )
                .enqueue()
                
            Result.success()
        } catch (e: Throwable) {
            // Catch Throwable (not just Exception) so OutOfMemoryErrors also trigger retry.
            Log.e("GalleryIndexerWorker", "Indexing failed, will retry", e)
            Result.retry()
        }
    }

    private fun createNotificationChannel() {
        Log.d("GalleryWorker", "Creating notification channel")
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gallery Indexing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while Smart Gallery indexes your photos in the background."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(
        processed: Int,
        total: Int
    ): ForegroundInfo {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Smart Gallery")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)

        if (total == 0) {
            notification
                .setContentText("Preparing photo indexing...")
                .setProgress(0, 0, true)
        } else {
            notification
                .setContentText("Indexed $processed of $total photos")
                .setProgress(total, processed, false)
        }

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }
}
