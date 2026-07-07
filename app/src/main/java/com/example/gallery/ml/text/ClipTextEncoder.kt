package com.example.gallery.ml.text

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.example.gallery.utils.VectorUtils
import java.io.File
import java.io.FileOutputStream

class ClipTextEncoder(context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer = ClipTokenizer(context)

    init {
        val modelBytes = context.assets.open("text_model.ort").readBytes()
        var tempSession: OrtSession? = null
        try {
            val options = OrtSession.SessionOptions().apply {
                addNnapi()
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            options.use {
                tempSession = env.createSession(modelBytes, it)
            }
        } catch (e: Throwable) {
            android.util.Log.e("ClipTextEncoder", "Failed to initialize with NNAPI, falling back to CPU", e)
            tempSession = env.createSession(modelBytes)
        }
        session = tempSession!!
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