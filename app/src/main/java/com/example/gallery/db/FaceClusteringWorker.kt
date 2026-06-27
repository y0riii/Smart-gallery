package com.example.gallery.db

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.gallery.GalleryService
import com.example.gallery.R

class FaceClusteringWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "FaceClusteringWorker"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "face_clustering_channel"
    }

    override suspend fun doWork(): Result {
        try {
            setForeground(createForegroundInfo())
        } catch (e: Exception) {
            Log.e(TAG, "setForeground failed (non-fatal)", e)
        }

        return try {
            val galleryService = GalleryService(applicationContext)
            val didRun = galleryService.createClusters()
            if (didRun) {
                Result.success()
            } else {
                // Another clustering run is active — retry later.
                Log.d(TAG, "Skipped: another clustering run is active, will retry")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Clustering failed", e)
            Result.retry()
        }
    }
 
    private fun createForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = buildNotification()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Smart Gallery")
            .setContentText("Clustering faces…")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
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
                description = "Shown while Smart Gallery clusters detected faces."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
