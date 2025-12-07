package com.example.gallery.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ClipImageEncoder(context: Context) {

    private val IMAGE_SIZE = 224
    private val NORM_MEAN_RGB = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val NORM_STD_RGB = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val file = File(context.filesDir, "image_model.ort")
        if (!file.exists()) {
            context.assets.open("image_model.ort").use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
        session = env.createSession(file.absolutePath)
    }

    private fun preprocessImage(bitmap: Bitmap): OnnxTensor {
        // 1. Resize smallest edge to 224
        val w = bitmap.width
        val h = bitmap.height
        val scale = if (w < h) IMAGE_SIZE.toFloat() / w else IMAGE_SIZE.toFloat() / h
        val matrix = Matrix().apply { postScale(scale, scale) }
        val resizedBitmap = Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, true)

        // 2. Center crop 224x224
        val x = (resizedBitmap.width - IMAGE_SIZE) / 2
        val y = (resizedBitmap.height - IMAGE_SIZE) / 2
        val croppedBitmap = Bitmap.createBitmap(resizedBitmap, x, y, IMAGE_SIZE, IMAGE_SIZE)

        // 3. Convert to FloatBuffer and normalize (NCHW format)
        val numPixels = 3 * IMAGE_SIZE * IMAGE_SIZE

// Allocate a DIRECT ByteBuffer (4 bytes per float)
        val byteBuffer = ByteBuffer.allocateDirect(numPixels * 4)
        byteBuffer.order(ByteOrder.nativeOrder())

// Create a FloatBuffer view on top of the direct buffer
        val floatBuffer = byteBuffer.asFloatBuffer()

// (The rest of the function stays the same)

        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        croppedBitmap.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

        for (c in 0..2) { // R, G, B
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val channelValue = when (c) {
                    0 -> (pixel shr 16) and 0xFF // R
                    1 -> (pixel shr 8) and 0xFF  // G
                    else -> pixel and 0xFF         // B
                }

                // Normalize and put into buffer
                floatBuffer.put(
                    ((channelValue / 255.0f) - NORM_MEAN_RGB[c]) / NORM_STD_RGB[c]
                )
            }
        }

        floatBuffer.rewind()
        return OnnxTensor.createTensor(env, floatBuffer, longArrayOf(1, 3, 224, 224))
    }

    fun getImageFeatures(bitmap: Bitmap): FloatArray {
        val input = preprocessImage(bitmap)

        val result = session.run(mapOf("image" to input))
        val output = (result[0].value as Array<FloatArray>)[0]

        return VectorUtils.normalize(output)
    }
}
