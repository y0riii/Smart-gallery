package com.example.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.example.gallery.db.AppDatabase
import com.example.gallery.db.MediaEntity
import com.example.gallery.db.OcrProcessor
import com.example.gallery.ml.ClipImageEncoder
import com.example.gallery.ml.ClipTextEncoder
import com.example.gallery.ml.ImageUtils
import com.example.gallery.ml.VectorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SearchResult(val uri: Uri, val similarity: Float)

class GalleryService(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)

    private val dao = db.mediaDao()

    private val ocrProcessor = OcrProcessor(db, context)

    private var imageEncoder: ClipImageEncoder? = null
    private var textEncoder: ClipTextEncoder? = null

    private var ftsSupported: Boolean = false

    private suspend fun loadModels() {
        ImageUtils.scanMediaStore(context)
        withContext(Dispatchers.IO) {
            if (imageEncoder == null) imageEncoder = ClipImageEncoder(context)
            if (textEncoder == null) textEncoder = ClipTextEncoder(context)
        }
    }

    suspend fun loadAllIndexedImages(): List<Uri> {
        return withContext(Dispatchers.IO) {
            try {
                db.openHelper.readableDatabase
                    .query("SELECT rowid FROM media_items_fts LIMIT 1")
                    .use { cursor -> ftsSupported = true }
            } catch (e: Exception) {
                ftsSupported = false
            }

            val count = dao.count()

            // 2. If empty → index images
            if (count == 0) {
                indexImages()
            }

            dao.getAllMedia().map {
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.mediaId)
            }
        }
    }

    private suspend fun indexImages() {
        loadModels()

        var deviceImages = ImageUtils.scanMediaStore(context)

        deviceImages = deviceImages.shuffled().take(500)

        withContext(Dispatchers.IO) {

            deviceImages.forEach { (id, timestamp) ->
                try {
                    val uri =
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val bitmap = ImageUtils.getBitmapFromUri(context, uri)
                    if (bitmap != null) {
                        val features = imageEncoder?.getImageFeatures(bitmap)
                        if (features != null) {
                            val text = ocrProcessor.recognizeText(id)
                            dao.insertAll(listOf(MediaEntity(id, timestamp, false, features, text)))
                            Log.d("GalleryService", "Saved image $id")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GalleryService", "Error processing $id", e)
                }
            }
        }
    }

    suspend fun search(prompt: String): List<Uri> {
        if (textEncoder == null) loadModels()

        return withContext(Dispatchers.IO) {

            val textFeatures = textEncoder!!.getTextFeatures(prompt)
            val images = dao.getAllMedia()

            val sortedImages = images.map { entity ->
                val similarity = VectorUtils.dotProduct(textFeatures, entity.embedding)
                entity.mediaId to similarity
            }.sortedByDescending { it.second }
            sortedImages.map {
                ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    it.first
                )
            }
        }
    }

    suspend fun searchDocuments(text: String): List<Uri> {
        return withContext(Dispatchers.IO) {

            val results = if (ftsSupported) {
                dao.searchMediaFts(text)   // returns List<MediaEntity>
            } else {
                dao.searchMediaSimple(text)
            }

            // Convert each result to a real MediaStore URI
            results.map { entity ->
                ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    entity.mediaId
                )
            }
        }
    }
}