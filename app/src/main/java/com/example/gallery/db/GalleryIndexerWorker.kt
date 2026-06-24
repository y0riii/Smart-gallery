package com.example.gallery.db

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
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
import java.util.concurrent.TimeUnit

class GalleryIndexerWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "GalleryIndexerWorker"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "gallery_indexing_channel"
    }

    override suspend fun doWork(): Result {
        // Promote to a foreground worker so Android doesn't kill it mid-index.
        // After boot, the OS may deny foreground service promotion — we track this
        // so we can skip notification updates (which would crash without a foreground service).
        var isForeground = false
        try {
            setForeground(createForegroundInfo(0, 0))
            isForeground = true
        } catch (e: Exception) {
            Log.w(TAG, "setForeground failed — running as background worker", e)
        }

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        return try {
            val service = GalleryService(applicationContext)
            var lastNotifyTime = 0L

            service.indexImagesBackground { processed, total ->
                val now = System.currentTimeMillis()
                // Throttle updates to at most once every 500ms, but always update on completion
                if (now - lastNotifyTime > 500 || processed == total) {
                    lastNotifyTime = now
                    withContext(Dispatchers.Main) {
                        setProgress(
                            workDataOf(
                                "processed" to processed,
                                "total" to total
                            )
                        )
                    }
                    // Update notification regardless of foreground status so the user knows it's running
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        createForegroundInfo(processed, total).notification
                    )
                }
            }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Worker was stopped by the system (OS pressure, constraints, etc.).
            // The finally block below handles rescheduling.
            // The mutex is safely released by the finally block in indexImagesBackground.
            Log.w(TAG, "Worker was cancelled")
            throw e
        } catch (e: Throwable) {
            // All other errors (TimeoutException, OOM, model init failure, etc.) trigger retry.
            Log.e(TAG, "Indexing failed, will retry", e)
            Result.retry()
        } finally {
            // If the worker was stopped (system killed, cancelled, constraints lost, etc.)
            // rather than completing naturally, re-enqueue the full chain so work is never lost.
            if (isStopped) {
                Log.w(TAG, "Worker was stopped — rescheduling indexing + clustering chain")
                rescheduleWork()
            }
        }
    }

    /**
     * Re-enqueue the full indexing → clustering chain with a small delay
     * to avoid a rapid restart loop if the system is under pressure.
     */
    private fun rescheduleWork() {
        val indexRequest = OneTimeWorkRequestBuilder<GalleryIndexerWorker>()
            .setInitialDelay(1, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .build()

        val clusterRequest = OneTimeWorkRequestBuilder<FaceClusteringWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(applicationContext)
            .beginUniqueWork(
                "GalleryIndexing_OneTime",
                ExistingWorkPolicy.REPLACE,
                indexRequest
            )
            .then(clusterRequest)
            .enqueue()
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
