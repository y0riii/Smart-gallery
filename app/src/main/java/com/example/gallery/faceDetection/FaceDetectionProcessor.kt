package com.example.gallery.faceDetection

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.lang.AutoCloseable
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class FaceDetectionProcessor : AutoCloseable {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build()
    )

    suspend fun detectFaces(bitmap: Bitmap): List<Face> =
        suspendCoroutine { cont ->
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                detector.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(emptyList()) }
            } catch (e: Exception) {
                cont.resume(emptyList())
            }
        }

    fun cropFace(bitmap: Bitmap, rect: Rect): Bitmap {
        val scale = 1.3f
        val centerX = rect.centerX()
        val centerY = rect.centerY()

        val newWidth = (rect.width() * scale).toInt()
        val newHeight = (rect.height() * scale).toInt()

        val left = (centerX - newWidth / 2).coerceAtLeast(0)
        val top = (centerY - newHeight / 2).coerceAtLeast(0)
        val right = (centerX + newWidth / 2).coerceAtMost(bitmap.width)
        val bottom = (centerY + newHeight / 2).coerceAtMost(bitmap.height)

        return Bitmap.createBitmap(
            bitmap,
            left,
            top,
            right - left,
            bottom - top
        )
    }

    override fun close() {
        detector.close()
    }
}