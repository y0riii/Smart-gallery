package com.example.kotlin_test

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import androidx.core.net.toUri

class OcrProcessor(private val database: AppDatabase) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun runOcrOnImages(
        context: Context,
        images: List<MediaEntity>
    ) = withContext(Dispatchers.IO) {

        // Filter: Only process images that don't have OCR text yet!
        // This saves massive time on subsequent runs.
        val imagesToScan = images.filter { !it.isVideo && it.ocrText.isNullOrEmpty() }

        for (item in imagesToScan) {
            try {
                val uri = item.uriString.toUri()
                val text = recognizeText(context, uri) ?: ""

                if (text.isNotEmpty()) {
                    // Update Database immediately
                    database.mediaDao().updateOcrText(item.uriString, text)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun recognizeText(context: Context, uri: Uri): String? =
        suspendCoroutine { cont ->
            try {
                val image = InputImage.fromFilePath(context, uri)
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it.text) }
                    .addOnFailureListener { cont.resume(null) } // Resume null on failure to keep going
            } catch (e: Exception) {
                cont.resume(null)
            }
        }
}