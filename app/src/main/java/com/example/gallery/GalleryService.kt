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

class GalleryService(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)

    private val dao = db.mediaDao()

    private val textEncoder: ClipTextEncoder by lazy {
        ClipTextEncoder(context)
    }

    suspend fun preloadTextModel() {
        withContext(Dispatchers.IO) {
            textEncoder
        }
    }

    private var ftsSupported: Boolean = false

    suspend fun indexImagesBackground() {
        return withContext(Dispatchers.IO) {
            try {
                db.openHelper.readableDatabase
                    .query("SELECT rowid FROM media_items_fts LIMIT 1")
                    .use { _ -> ftsSupported = true }
            } catch (_: Exception) {
                ftsSupported = false
            }

            val count = dao.count()

            // 2. If empty → index images
            if (count == 0) {
                indexImages()
            }
        }
    }

    private suspend fun indexImages() {

        val deviceImages = ImageUtils.scanMediaStore(context)

        var counter = 0
        val totalCount = deviceImages.size

        val encoder = ClipImageEncoder(context)
        val ocrProcessor = OcrProcessor()


        deviceImages.forEach { (id, timestamp) ->

            try {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                val bitmap = ImageUtils.getBitmapFromUri(context, uri)
                if (bitmap != null) {

                    val features = encoder.getImageFeatures(bitmap)
                    val text = ocrProcessor.recognizeText(bitmap)

                    dao.insertAll(
                        listOf(MediaEntity(id, timestamp, false, features, text))
                    )

                    Log.d(
                        "GalleryService",
                        "Saved image number: $counter (${(counter * 100) / totalCount}%), with id: $id"
                    )
                }

            } catch (e: Exception) {
                Log.e("GalleryService", "Error processing $id", e)
            }
            counter++
        }
        encoder.close()
        ocrProcessor.close()
    }

    suspend fun getAllDeviceImages(): List<Uri> =
        withContext(Dispatchers.IO) {
            ImageUtils.scanMediaStore(context).map { (id, _) ->
                ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )
            }
        }

    suspend fun search(prompt: String): List<Uri> {

        return withContext(Dispatchers.IO) {

            val textFeatures = textEncoder.getTextFeatures(prompt)
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