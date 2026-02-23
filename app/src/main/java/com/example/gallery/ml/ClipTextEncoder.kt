package com.example.gallery.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.io.FileOutputStream

class ClipTextEncoder(context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer = ClipTokenizer(context)

    init {
        val file = File(context.filesDir, "text_model.ort")
        if (!file.exists()) {
            context.assets.open("text_model.ort").use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
        session = env.createSession(file.absolutePath)
    }

    fun getTextFeatures(text: String): FloatArray {
        val tokens = tokenizer.tokenize(text)

        val inputTensor = OnnxTensor.createTensor(env, tokens, longArrayOf(1, 77))

        session.run(mapOf("text" to inputTensor)).use { result ->
            val output = (result[0].value as Array<FloatArray>)[0]
            return VectorUtils.normalize(output)
        }
    }
}