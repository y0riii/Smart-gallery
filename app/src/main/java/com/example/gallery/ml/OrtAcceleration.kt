package com.example.gallery.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlin.math.sqrt

/**
 * Picks NNAPI vs plain-CPU execution per model. NNAPI must first pass a CORRECTNESS check (its output
 * has to match CPU), because some budget vendor drivers run an op fast but WRONG — which silently
 * corrupts every embedding and, for CLIP, makes semantic search rank the whole gallery the same.
 * Only if NNAPI is correct does speed decide. The per-model decision is cached for future launches.
 */
object OrtAcceleration {
    private const val TAG = "OrtAcceleration"
    private const val PREFS_NAME = "ort_acceleration_prefs"
    private const val WARMUP_RUNS = 2
    private const val TIMED_RUNS = 4

    // Minimum cosine similarity between NNAPI's and CPU's raw output for NNAPI to be trusted. Correct
    // backends agree to ~1.0 (tiny float differences); a broken one diverges far below this.
    private const val CORRECTNESS_MIN_COSINE = 0.99

    // "v2": devices that cached a WRONG "NNAPI" decision before the correctness gate existed will
    // re-benchmark (their old key is ignored) and get corrected.
    private fun key(modelKey: String) = "use_nnapi_v2_$modelKey"

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

        // CORRECTNESS GATE (before speed): NNAPI must produce essentially the same output as CPU.
        // On some budget SoCs the NNAPI driver runs the model fast but numerically WRONG, silently
        // corrupting every embedding (semantic search then treats the whole gallery as equally
        // relevant). If the outputs diverge — or NNAPI throws / returns garbage — force CPU.
        val nnapiCorrect = try {
            outputsAgree(cpuSession, nnapiSession, inputName, makeInput)
        } catch (e: Throwable) {
            Log.e(TAG, "$modelKey: NNAPI correctness check threw, using CPU", e)
            false
        }
        if (!nnapiCorrect) {
            Log.w(TAG, "$modelKey: NNAPI output diverges from CPU — forcing CPU for correctness")
            nnapiSession.close()
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

    /**
     * True if NNAPI's output matches CPU's for the same input — cosine similarity of the raw output
     * vectors ≥ [CORRECTNESS_MIN_COSINE]. Cosine (not exact equality) tolerates the tiny float
     * differences between backends while still catching gross wrongness. An all-zero output (zero
     * magnitude) is itself treated as broken.
     */
    private fun outputsAgree(
        cpuSession: OrtSession,
        nnapiSession: OrtSession,
        inputName: String,
        makeInput: () -> OnnxTensor
    ): Boolean {
        val cpuOut = firstOutput(cpuSession, inputName, makeInput)
        val nnapiOut = firstOutput(nnapiSession, inputName, makeInput)
        if (cpuOut.isEmpty() || cpuOut.size != nnapiOut.size) return false

        var dot = 0.0
        var normCpu = 0.0
        var normNnapi = 0.0
        for (i in cpuOut.indices) {
            dot += cpuOut[i].toDouble() * nnapiOut[i]
            normCpu += cpuOut[i].toDouble() * cpuOut[i]
            normNnapi += nnapiOut[i].toDouble() * nnapiOut[i]
        }
        if (normCpu <= 0.0 || normNnapi <= 0.0) return false // one produced all-zeros → broken
        val cosine = dot / (sqrt(normCpu) * sqrt(normNnapi))
        Log.d(TAG, "NNAPI vs CPU output cosine = %.4f".format(cosine))
        return cosine >= CORRECTNESS_MIN_COSINE
    }

    /** Runs one inference and returns the first output row (the [1, D] embedding). */
    private fun firstOutput(session: OrtSession, inputName: String, makeInput: () -> OnnxTensor): FloatArray {
        makeInput().use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                return (result[0].value as Array<FloatArray>)[0]
            }
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
