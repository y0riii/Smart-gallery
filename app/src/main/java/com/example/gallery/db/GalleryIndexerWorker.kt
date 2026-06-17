package com.example.gallery.db

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.gallery.GalleryService
import com.example.gallery.R

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
        setForeground(createForegroundInfo(0, 0))

        return try {
            val service = GalleryService(applicationContext)
            service.indexImagesBackground { processed, total ->
                setProgress(
                    workDataOf(
                        "processed" to processed,
                        "total" to total
                    )
                )

                setForeground(
                    createForegroundInfo(processed, total)
                )
            }
            Result.success()
        } catch (e: Throwable) {
            Log.e("GalleryIndexerWorker", "Error during indexing", e)
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
