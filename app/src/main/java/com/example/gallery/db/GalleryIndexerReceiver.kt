package com.example.gallery.db

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import com.example.gallery.GalleryService
import com.example.gallery.R

class GalleryIndexerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("GalleryIndexerReceiver", "Received action: $action")

        when (action) {
            "com.example.gallery.ACTION_PAUSE_INDEXING" -> {
                // 1. Set the flag FIRST so the worker loop sees it immediately
                GalleryIndexerWorker.isPaused = true
                // 2. Instantly swap the notification in-place (same ID → no flicker)
                GalleryIndexerWorker.showPausedNotification(context)
            }

            "com.example.gallery.ACTION_START_INDEXING" -> {
                // 1. Instantly swap notification to active state with pause button
                showResumedNotification(context)
                // 2. Clear the flag so the worker loop resumes on next poll (~1s)
                GalleryIndexerWorker.isPaused = false
                // 3. Ensure the right worker is running (no-op if already alive & waiting; restarts
                //    it if it had been killed). Route to the Arabic pass when that's what was paused.
                val service = GalleryService.getInstance(context)
                if (GalleryIndexerWorker.isArabicPass) {
                    service.startArabicOcrWorkManager()
                } else {
                    service.startIndexingWorkManager(force = false)
                }
            }
        }
    }

    /**
     * Immediately replace the paused notification with an active-looking one
     * so the button swaps to Pause instantly, before the worker's own progress
     * update fires.
     */
    private fun showResumedNotification(context: Context) {
        GalleryIndexerWorker.run {
            // Reuse ensureChannel from companion
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val pauseIntent = Intent(context, GalleryIndexerReceiver::class.java).apply {
            action = "com.example.gallery.ACTION_PAUSE_INDEXING"
        }
        val pausePendingIntent = PendingIntent.getBroadcast(
            context, 0, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIcon = Icon.createWithResource(context, R.drawable.ic_notif_pause)
        val pauseAction = Notification.Action.Builder(pauseIcon, "", pausePendingIntent).build()

        val notification = Notification.Builder(context, GalleryIndexerWorker.CHANNEL_ID)
            .setContentTitle("Smart Gallery")
            .setContentText(
                when (GalleryIndexerWorker.currentPhase()) {
                    GalleryIndexerWorker.IndexingPhase.ARABIC_VIDEOS -> "Resuming Arabic text scan (videos)…"
                    GalleryIndexerWorker.IndexingPhase.ARABIC_IMAGES -> "Resuming Arabic text scan (images)…"
                    else -> "Resuming image indexing…"
                }
            )
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setOngoing(true)
            .addAction(pauseAction)
            .setStyle(
                Notification.MediaStyle()
                    .setShowActionsInCompactView(0)
            )
            .build()

        nm.notify(GalleryIndexerWorker.NOTIFICATION_ID, notification)
    }
}
