package com.example.gallery.db

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.gallery.GalleryService

class GalleryPeriodicTriggerWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        // Skip indexing when the battery is low — but always return success so WorkManager doesn't
        // back off. The next 6-hour cycle will try again. This check lives HERE instead of as a
        // WorkManager constraint because constraints defer the entire trigger (compounding with Doze
        // and app standby buckets), whereas this lets the trigger fire on time and just no-ops.
        if (isBatteryLow()) return Result.success()

        // Route through the service so the priority flag (indexingRequested) is raised too — this
        // makes any in-progress Arabic OCR pass yield so the 6-hour indexing runs first, exactly
        // like an app-open trigger. KEEP semantics (force = false) so it won't stomp a running pass.
        GalleryService.getInstance(applicationContext).startIndexingWorkManager(force = false)
        return Result.success()
    }

    /** True when the device battery is below the system's "low" threshold (~15%). */
    private fun isBatteryLow(): Boolean {
        val batteryStatus = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return false
        val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return (level * 100 / scale) < 15
    }
}
