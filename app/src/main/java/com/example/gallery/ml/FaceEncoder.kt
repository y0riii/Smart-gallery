package com.example.gallery.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import java.io.File
import java.io.FileOutputStream
import java.lang.AutoCloseable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.copyTo
import kotlin.use

class FaceEncoder(context: Context) : AutoCloseable {

    private val IMAGE_SIZE = 112

    private val hw = IMAGE_SIZE * IMAGE_SIZE

    // Allocate a DIRECT ByteBuffer (4 bytes per float)
    private val byteBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(hw * 3 * 4).order(ByteOrder.nativeOrder())

    // Create a FloatBuffer view on top of the direct buffer
    private val floatBuffer = byteBuffer.asFloatBuffer()

    private val pixels = IntArray(hw)

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val file = File(context.filesDir, "MobileFaceNet.ort")
        if (!file.exists()) {
            context.assets.open("MobileFaceNet.ort").use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
        session = env.createSession(file.absolutePath)
    }

    private fun preprocessImage(bitmap: Bitmap): OnnxTensor {

        val safeBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)

        val resized = safeBitmap.scale(IMAGE_SIZE, IMAGE_SIZE)

        resized.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

        floatBuffer.rewind()

        for (i in pixels.indices) {
            val p = pixels[i]

            val r = ((p shr 16) and 0xFF) / 127.5f - 1f
            val g = ((p shr 8) and 0xFF) / 127.5f - 1f
            val b = (p and 0xFF) / 127.5f - 1f

            floatBuffer.put(i, r)           // R channel block
            floatBuffer.put(i + hw, g)      // G channel block
            floatBuffer.put(i + 2 * hw, b)  // B channel block
        }

        floatBuffer.rewind()
        return OnnxTensor.createTensor(
            env,
            floatBuffer,
            longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())
        )
    }

    fun getImageFeatures(bitmap: Bitmap): FloatArray {
        preprocessImage(bitmap).use { input ->
            session.run(mapOf("image" to input)).use { result ->
                return (result[0].value as Array<FloatArray>)[0]
            }
        }
    }

    override fun close() {
        session.close()
        env.close()
    }
}