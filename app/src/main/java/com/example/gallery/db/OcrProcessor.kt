package com.example.gallery.db

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class OcrProcessor(private val database: AppDatabase, private val context: Context) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun runOcrOnImages(
        images: List<MediaEntity>
    ) = withContext(Dispatchers.IO) {

        // Filter: Only process images that don't have OCR text yet!
        // This saves massive time on subsequent runs.
        val imagesToScan = images.filter { !it.isVideo && it.ocrText.isNullOrEmpty() }

        for (item in imagesToScan) {
            try {
                val text = recognizeText(item.mediaId) ?: ""

                if (text.isNotEmpty()) {
                    // Update Database immediately
                    database.mediaDao().updateOcrText(item.mediaId, text)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun recognizeText(id: Long): String? =
        suspendCoroutine { cont ->
            try {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val image = InputImage.fromFilePath(context, uri)
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it.text) }
                    .addOnFailureListener { cont.resume(null) } // Resume null on failure to keep going
            } catch (e: Exception) {
                cont.resume(null)
            }
        }
}