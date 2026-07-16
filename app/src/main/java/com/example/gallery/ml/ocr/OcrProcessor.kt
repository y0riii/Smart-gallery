package com.example.gallery.ml.ocr

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
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(image)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            if (cont.isActive) cont.resume(task.result.text)
                        } else {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }

    override fun close() {
        recognizer.close()
    }
}