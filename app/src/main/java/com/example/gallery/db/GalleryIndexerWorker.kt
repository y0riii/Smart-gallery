package com.example.gallery.db

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.PowerManager
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

    /** Which indexing pass is currently active. Single source of truth shared by the foreground
     *  notification and the in-app progress bar, so their wording can never disagree on the phase. */
    enum class IndexingPhase { IMAGES, VIDEOS, ARABIC_IMAGES, ARABIC_VIDEOS }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "gallery_indexing_channel"

        // Input-data flag: when true this worker re-runs OCR over the whole library instead of the
        // normal device-vs-DB sync (used when the user enables Arabic OCR).
        const val KEY_RE_OCR_ONLY = "reOcrOnly"

        // Input-data flag: when true this worker runs ONLY the video-indexing pass. Video indexing was
        // moved out of the image pass so the pipeline order is images → cluster → videos → Arabic; the
        // clustering worker enqueues this once faces are grouped.
        const val KEY_VIDEO_ONLY = "videoOnly"

        // Minimum gap between progress/notification updates during indexing (see doWork).
        private const val PROGRESS_UPDATE_MS = 500L

        @Volatile
        var isPaused = false

        // True while this worker is running the Arabic OCR re-scan (reOcrOnly) rather than normal
        // indexing. Drives the wording of every notification (progress / paused / resumed). The two
        // passes never run at once, so a single process-wide flag is enough.
        @Volatile
        var isArabicPass = false

        // True while normal indexing is in its VIDEO phase (after images) — makes the notification
        // say "videos" instead of "images". Set/cleared by GalleryService.processAndIndexVideos.
        @Volatile
        var isVideoPass = false

        /**
         * Maps the two phase flags to a single [IndexingPhase]. Every surface (foreground
         * notification, paused notification, in-app progress bar) derives its wording from THIS one
         * function, so they can never disagree about which pass is running. Takes the flags as
         * parameters so callers can pass explicit values (e.g. the initial "Preparing…" notification,
         * before the shared flags are set inside the lock).
         */
        fun phaseOf(arabic: Boolean, video: Boolean): IndexingPhase = when {
            arabic && video -> IndexingPhase.ARABIC_VIDEOS
            arabic -> IndexingPhase.ARABIC_IMAGES
            video -> IndexingPhase.VIDEOS
            else -> IndexingPhase.IMAGES
        }

        /** [phaseOf] applied to the live flags — used by the paused notification and the in-app bar. */
        fun currentPhase(): IndexingPhase = phaseOf(isArabicPass, isVideoPass)

        /**
         * Immediately post the "paused" notification, reusing the same
         * NOTIFICATION_ID so it replaces the active one in-place.
         */
        fun showPausedNotification(context: Context) {
            ensureChannel(context)

            val closeIntent = Intent(context, GalleryIndexerReceiver::class.java).apply {
                action = "com.example.gallery.ACTION_STOP_INDEXING"
            }
            val closePendingIntent = PendingIntent.getBroadcast(
                context, 2, closeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val closeIcon = Icon.createWithResource(context, R.drawable.ic_notif_close)
            val closeAction = Notification.Action.Builder(closeIcon, "", closePendingIntent).build()

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
                    when (currentPhase()) {
                        IndexingPhase.ARABIC_VIDEOS -> "Arabic text scan (videos) is paused"
                        IndexingPhase.ARABIC_IMAGES -> "Arabic text scan (images) is paused"
                        IndexingPhase.VIDEOS -> "Video indexing is paused"
                        IndexingPhase.IMAGES -> "Image indexing is paused"
                    }
                )
                .setSmallIcon(R.drawable.ic_notification_logo)
                .setOngoing(true)
                .addAction(playAction)
                .addAction(closeAction)
                .setStyle(
                    Notification.MediaStyle()
                        .setShowActionsInCompactView(0, 1)
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
                    description = "Shown while Smart Gallery indexes your images in the background."
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    override suspend fun doWork(): Result {
        val reOcrOnly = inputData.getBoolean(KEY_RE_OCR_ONLY, false)
        val videoOnly = inputData.getBoolean(KEY_VIDEO_ONLY, false)

        // A stale Arabic pass may have been enqueued (and persisted by WorkManager across restarts)
        // before the user turned Arabic OCR off. Bail BEFORE showing any foreground notification so it
        // never flashes "Preparing Arabic text scan…" once the feature is disabled.
        if (reOcrOnly && !GalleryService.getInstance(applicationContext).arabicOcrEnabled) {
            return Result.success()
        }

        // The initial "Preparing…" notification uses this worker's own mode (not the shared flags,
        // which are set only inside the lock below).
        try {
            setForeground(createForegroundInfo(0, 0, arabic = reOcrOnly, video = videoOnly))
        } catch (e: Exception) {
            Log.e("GalleryIndexerWorker", "setForeground failed (non-fatal)", e)
        }

        val notificationManager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Hold a partial wake lock for the whole pass so the CPU keeps running when the screen is off
        // — the foreground service alone doesn't prevent SoC suspend, which is what stalls background
        // indexing on low-end devices. Released in finally; the OS reclaims it if the process is killed.
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "SmartGallery:Indexing"
        )
        // Safety-net timeout (2 h): if the worker somehow hangs beyond all per-item safeguards, the
        // OS auto-releases the lock so the CPU isn't held awake indefinitely.
        wakeLock.acquire(2 * 60 * 60 * 1000L)

        return try {
            val service = GalleryService.getInstance(applicationContext)
            // Indexing shares the ML lock with clustering: if a clustering pass is already running,
            // this simply waits for it to finish, then runs.
            GalleryService.mlExecutionLock.withLock {
                GalleryService.isIndexingRunning = true
                // Set the notification-wording flags ONLY while holding the lock, so another worker
                // waiting for the lock can't stomp them (which made the video pass show "images").
                // For the same reason they are NOT reset in finally — the next worker to acquire the
                // lock overwrites them, and between runs no progress notification reads them.
                isArabicPass = reOcrOnly
                isVideoPass = videoOnly
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
                    when {
                        // Arabic OCR pass (images then videos) — the final pipeline step.
                        reOcrOnly -> service.runArabicOcrPass(onProgress)
                        // Video indexing — runs AFTER face clustering (enqueued by that worker).
                        videoOnly -> service.indexVideosBackground(onProgress)
                        // Image indexing — the first step.
                        else -> {
                            service.indexImagesBackground(onProgress)
                            // Images have run; the priority request is satisfied, so a downstream
                            // Arabic pass won't immediately yield.
                            GalleryService.indexingRequested = false
                        }
                    }
                } finally {
                    GalleryService.isIndexingRunning = false
                }
            }

            // Pipeline chaining — order is images → cluster → videos → Arabic:
            //  • after IMAGE indexing → enqueue face clustering,
            //  • after VIDEO indexing → enqueue the Arabic pass (only if there's pending OCR),
            //  • after the Arabic pass → end of chain.
            when {
                !reOcrOnly && !videoOnly -> {
                    // Skip the clustering hand-off if we exited because of a pause (the image pass
                    // returns on pause) — otherwise clustering would run while the user has paused.
                    // On resume the image worker re-runs, finishes, and enqueues clustering then, so
                    // the images → cluster → videos → Arabic order is preserved.
                    if (!isPaused) {
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
                }
                videoOnly -> {
                    // Skip the Arabic hand-off if we exited because of a pause (the video pass returns
                    // on pause) — otherwise Arabic could run on partially-indexed videos. On resume the
                    // whole images → cluster → videos → Arabic chain re-runs, keeping the order intact.
                    if (!isPaused && service.hasPendingArabicOcr()) service.startArabicOcrWorkManager()
                }
            }

            Result.success()
        } catch (e: Throwable) {
            Log.e("GalleryIndexerWorker", "Indexing failed, will retry", e)
            Result.retry()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
        // NOTE: the wording flags are intentionally NOT reset here — resetting them would clobber the
        // flags of a different worker that has already picked up the lock. The next worker sets them.
    }

    private fun createForegroundInfo(
        processed: Int,
        total: Int,
        // Default to the shared flags (correct for progress updates, which fire only from the worker
        // holding the lock). The initial "Preparing…" call passes explicit values so it's right even
        // before the flags are set inside the lock.
        arabic: Boolean = isArabicPass,
        video: Boolean = isVideoPass
    ): ForegroundInfo {
        ensureChannel(applicationContext)

        val closeIntent = Intent(applicationContext, GalleryIndexerReceiver::class.java).apply {
            action = "com.example.gallery.ACTION_STOP_INDEXING"
        }
        val closePendingIntent = PendingIntent.getBroadcast(
            applicationContext, 2, closeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val closeIcon = Icon.createWithResource(applicationContext, R.drawable.ic_notif_close)
        val closeAction = Notification.Action.Builder(closeIcon, "", closePendingIntent).build()

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
            total == 0 && arabic -> "Preparing Arabic text scan…"
            total == 0 && video -> "Preparing video indexing…"
            total == 0 -> "Preparing image indexing…"
            // Progress wording is derived from the SAME phaseOf() the in-app bar uses, so the two
            // always agree on the pass. Arabic runs images first, then videos.
            else -> when (phaseOf(arabic, video)) {
                IndexingPhase.ARABIC_VIDEOS -> "Scanned $processed of $total videos for Arabic text"
                IndexingPhase.ARABIC_IMAGES -> "Scanned $processed of $total images for Arabic text"
                IndexingPhase.VIDEOS -> "Indexed $processed of $total videos"
                IndexingPhase.IMAGES -> "Indexed $processed of $total images"
            }
        }

        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Smart Gallery")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setOngoing(true)
            .addAction(pauseAction)
            .addAction(closeAction)
            .setStyle(
                Notification.MediaStyle()
                    .setShowActionsInCompactView(0, 1)
            )
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }
}
