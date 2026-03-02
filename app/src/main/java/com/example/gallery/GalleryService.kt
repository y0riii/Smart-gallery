package com.example.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.example.gallery.db.AppDatabase
import com.example.gallery.db.MediaEntity
import com.example.gallery.db.OcrProcessor
import com.example.gallery.faceDetection.FaceDetectionProcessor
import com.example.gallery.ml.ClipImageEncoder
import com.example.gallery.ml.ClipTextEncoder
import com.example.gallery.ml.ImageUtils
import com.example.gallery.ml.VectorUtils
import com.google.mlkit.vision.face.Face
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GalleryService(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val dao = db.mediaDao()
    private val faceDao = db.faceDao() // Groundwork for face recognition

    private val textEncoder: ClipTextEncoder by lazy {
        ClipTextEncoder(context)
    }

    private var ftsSupported: Boolean = false

    suspend fun preloadTextModel() {
        withContext(Dispatchers.IO) {
            textEncoder
        }
    }

//    fun saveBitmapToGallery(
//        bitmap: Bitmap,
//        fileName: String
//    ): Boolean {
//
//        val resolver = context.contentResolver
//
//        val contentValues = ContentValues().apply {
//            put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.jpg")
//            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
//            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FaceCrops")
//            put(MediaStore.Images.Media.IS_PENDING, 1)
//        }
//
//        val imageUri = resolver.insert(
//            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
//            contentValues
//        ) ?: return false
//
//        return try {
//            resolver.openOutputStream(imageUri)?.use { stream ->
//                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
//            }
//
//            contentValues.clear()
//            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
//            resolver.update(imageUri, contentValues, null, null)
//
//            true
//        } catch (e: Exception) {
//            false
//        }
//    }

    /**
     * Compares the device's MediaStore with the Room Database and syncs them.
     */
    suspend fun indexImagesBackground() {
        withContext(Dispatchers.IO) {
            // 1. Check FTS support
            try {
                db.openHelper.readableDatabase
                    .query("SELECT rowid FROM media_items_fts LIMIT 1")
                    .use { _ -> ftsSupported = true }
            } catch (_: Exception) {
                ftsSupported = false
            }

            // 2. Fetch IDs currently on the device
            val deviceImages = ImageUtils.scanMediaStore(context)
            val deviceImageIds = deviceImages.map { it.first }.toSet()

            // 3. Fetch IDs currently in the database
            // Note: Ensure you have added getAllMediaIds() to your MediaDao or use getAllMedia().map { it.mediaId }
            val dbImageIds = dao.getAllMedia().map { it.mediaId }.toSet()

            // 4. Calculate the differences (The Diff)
            val idsToDelete = dbImageIds - deviceImageIds // In DB, but missing from device
            val idsToAdd = deviceImageIds - dbImageIds    // On device, but missing from DB

            // 5. Delete removed images from the database
            if (idsToDelete.isNotEmpty()) {
                // You may need to add a dedicated deleteByIds(List<Long>) to your MediaDao
                val itemsToDelete = dao.getAllMedia().filter { it.mediaId in idsToDelete }
                dao.deleteAll(itemsToDelete)
                Log.d("GalleryService", "Sync: Deleted ${idsToDelete.size} obsolete images from DB")
            }

            // 6. Process and insert newly added images
            if (idsToAdd.isNotEmpty()) {
                val newImagesToProcess = deviceImages.filter { it.first in idsToAdd }
                Log.d(
                    "GalleryService",
                    "Sync: Found ${newImagesToProcess.size} new images to index."
                )
                processAndInsertImages(newImagesToProcess)
            } else {
                Log.d("GalleryService", "Sync: Database is fully synced. No new images to process.")
            }

            // 7. Testing Purpose: Print DB Content to Logcat
            logDatabaseContent()
        }
    }

    /**
     * Iterates through the database and prints content to Logcat for testing.
     */
    private suspend fun logDatabaseContent() {
        val allItems = dao.getAllMedia()
        Log.d("DB_DEBUG", "=== CURRENT DATABASE CONTENT (${allItems.size} items) ===")
        allItems.forEachIndexed { index, entity ->
            val embeddingPreview = entity.embedding.take(3).joinToString(", ")
            Log.d(
                "DB_DEBUG",
                "[#$index] ID: ${entity.mediaId} | OCR: ${entity.ocrText?.take(30) ?: "None"}... | Vector: [$embeddingPreview...]"
            )
        }
        Log.d("DB_DEBUG", "============================================")
    }

    /**
     * Extracts features (OCR + CLIP) and saves new images to the database.
     */
    private suspend fun processAndInsertImages(imagesToProcess: List<Pair<Long, Long>>) {
        val encoder = ClipImageEncoder(context)
        val ocrProcessor = OcrProcessor()
        val faceDetector = FaceDetectionProcessor()
        val totalCount = imagesToProcess.size
        var counter = 0

        imagesToProcess.forEach { (id, timestamp) ->
            try {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                val bitmap = ImageUtils.getBitmapFromUri(context, uri)
                if (bitmap != null) {
                    // CLIP & OCR Processing
                    val features = encoder.getImageFeatures(bitmap)
                    val text = ocrProcessor.recognizeText(bitmap)
                    val faces: List<Face> = faceDetector.detectFaces(bitmap)

                    Log.d("GalleryService", "Detected: ${faces.size} faces (ID: $id)")

//                    faces.forEach { face ->
//                        val croppedImage = faceDetector.cropFace(bitmap, face.boundingBox)
//                        saveBitmapToGallery(croppedImage, "face_${System.currentTimeMillis()}")
//                    }

                    dao.insertAll(
                        listOf(MediaEntity(id, timestamp, false, features, text))
                    )

                    // Note: Face recognition logic will be integrated here in the next step.

                    counter++
                    Log.d("GalleryService", "Processed: $counter / $totalCount (ID: $id)")
                }
            } catch (e: Exception) {
                Log.e("GalleryService", "Error processing image $id", e)
            }
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
                dao.searchMediaFts(text)
            } else {
                dao.searchMediaSimple(text)
            }

            results.map { entity ->
                ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    entity.mediaId
                )
            }
        }
    }
}