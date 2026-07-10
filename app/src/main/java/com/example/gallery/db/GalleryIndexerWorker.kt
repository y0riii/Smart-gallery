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
import kotlinx.coroutines.sync.withLock

class GalleryIndexerWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "gallery_indexing_channel"

        // Input-data flag: when true this worker re-runs OCR over the whole library instead of the
        // normal device-vs-DB sync (used when the user enables Arabic OCR).
        const val KEY_RE_OCR_ONLY = "reOcrOnly"

        // Minimum gap between progress/notification updates during indexing (see doWork).
        private const val PROGRESS_UPDATE_MS = 500L

        @Volatile
        var isPaused = false

        // True while this worker is running the Arabic OCR re-scan (reOcrOnly) rather than normal
        // indexing. Drives the wording of every notification (progress / paused / resumed). The two
        // passes never run at once, so a single process-wide flag is enough.
        @Volatile
        var isArabicPass = false

        // True while normal indexing is in its VIDEO phase (after photos) — makes the notification
        // say "videos" instead of "photos". Set/cleared by GalleryService.processAndIndexVideos.
        @Volatile
        var isVideoPass = false

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
                .setContentText(
                    if (isArabicPass) "Arabic text scan is paused" else "Image indexing is paused"
                )
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
        val reOcrOnly = inputData.getBoolean(KEY_RE_OCR_ONLY, false)
        // Set before the first notification so even "Preparing…" uses the right wording.
        isArabicPass = reOcrOnly

        try {
            setForeground(createForegroundInfo(0, 0))
        } catch (e: Exception) {
            Log.e("GalleryIndexerWorker", "setForeground failed (non-fatal)", e)
        }

        val notificationManager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        return try {
            val service = GalleryService.getInstance(applicationContext)
            // Indexing shares the ML lock with clustering: if a clustering pass is already running,
            // this simply waits for it to finish, then runs.
            GalleryService.mlExecutionLock.withLock {
                GalleryService.isIndexingRunning = true
                // Throttle progress/notification updates: firing setProgress() (a WorkManager DB
                // write) and notificationManager.notify() (a cross-process call) on every single
                // image is a real cost on large libraries. Update at most every PROGRESS_UPDATE_MS,
                // but always emit the very first and very last item so the bar starts and completes.
                var lastUpdateMs = 0L
                val onProgress: suspend (Int, Int) -> Unit = { processed, total ->
                    // ── never overwrite the "paused" notification ──
                    if (!isPaused) {
                        val now = System.currentTimeMillis()
                        val isBoundary = processed == 1 || processed >= total
                        if (isBoundary || now - lastUpdateMs >= PROGRESS_UPDATE_MS) {
                            lastUpdateMs = now
                            withContext(Dispatchers.Main) {
                                setProgress(workDataOf("processed" to processed, "total" to total))
                            }
                            notificationManager.notify(
                                NOTIFICATION_ID,
                                createForegroundInfo(processed, total).notification
                            )
                        }
                    }
                }
                try {
                    if (reOcrOnly) {
                        service.runArabicOcrPass(onProgress)
                    } else {
                        service.indexImagesBackground(onProgress)
                        // Indexing has run; the priority request is satisfied, so a subsequent Arabic
                        // pass (enqueued after clustering) won't immediately yield.
                        GalleryService.indexingRequested = false
                    }
                } finally {
                    GalleryService.isIndexingRunning = false
                }
            }

            // A normal indexing pass then triggers face clustering; an OCR-only re-scan doesn't
            // change faces, so it skips that.
            if (!reOcrOnly) {
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
            }

            Result.success()
        } catch (e: Throwable) {
            Log.e("GalleryIndexerWorker", "Indexing failed, will retry", e)
            Result.retry()
        } finally {
            // Clear the wording flags once the pass is fully done (a mid-pause worker is still inside
            // the try above, so this only runs when work actually completes/cancels).
            isArabicPass = false
            isVideoPass = false
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

        val contentText = when {
            total == 0 && isArabicPass -> "Preparing Arabic text scan…"
            total == 0 && isVideoPass -> "Preparing video indexing…"
            total == 0 -> "Preparing Image indexing…"
            // Arabic pass runs images first, then videos; isVideoPass marks the video phase so the
            // user sees Arabic move on to videos instead of it being silent.
            isArabicPass && isVideoPass -> "Scanned $processed of $total videos for Arabic text"
            isArabicPass -> "Scanned $processed of $total images for Arabic text"
            isVideoPass -> "Indexed $processed of $total videos"
            else -> "Indexed $processed of $total images"
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
