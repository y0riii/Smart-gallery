package com.example.gallery.ml.text

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.example.gallery.ml.ModelAssets
import com.example.gallery.utils.VectorUtils

class ClipTextEncoder(context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer = ClipTokenizer(context)

    init {
        // Memory-map the model straight from the APK (no copy, off-heap) — readBytes() OOMs the Java
        // heap with this ~61 MB model on low-RAM devices, which left this encoder null and broke search.
        val modelBuffer = ModelAssets.mappedModel(context, "text_model.ort")

        // CLIP runs on CPU ONLY — never NNAPI (see ClipImageEncoder for why). Both CLIP encoders must
        // use the SAME backend so text and image embeddings share one space; CPU for both guarantees
        // correct, comparable vectors on every device.
        session = try {
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            options.use { env.createSession(modelBuffer.duplicate(), it) }
        } catch (e: Throwable) {
            android.util.Log.e("ClipTextEncoder", "Failed to initialize session, falling back to defaults", e)
            OrtSession.SessionOptions().use { env.createSession(modelBuffer.duplicate(), it) }
        }
    }

    fun getTextFeatures(text: String): FloatArray {
        val tokens = tokenizer.tokenize(text, truncate = true)

        val inputTensor = OnnxTensor.createTensor(env, tokens, longArrayOf(1, 77))

        session.run(mapOf("text" to inputTensor)).use { result ->
            val output = (result[0].value as Array<FloatArray>)[0]
            return VectorUtils.normalize(output)
        }
    }
}