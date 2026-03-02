package com.example.gallery.db

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.lang.AutoCloseable
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class OcrProcessor() : AutoCloseable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognizeText(bitmap: Bitmap): String? =
        suspendCoroutine { cont ->
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it.text) }
                    .addOnFailureListener { cont.resume(null) } // Resume null on failure to keep going
            } catch (e: Exception) {
                cont.resume(null)
            }
        }

    override fun close() {
        recognizer.close()
    }
}