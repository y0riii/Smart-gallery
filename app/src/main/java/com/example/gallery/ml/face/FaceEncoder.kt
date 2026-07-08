package com.example.gallery.ml.face

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.core.graphics.scale
import com.example.gallery.ml.OrtAcceleration
import java.lang.AutoCloseable
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        val modelBytes = context.assets.open("MobileFaceNet.ort").readBytes()
        val modelKey = "face_encoder"

        fun buildCpuSession(): OrtSession {
            return try {
                val options = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                options.use { env.createSession(modelBytes, it) }
            } catch (e: Throwable) {
                android.util.Log.e("FaceEncoder", "Failed to initialize session, falling back to defaults", e)
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
            android.util.Log.e("FaceEncoder", "NNAPI session unavailable, will use CPU", e)
            null
        }

        fun dummyInput(): OnnxTensor {
            floatBuffer.rewind()
            return OnnxTensor.createTensor(
                env, floatBuffer, longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())
            )
        }

        session = when (OrtAcceleration.cachedDecision(context, modelKey)) {
            true -> buildNnapiSession() ?: buildCpuSession()
            false -> buildCpuSession()
            null -> OrtAcceleration.pickFasterSession(
                context, modelKey,
                cpuSession = buildCpuSession(),
                nnapiSession = buildNnapiSession(),
                inputName = "input0",
                makeInput = ::dummyInput
            )
        }
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

    fun getFaceFeatures(bitmap: Bitmap): FloatArray {
        val feat1 = runInference(bitmap)

        // Create a horizontally flipped version of the bitmap
        val matrix = Matrix().apply { postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f) }
        val flippedBitmap =
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

        val feat2 = runInference(flippedBitmap)

        // Element-wise average
        for (i in feat1.indices) {
            feat1[i] = (feat1[i] + feat2[i]) / 2f
        }

        return feat1
    }

    private fun runInference(bitmap: Bitmap): FloatArray {
        preprocessImage(bitmap).use { input ->
            session.run(mapOf("input0" to input)).use { result ->
                return (result[0].value as Array<FloatArray>)[0]
            }
        }
    }

    override fun close() {
        // NOTE: do NOT close `env` — OrtEnvironment.getEnvironment() is a process-wide
        // singleton shared with the other encoders. Closing it here would invalidate
        // sessions still in use elsewhere and break the next indexing batch.
        session.close()
    }
}