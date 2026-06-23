package com.example.gallery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Start the background indexer as soon as the device boots
            val galleryService = GalleryService(context)
            galleryService.startIndexingWorkManager()
        }
    }
}
