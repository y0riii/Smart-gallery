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
import kotlin.math.max
import kotlin.math.min

class ClipImageEncoder(private val context: Context) : AutoCloseable {

//    private val IMAGE_SIZE = 224
//    private val NORM_MEAN_RGB = doubleArrayOf(0.48145466, 0.4578275, 0.40821073)
//    private val NORM_STD_RGB = doubleArrayOf(0.26862954, 0.26130258, 0.27577711)

    private val IMAGE_SIZE = 256
    private val NORM_MEAN_RGB = doubleArrayOf(0.0, 0.0, 0.0)
    private val NORM_STD_RGB = doubleArrayOf(1.0, 1.0, 1.0)

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

        val safeBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)

        // 1. Resize smallest edge to SIZE
        val w = safeBitmap.width
        val h = safeBitmap.height
        val scale = IMAGE_SIZE.toFloat() / min(w, h)
        val newW = max(IMAGE_SIZE, (w * scale).toInt())
        val newH = max(IMAGE_SIZE, (h * scale).toInt())
        val resized = safeBitmap.scale(newW, newH)

        // 2. Center crop
        val x = (resized.width - IMAGE_SIZE) / 2
        val y = (resized.height - IMAGE_SIZE) / 2
        val cropped = Bitmap.createBitmap(resized, x, y, IMAGE_SIZE, IMAGE_SIZE)

        cropped.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

        floatBuffer.rewind()

        for (i in pixels.indices) {
            val p = pixels[i]

            val r = (((p shr 16) and 0xFF) / 255f - NORM_MEAN_RGB[0]) / NORM_STD_RGB[0]
            val g = (((p shr 8) and 0xFF) / 255f - NORM_MEAN_RGB[1]) / NORM_STD_RGB[1]
            val b = ((p and 0xFF) / 255f - NORM_MEAN_RGB[2]) / NORM_STD_RGB[2]

            floatBuffer.put(i, r.toFloat())           // R channel block
            floatBuffer.put(i + hw, g.toFloat())      // G channel block
            floatBuffer.put(i + 2 * hw, b.toFloat())  // B channel block
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
                val output = (result[0].value as Array<FloatArray>)[0]
                return VectorUtils.normalize(output)
            }
        }
    }

    override fun close() {
        session.close()
        env.close()
    }
}
