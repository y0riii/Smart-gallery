package com.example.gallery.db

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.util.Log
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
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "gallery_indexing_channel"

        @Volatile
        var isPaused = false

        /**
         * Immediately post the "paused" notification, reusing the same
         * NOTIFICATION_ID so it replaces the active one in-place.
         */
        fun showPausedNotification(context: Context) {
            ensureChannel(context)

            val resumeIntent = Intent(context, GalleryIndexerReceiver::class.java).apply {
                action = "com.example.gallery.ACTION_START_INDEXING"
            }
            val resumePendingIntent = PendingIntent.getBroadcast(
                context, 1, resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val playIcon = Icon.createWithResource(context, R.drawable.ic_notif_play)
            val playAction = Notification.Action.Builder(playIcon, "", resumePendingIntent).build()

            val notification = Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("Smart Gallery")
                .setContentText("Photo indexing is paused")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .addAction(playAction)
                .setStyle(
                    Notification.MediaStyle()
                        .setShowActionsInCompactView(0)
                )
                .build()

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
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
    }

    override suspend fun doWork(): Result {
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
                // ── KEY FIX: never overwrite the "paused" notification ──
                if (!isPaused) {
                    withContext(Dispatchers.Main) {
                        setProgress(
                            workDataOf(
                                "processed" to processed,
                                "total" to total
                            )
                        )
                    }
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        createForegroundInfo(processed, total).notification
                    )
                }
            }

            // Indexing completed successfully — enqueue face clustering
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
            Log.e("GalleryIndexerWorker", "Indexing failed, will retry", e)
            Result.retry()
        }
    }

    private fun createForegroundInfo(
        processed: Int,
        total: Int
    ): ForegroundInfo {
        ensureChannel(applicationContext)

        val pauseIntent = Intent(applicationContext, GalleryIndexerReceiver::class.java).apply {
            action = "com.example.gallery.ACTION_PAUSE_INDEXING"
        }
        val pausePendingIntent = PendingIntent.getBroadcast(
            applicationContext, 0, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIcon = Icon.createWithResource(applicationContext, R.drawable.ic_notif_pause)
        val pauseAction = Notification.Action.Builder(pauseIcon, "", pausePendingIntent).build()

        val contentText = if (total == 0) {
            "Preparing photo indexing…"
        } else {
            "Indexed $processed of $total photos"
        }

        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Smart Gallery")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .addAction(pauseAction)
            .setStyle(
                Notification.MediaStyle()
                    .setShowActionsInCompactView(0)
            )
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }
}
