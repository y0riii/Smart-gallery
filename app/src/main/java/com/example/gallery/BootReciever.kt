package com.example.gallery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("BootReceiver", "onReceive called with action: ${intent.action}")
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                try {
                    GalleryService(context).startIndexingAfterBoot()
                    Log.d("BootReceiver", "Boot indexing enqueued successfully")
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to enqueue boot indexing", e)
                }
            }
            else -> {
                Log.d("BootReceiver", "Ignoring action: ${intent.action}")
            }
        }
    }
}
