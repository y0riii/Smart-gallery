package com.example.gallery.ml.text

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Owns the single, process-wide [ClipTextEncoder] used for semantic search.
 *
 * Building the text encoder is expensive — it loads an ONNX model and runs the one-time
 * CPU-vs-NNAPI benchmark (see [com.example.gallery.ml.OrtAcceleration]) — so it is created lazily
 * once and kept for the app's lifetime rather than per search.
 *
 * This lived in `GalleryService`'s companion object; it was extracted so the (fiddly) lazy-init
 * concurrency is isolated and testable on its own. All access is guarded by [initLock] because it
 * can be touched concurrently from the UI thread (first search), the preload coroutine, and
 * background workers.
 */
object TextEncoderProvider {
    private const val TAG = "TextEncoderProvider"

    @Volatile
    private var instance: ClipTextEncoder? = null
    private var initJob: Deferred<Unit>? = null
    private val initLock = Any()

    /**
     * Returns the shared encoder, constructing it on first call. Returns null if construction
     * fails (e.g. a missing model asset) so callers can degrade gracefully instead of crashing.
     */
    fun get(context: Context): ClipTextEncoder? {
        return synchronized(initLock) {
            if (instance == null) {
                try {
                    instance = ClipTextEncoder(context.applicationContext)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to init TextEncoder", e)
                }
            }
            instance
        }
    }

    /**
     * Starts (or reuses) a background job that builds the encoder ahead of time so the first
     * search doesn't pay the full load cost. Callers `await()` the returned job before using
     * [get]. If a previous preload finished but produced no instance (construction failed), the
     * job is recreated so the next attempt retries rather than being stuck on a dead job.
     */
    fun ensureInitialized(context: Context): Deferred<Unit> {
        return synchronized(initLock) {
            var job = initJob
            val shouldRecreate = job == null || (job.isCompleted && instance == null)
            if (shouldRecreate) {
                val appContext = context.applicationContext
                job = CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
                    try {
                        get(appContext)
                    } catch (t: Throwable) {
                        Log.e(TAG, "TextEncoder pre-load failed", t)
                    }
                }
                initJob = job
            }
            job
        }
    }
}
