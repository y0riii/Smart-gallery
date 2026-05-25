package com.example.gallery

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.room.Transaction
import com.example.gallery.db.AppDatabase
import com.example.gallery.db.entities.CategoryEntity
import com.example.gallery.db.entities.MediaCategoryCrossRef
import com.example.gallery.db.entities.MediaEntity
import com.example.gallery.db.entities.MediaPersonCrossRef
import com.example.gallery.db.entities.PersonEntity
import com.example.gallery.ml.face.FaceDetectionProcessor
import com.example.gallery.ml.face.FaceEncoder
import com.example.gallery.ml.image.ClipImageEncoder
import com.example.gallery.ml.ocr.OcrProcessor
import com.example.gallery.ml.text.ClipTextEncoder
import com.example.gallery.utils.ImageUtils
import com.example.gallery.utils.VectorUtils
import com.example.gallery.utils.toMediaUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

class GalleryService(private val context: Context) {

    companion object {
        private const val FACE_MATCH_THRESHOLD = 0.45f
        private const val CATEGORY_MATCH_THRESHOLD = 0.22f
    }

    private val db = AppDatabase.getDatabase(context)
    private val mediaDao = db.mediaDao()
    private val personDao = db.personDao()
    private val categoryDao = db.categoryDao()

    private val textEncoder: ClipTextEncoder by lazy {
        ClipTextEncoder(context)
    }

    private var initJob: Deferred<Unit> = CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
        textEncoder // triggers initialization immediately
    }


    private var ftsSupported: Boolean = false

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
            val dbImageIds = mediaDao.getAllMediaIds().toSet()

            // 4. Calculate the differences (The Diff)
            val idsToDelete = dbImageIds - deviceImageIds // In DB, but missing from device
            val idsToAdd = deviceImageIds - dbImageIds    // On device, but missing from DB

            // 5. Delete removed images from the database and cleanup associations
            if (idsToDelete.isNotEmpty()) {
                Log.d("GalleryService", "Sync: Deleting ${idsToDelete.size} obsolete images")
                idsToDelete.forEach { mediaId ->
                    deleteImageFromDb(mediaId)
                }
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

            logDatabaseContent()
        }
    }

    private suspend fun logDatabaseContent() {
        val allItems = mediaDao.getAllMedia()
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

    private suspend fun processAndInsertImages(imagesToProcess: List<Pair<Long, Long>>) {
        val imageEncoder = ClipImageEncoder(context)
        val ocrProcessor = OcrProcessor()
        val faceDetector = FaceDetectionProcessor()
        val faceEncoder = FaceEncoder(context)
        val totalCount = imagesToProcess.size
        var counter = 0

        imagesToProcess.forEach { (mediaId, timestamp) ->
            try {
                val uri = mediaId.toMediaUri()

                val bitmap = ImageUtils.getBitmapFromUri(context, uri)
                if (bitmap != null) {
                    val (features, text, faces) = withContext(Dispatchers.Default) {
                        val featuresDeferred = async { imageEncoder.getImageFeatures(bitmap) }
                        val textDeferred = async { ocrProcessor.recognizeText(bitmap) }
                        val facesDeferred = async { faceDetector.detectFaces(bitmap) }
                        Triple(
                            featuresDeferred.await(),
                            textDeferred.await(),
                            facesDeferred.await()
                        )
                    }

                    mediaDao.insertAll(
                        listOf(MediaEntity(mediaId, timestamp, false, features, text))
                    )

                    // 1. Process Faces
                    withContext(Dispatchers.Default) {
                        faces.forEach { face ->
                            val croppedImage = faceDetector.alignFace(bitmap, face)
                            val faceFeatures = faceEncoder.getFaceFeatures(croppedImage)
                            val normalizedFaceFeatures = VectorUtils.normalize(faceFeatures)

                            val allPersons = personDao.getAllPersons()
                            val bestMatch = allPersons.maxByOrNull {
                                val avgVec = VectorUtils.normalize(
                                    VectorUtils.divide(it.embedding, it.counter.toFloat())
                                )
                                VectorUtils.dotProduct(normalizedFaceFeatures, avgVec)
                            }

                            var personId: Long
                            val thumbnail = ImageUtils.cropImage(bitmap, face.boundingBox)
                            val thumbnailSize = thumbnail.width * thumbnail.height
                            if (bestMatch == null || VectorUtils.dotProduct(
                                    normalizedFaceFeatures,
                                    VectorUtils.normalize(
                                        VectorUtils.divide(
                                            bestMatch.embedding,
                                            bestMatch.counter.toFloat()
                                        )
                                    )
                                ) < FACE_MATCH_THRESHOLD
                            ) {
                                personId = (personDao.countPersons() + 1).toLong()
                                val thumbnailPath = ImageUtils.createThumbnail(context, thumbnail)
                                personDao.insertPerson(
                                    PersonEntity(
                                        personId,
                                        "#p$personId",
                                        faceFeatures,
                                        1,
                                        thumbnailPath,
                                        thumbnailSize
                                    )
                                )
                            } else {
                                personId = bestMatch.id
                                if (!personDao.crossRefExists(mediaId, personId)) {
                                    val newEmbedding =
                                        VectorUtils.add(bestMatch.embedding, faceFeatures)
                                    personDao.updatePersonEmbedding(personId, newEmbedding)
                                    personDao.incrementPersonCounter(personId)
                                    if (thumbnailSize > bestMatch.thumbnailSize) {
                                        ImageUtils.deleteThumbnail(bestMatch.thumbnailPath)
                                        val thumbnailPath =
                                            ImageUtils.createThumbnail(context, thumbnail)
                                        personDao.updatePersonThumbnail(
                                            personId,
                                            thumbnailPath,
                                            thumbnailSize
                                        )
                                    }
                                }
                            }
                            personDao.insertCrossRef(
                                MediaPersonCrossRef(
                                    mediaId,
                                    personId,
                                    faceFeatures
                                )
                            )
                        }

                        // 2. Process Categories (Auto-assignment logic)
                        val allCategories = categoryDao.getAllCategories()
                        allCategories.forEach { category ->
                            val similarity = VectorUtils.dotProduct(features, category.embedding)
                            if (similarity > CATEGORY_MATCH_THRESHOLD) {
                                categoryDao.insertCrossRef(
                                    MediaCategoryCrossRef(
                                        mediaId,
                                        category.id,
                                        similarity
                                    )
                                )
                            }
                        }
                    }

                    counter++
                    Log.d("GalleryService", "Processed: $counter / $totalCount (ID: $mediaId)")
                }
            } catch (e: Exception) {
                Log.e("GalleryService", "Error processing image $mediaId", e)
            }
        }

        imageEncoder.close()
        ocrProcessor.close()
        faceDetector.close()
        faceEncoder.close()
    }

    /**
     * Deletes an image from the database and cleans up all associations (Person embeddings, Categories).
     */
    @Transaction
    private suspend fun deleteImageFromDb(mediaId: Long) {
        // 1. Cleanup Person Embeddings
        val crossRefs = personDao.getCrossRefsForMedia(mediaId)
        crossRefs.forEach { ref ->
            val person = personDao.getPersonById(ref.personId)
            if (person != null) {
                if (person.counter > 1) {
                    val updatedEmbedding = VectorUtils.subtract(person.embedding, ref.embedding)
                    personDao.updatePersonEmbedding(person.id, updatedEmbedding)
                    personDao.decrementPersonCounter(person.id)
                } else {
                    // Only one image for this person, delete the person entirely
                    personDao.deletePerson(person.id)
                    ImageUtils.deleteThumbnail(person.thumbnailPath)
                }
            }
        }

        // 2. Cross-refs are deleted automatically via CASCADE in Room
        val entity = mediaDao.getMediaById(mediaId)
        if (entity != null) {
            mediaDao.delete(entity)
        }
    }

    suspend fun getAllDeviceImages(): List<Uri> =
        withContext(Dispatchers.IO) {
            ImageUtils.scanMediaStore(context).map { (id, _) -> id.toMediaUri() }
        }

    suspend fun createCategory(prompt: String) {
        initJob.await()
        val textFeatures =
            withContext(Dispatchers.Default) { textEncoder.getTextFeatures("An image of $prompt") }

        withContext(Dispatchers.IO) {
            val categoryId =
                categoryDao.insertCategory(CategoryEntity(name = prompt, embedding = textFeatures))
            val allImages = mediaDao.getAllMedia()

            withContext(Dispatchers.Default) {
                allImages.forEach { image ->
                    val similarity = VectorUtils.dotProduct(image.embedding, textFeatures)
                    if (similarity > CATEGORY_MATCH_THRESHOLD) {
                        categoryDao.insertCrossRef(
                            MediaCategoryCrossRef(
                                image.mediaId,
                                categoryId,
                                similarity
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun search(prompt: String): List<Uri> {
        return withContext(Dispatchers.IO) {
            val (names, cleanPrompt) = findNamesInPrompt(prompt)
            initJob.await()

            // Provide CLIP with the clean prompt where "@name" is replaced by "a person"
            val textFeatures =
                withContext(Dispatchers.Default) { textEncoder.getTextFeatures(cleanPrompt) }

            // Filter by names first if there are any
            val images = if (names.isEmpty()) {
                mediaDao.getAllMedia()
            } else {
                personDao.getImagesByNames(names, names.size)
            }

            // Sort the filtered images by their similarity to the modified text prompt
            val sortedImages = withContext(Dispatchers.Default) {
                images.map { entity ->
                    val similarity = VectorUtils.dotProduct(textFeatures, entity.embedding)
                    entity.mediaId to similarity
                }.sortedByDescending { it.second }
            }

            sortedImages.map { it.first.toMediaUri() }
        }
    }

    suspend fun searchDocuments(text: String): List<Uri> {
        return withContext(Dispatchers.IO) {
            val results = if (ftsSupported) {
                mediaDao.searchMediaFts(text)
            } else {
                mediaDao.searchMediaSimple(text)
            }

            results.map { it.mediaId.toMediaUri() }
        }
    }

    private suspend fun findNamesInPrompt(text: String): Pair<List<String>, String> {
        val allNames = personDao.getAllNames()
        val results = mutableListOf<String>()
        val cleanPromptBuilder = StringBuilder()
        var i = 0

        while (i < text.length) {
            if (text[i] == '@') {
                var bestEnd = -1
                var bestName = ""
                for (name in allNames) {
                    val candidate = "@$name"
                    if (text.startsWith(candidate, i, true)) {
                        val end = i + candidate.length
                        val validBoundary = end == text.length || text[end].isWhitespace()
                        if (validBoundary && end > bestEnd) {
                            bestEnd = end
                            bestName = name
                        }
                    }
                }
                if (bestEnd != -1) {
                    results.add(bestName)
                    cleanPromptBuilder.append("a person") // Replace the tag for CLIP
                    i = bestEnd
                    continue
                }
            }
            cleanPromptBuilder.append(text[i])
            i++
        }
        return Pair(results, cleanPromptBuilder.toString())
    }

    /**
     * Prepares deletion intent. Images are only removed if finalized.
     */
    suspend fun prepareDeleteImage(uri: Uri): PendingIntent? {
        return withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
            } else {
                null
            }
        }
    }

    /**
     * Finalizes deletion in DB and performs deletion on older Android versions.
     */
    suspend fun finalizeDeleteImage(uri: Uri) {
        withContext(Dispatchers.IO) {
            val mediaId = try {
                ContentUris.parseId(uri)
            } catch (e: Exception) {
                null
            }

            if (mediaId != null) {
                deleteImageFromDb(mediaId)
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (e: Exception) {
                    Log.e("GalleryService", "Failed to delete image from device", e)
                }
            }
        }
    }
}
