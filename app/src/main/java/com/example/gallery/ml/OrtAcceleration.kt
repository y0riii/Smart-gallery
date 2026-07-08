package com.example.gallery.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log

/**
 * Picks NNAPI vs plain-CPU execution per model by timing a few dummy inferences on both
 * and keeping whichever is faster. NNAPI's real-world performance depends entirely on the
 * device's vendor driver — it can be a large win or a net loss — so this benchmarks on first
 * use instead of assuming either is best, then caches the decision for future launches.
 */
object OrtAcceleration {
    private const val TAG = "OrtAcceleration"
    private const val PREFS_NAME = "ort_acceleration_prefs"
    private const val WARMUP_RUNS = 2
    private const val TIMED_RUNS = 4

    private fun key(modelKey: String) = "use_nnapi_$modelKey"

    /** Returns the cached decision for [modelKey], or null if it has never been benchmarked. */
    fun cachedDecision(context: Context, modelKey: String): Boolean? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.contains(key(modelKey))) prefs.getBoolean(key(modelKey), false) else null
    }

    private fun saveDecision(context: Context, modelKey: String, useNnapi: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(key(modelKey), useNnapi)
            .apply()
    }

    /**
     * Benchmarks [cpuSession] against [nnapiSession] (skips the benchmark and keeps CPU if
     * [nnapiSession] is null, e.g. NNAPI setup itself failed), keeps whichever is faster,
     * closes the loser, and caches the result under [modelKey] for future launches.
     */
    fun pickFasterSession(
        context: Context,
        modelKey: String,
        cpuSession: OrtSession,
        nnapiSession: OrtSession?,
        inputName: String,
        makeInput: () -> OnnxTensor
    ): OrtSession {
        if (nnapiSession == null) {
            saveDecision(context, modelKey, false)
            return cpuSession
        }

        // NNAPI can build a session successfully and still throw the first time it actually
        // runs (e.g. an op the vendor driver claims to support but doesn't). Treat that the
        // same as a failed NNAPI session: cache "false" so we don't retry this crash forever.
        val nnapiNanos = try {
            time(nnapiSession, inputName, makeInput)
        } catch (e: Throwable) {
            Log.e(TAG, "$modelKey: NNAPI failed during benchmark inference, falling back to CPU", e)
            nnapiSession.close()
            saveDecision(context, modelKey, false)
            return cpuSession
        }
        val cpuNanos = time(cpuSession, inputName, makeInput)
        val useNnapi = nnapiNanos < cpuNanos

        Log.d(
            TAG,
            "$modelKey: cpu=${cpuNanos / 1_000_000}ms nnapi=${nnapiNanos / 1_000_000}ms " +
                "-> using ${if (useNnapi) "NNAPI" else "CPU"}"
        )
        saveDecision(context, modelKey, useNnapi)

        return if (useNnapi) {
            cpuSession.close()
            nnapiSession
        } else {
            nnapiSession.close()
            cpuSession
        }
    }

    private fun time(session: OrtSession, inputName: String, makeInput: () -> OnnxTensor): Long {
        // Warm up first so one-time lazy compilation/init cost doesn't skew the measurement.
        repeat(WARMUP_RUNS) {
            makeInput().use { input -> session.run(mapOf(inputName to input)).use { } }
        }
        val start = System.nanoTime()
        repeat(TIMED_RUNS) {
            makeInput().use { input -> session.run(mapOf(inputName to input)).use { } }
        }
        return System.nanoTime() - start
    }
}
