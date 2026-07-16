package com.example.gallery.db

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.gallery.GalleryService
import com.example.gallery.R
import kotlinx.coroutines.sync.withLock

class FaceClusteringWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "FaceClusteringWorker"
        const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "face_clustering_channel"
    }

    override suspend fun doWork(): Result {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        val notification = buildNotification()

        // Post notification manually first to ensure it shows immediately,
        // even if setForeground() fails due to background service start restrictions.
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to manually post notification", e)
        }

        try {
            setForeground(ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        } catch (e: Exception) {
            Log.e(TAG, "setForeground failed (non-fatal)", e)
        }

        // Hold a partial wake lock while clustering runs — like indexing, the foreground service keeps
        // the process alive but doesn't stop the CPU suspending when the screen is off. Released in
        // finally; reclaimed by the OS if the process dies.
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "SmartGallery:Clustering"
        )
        // Safety-net timeout (30 min): clustering is CPU-only math, so 30 minutes is generous.
        // If it somehow hangs, the OS auto-releases the lock instead of draining the battery.
        wakeLock.acquire(30 * 60 * 1000L)

        return try {
            val galleryService = GalleryService.getInstance(applicationContext)
            var didRun = false
            GalleryService.mlExecutionLock.withLock {
                GalleryService.isClusteringRunning = true
                try {
                    didRun = galleryService.createClusters()
                } finally {
                    GalleryService.isClusteringRunning = false
                }
            }
            nm.cancel(NOTIFICATION_ID)
            if (didRun) {
                // New pipeline order — images → cluster → videos → Arabic. Now that faces are grouped,
                // enqueue the video-indexing pass; that pass in turn triggers the Arabic OCR pass (if
                // any is pending), so the two OCR phases stay last.
                galleryService.startVideoIndexingWorkManager()
                Result.success()
            } else {
                // Another clustering run is active — retry later.
                Log.d(TAG, "Skipped: another clustering run is active, will retry")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Clustering failed", e)
            nm.cancel(NOTIFICATION_ID)
            Result.retry()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Smart Gallery")
            .setContentText("Clustering faces…")
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Face Clustering",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while Smart Gallery clusters detected faces in the background."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
