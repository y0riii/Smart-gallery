package com.example.gallery.ml.text

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.example.gallery.ml.OrtAcceleration
import com.example.gallery.utils.VectorUtils
import java.io.File
import java.io.FileOutputStream

class ClipTextEncoder(context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer = ClipTokenizer(context)

    init {
        val modelBytes = context.assets.open("text_model.ort").readBytes()
        val modelKey = "clip_text"

        fun buildCpuSession(): OrtSession {
            return try {
                val options = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                options.use { env.createSession(modelBytes, it) }
            } catch (e: Throwable) {
                android.util.Log.e("ClipTextEncoder", "Failed to initialize session, falling back to defaults", e)
                env.createSession(modelBytes)
            }
        }

        fun buildNnapiSession(): OrtSession? = try {
            val options = OrtSession.SessionOptions().apply {
                addNnapi()
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            options.use { env.createSession(modelBytes, it) }
        } catch (e: Throwable) {
            android.util.Log.e("ClipTextEncoder", "NNAPI session unavailable, will use CPU", e)
            null
        }

        fun dummyInput(): OnnxTensor {
            val tokens = tokenizer.tokenize("a photo", truncate = true)
            return OnnxTensor.createTensor(env, tokens, longArrayOf(1, 77))
        }

        session = when (OrtAcceleration.cachedDecision(context, modelKey)) {
            true -> buildNnapiSession() ?: buildCpuSession()
            false -> buildCpuSession()
            null -> OrtAcceleration.pickFasterSession(
                context, modelKey,
                cpuSession = buildCpuSession(),
                nnapiSession = buildNnapiSession(),
                inputName = "text",
                makeInput = ::dummyInput
            )
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