package com.example.gallery

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import androidx.room.Transaction
import androidx.room.withTransaction
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.gallery.db.AppDatabase
import com.example.gallery.db.FaceClusteringWorker
import com.example.gallery.db.GalleryIndexerWorker
import com.example.gallery.db.entities.CategoryEntity
import com.example.gallery.db.entities.FaceEntity
import com.example.gallery.db.entities.MediaCategoryCrossRef
import com.example.gallery.db.entities.MediaEntity
import com.example.gallery.db.entities.PersonEntity
import com.example.gallery.db.previews.MediaDateInfo
import com.example.gallery.ml.face.FaceDetectionProcessor
import com.example.gallery.ml.face.FaceEncoder
import com.example.gallery.ml.face.ChineseWhispers
import com.example.gallery.ml.image.ClipImageEncoder
import com.example.gallery.ml.ocr.OcrProcessor
import com.example.gallery.ml.text.ClipTextEncoder
import com.example.gallery.utils.ImageUtils
import com.example.gallery.utils.VideoUtils
import com.example.gallery.utils.VectorUtils
import com.example.gallery.utils.toMediaUri
import com.example.gallery.utils.toVideoUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class GalleryService(val context: Context) {

    fun isUriDeleted(uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { }
            false
        } catch (e: Exception) {
            true
        }
    }

    fun startIndexingWorkManager(force: Boolean = false) {
        val indexRequest = OneTimeWorkRequestBuilder<GalleryIndexerWorker>()
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.LINEAR,
                5,
                java.util.concurrent.TimeUnit.MINUTES
            ).build()

        WorkManager.getInstance(context)
            .beginUniqueWork(
                "GalleryIndexing_OneTime",
                if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                indexRequest
            )
            .enqueue()
    }

    /**
     * Triggers a full Chinese Whispers re-clustering by resetting the
     * first-run flag and enqueuing a one-time FaceClusteringWorker.
     * Any existing clustering work is replaced.
     */
    fun forceRecluster() {
        val now = System.currentTimeMillis()
        if (now - lastReclusterTime < 5000) {
            Log.d(TAG, "forceRecluster: Ignored due to spam prevention")
            return
        }
        lastReclusterTime = now

        if (isIndexingRunning || isClusteringRunning) {
            Log.d(TAG, "forceRecluster: Indexing or Clustering is already running. Ignoring to prevent race condition.")
            return
        }

        Log.d(TAG, "forceRecluster: resetting clustering flag and triggering full indexing + clustering pass")
        val prefs = context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("first_clustering_run_completed", false).apply()

        startIndexingWorkManager(force = true)
    }

    companion object {
        private const val TAG = "GalleryService"
        private const val CATEGORY_MATCH_THRESHOLD = 0.22f
        private const val ASSIGN_THRESHOLD = 0.45f

        val progress = MutableStateFlow<Float?>(null)

        @Volatile
        var isIndexingRunning = false

        @Volatile
        var isClusteringRunning = false

        val mlExecutionLock = kotlinx.coroutines.sync.Mutex()

        @Volatile
        private var lastReclusterTime = 0L

        @Volatile
        private var textEncoderInstance: ClipTextEncoder? = null
        private var textEncoderInitJob: Deferred<Unit>? = null
        private val initLock = Any()

        fun getTextEncoder(context: Context): ClipTextEncoder? {
            return synchronized(initLock) {
                if (textEncoderInstance == null) {
                    try {
                        textEncoderInstance = ClipTextEncoder(context.applicationContext)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to init TextEncoder", e)
                    }
                }
                textEncoderInstance
            }
        }

        fun ensureTextEncoderInitialized(context: Context): Deferred<Unit> {
            return synchronized(initLock) {
                var job = textEncoderInitJob
                val shouldRecreate = job == null || (job.isCompleted && textEncoderInstance == null)
                if (shouldRecreate) {
                    val appContext = context.applicationContext
                    job = CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
                        try {
                            getTextEncoder(appContext)
                        } catch (t: Throwable) {
                            Log.e(TAG, "TextEncoder pre-load failed", t)
                        }
                    }
                    textEncoderInitJob = job
                }
                job
            }
        }
    }

    val db = AppDatabase.getDatabase(context)
    private val mediaDao = db.mediaDao()
    private val personDao = db.personDao()
    private val categoryDao = db.categoryDao()
    private val faceDao = db.faceDao()

    private val textEncoder: ClipTextEncoder?
        get() = getTextEncoder(context)

    private val initJob: Deferred<Unit>
        get() = ensureTextEncoderInitialized(context)

    /** True once the text encoder has finished loading (or failed). */
    val isModelReady: Boolean get() = initJob.isCompleted

    private val ftsSupported by lazy {
        try {
            db.openHelper.readableDatabase
                .query("SELECT 1 FROM media_items_fts LIMIT 1")
                .use { }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Compares the device's MediaStore with the Room Database and syncs them.
     *
     * Returns true if indexing ran, false if skipped because another invocation
     * was already in progress (callers should treat false as a no-op, not an error).
     */
    suspend fun indexImagesBackground(onProgress: suspend (Int, Int) -> Unit) {
        withContext(Dispatchers.IO) {
            // 1. Fetch IDs currently on the device
            val deviceImages = ImageUtils.scanMediaStore(context)
            val deviceImageIds = deviceImages.map { it.first }.toSet()

            // 2. Fetch IDs currently in the database
            val dbImageIds = mediaDao.getAllMediaIds().toSet()

            // 3. Calculate the differences (The Diff)
            val idsToDelete = dbImageIds - deviceImageIds // In DB, but missing from device
            val idsToAdd = deviceImageIds - dbImageIds    // On device, but missing from DB

            // 4. Delete removed images from the database and cleanup associations
            if (idsToDelete.isNotEmpty()) {
                Log.d(TAG, "Sync: Deleting ${idsToDelete.size} obsolete images")
                idsToDelete.forEach { mediaId ->
                    deleteImageFromDb(mediaId)
                }
            }

            // 5. Process and insert newly added images
            if (idsToAdd.isNotEmpty()) {
                val newImagesToProcess = deviceImages.filter { it.first in idsToAdd }
                Log.d(TAG, "Sync: Found ${newImagesToProcess.size} new images to index.")
                processAndInsertImages(newImagesToProcess, onProgress)
            } else {
                Log.d(TAG, "Sync: Database is fully synced. No new images to process.")
                progress.value = null
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

    private suspend fun processAndInsertImages(imagesToProcess: List<Pair<Long, Long>>, onProgress: suspend (Int, Int) -> Unit) {
        var imageEncoder: ClipImageEncoder? = null
        var ocrProcessor: OcrProcessor? = null
        var faceDetector: FaceDetectionProcessor? = null
        var faceEncoder: FaceEncoder? = null

        fun initProcessors(): Boolean {
            try {
                if (imageEncoder == null) imageEncoder = ClipImageEncoder(context)
                if (ocrProcessor == null) ocrProcessor = OcrProcessor()
                if (faceDetector == null) faceDetector = FaceDetectionProcessor()
                if (faceEncoder == null) faceEncoder = FaceEncoder(context)
                return true
            } catch (e: Throwable) {
                Log.e("GalleryService", "Failed to init processors", e)
                imageEncoder?.close(); imageEncoder = null
                ocrProcessor?.close(); ocrProcessor = null
                faceDetector?.close(); faceDetector = null
                faceEncoder?.close(); faceEncoder = null
                return false
            }
        }

        fun closeProcessors() {
            imageEncoder?.close(); imageEncoder = null
            ocrProcessor?.close(); ocrProcessor = null
            faceDetector?.close(); faceDetector = null
            faceEncoder?.close(); faceEncoder = null
        }

        try {
            val totalCount = imagesToProcess.size
            var counter = 0
            progress.value = 0f

            for (item in imagesToProcess) {
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                    Log.d("GalleryService", "Indexing loop cancelled, breaking.")
                    break
                }

                // Check if the device gets hot
                var isThermalPaused = false
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
                    val status = powerManager.currentThermalStatus
                    if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                        isThermalPaused = true
                        Log.w("GalleryService", "Device is hot (thermal status: $status). Pausing indexing.")
                    }
                }

                // Check if we are paused (either manually or due to thermal throttling)!
                if (GalleryIndexerWorker.isPaused || isThermalPaused) {
                    Log.d("GalleryService", "Indexing is paused (Manual/Thermal). Releasing ML models and waiting...")
                    closeProcessors()
                    progress.value = null

                    // Wait loop
                    while (GalleryIndexerWorker.isPaused || isThermalPaused) {
                        if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                            break
                        }
                        kotlinx.coroutines.delay(1000) // check every 1 second
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
                            val status = powerManager.currentThermalStatus
                            // Resume only when cooled down below moderate (i.e. to light or none)
                            isThermalPaused = status >= PowerManager.THERMAL_STATUS_MODERATE
                            if (isThermalPaused) {
                                Log.w("GalleryService", "Device is still warm (thermal status: $status). Waiting to cool down...")
                            }
                        } else {
                            isThermalPaused = false
                        }
                    }

                    // Check if cancelled during wait
                    if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                        break
                    }

                    Log.d("GalleryService", "Indexing resumed. Reinitializing ML models...")
                    progress.value = counter / totalCount.toFloat()
                }

                // Initialize processors if they are null
                val encoder1 = imageEncoder
                val processor1 = ocrProcessor
                val detector1 = faceDetector
                val encoder2 = faceEncoder
                if (encoder1 == null || processor1 == null || detector1 == null || encoder2 == null) {
                    if (!initProcessors()) {
                        Log.e("GalleryService", "Aborting indexing because processors failed to initialize.")
                        throw IllegalStateException("Processors failed to initialize")
                    }
                }

                val mediaId = item.first
                val timestamp = item.second
                try {
                    val bitmap = ImageUtils.getBitmapFromUri(context, mediaId.toMediaUri())
                    if (bitmap != null) {
                        val (features, text, faces) = withContext(Dispatchers.Default) {
                            val featuresDeferred = async { imageEncoder!!.getImageFeatures(bitmap) }
                            val textDeferred = async { ocrProcessor!!.recognizeText(bitmap) }
                            val facesDeferred = async { faceDetector!!.detectFaces(bitmap) }
                            Triple(
                                featuresDeferred.await(),
                                textDeferred.await(),
                                facesDeferred.await()
                            )
                        }

                        // 1. Save face embeddings + bounding box to FaceEntity (clustering happens offline later)
                        withContext(Dispatchers.Default) {
                            val faceEntities = faces.map { face ->
                                val croppedImage = faceDetector!!.alignFace(bitmap, face)
                                val faceFeatures = faceEncoder!!.getFaceFeatures(croppedImage)
                                val box = ImageUtils.scaleRect(face.boundingBox, 1.5f)

                                FaceEntity(
                                    mediaId = mediaId,
                                    embedding = faceFeatures,
                                    boxLeft = box.left,
                                    boxTop = box.top,
                                    boxRight = box.right,
                                    boxBottom = box.bottom
                                )
                            }

                            // 2. Process Categories (Auto-assignment logic)
                            val allCategories = withContext(Dispatchers.IO) { categoryDao.getAllCategories() }
                            val crossRefs = allCategories.mapNotNull { category ->
                                val similarity = VectorUtils.dotProduct(features, category.embedding)
                                if (similarity > CATEGORY_MATCH_THRESHOLD) {
                                    MediaCategoryCrossRef(
                                        mediaId,
                                        category.id,
                                        similarity
                                    )
                                } else {
                                    null
                                }
                            }

                            withContext(Dispatchers.IO) {
                                db.withTransaction {
                                    mediaDao.insertAll(listOf(MediaEntity(mediaId, timestamp, false, features, text)))

                                    if (faceEntities.isNotEmpty()) {
                                        faceDao.insertFaces(faceEntities)
                                    }

                                    if (crossRefs.isNotEmpty()) {
                                        categoryDao.insertCrossRefs(crossRefs)
                                    }
                                }
                            }
                        }

                        counter++
                        Log.d("GalleryService", "Processed: $counter / $totalCount (ID: $mediaId)")
                        progress.value = counter / totalCount.toFloat()
                        onProgress(counter, totalCount)
                    }
                } catch (e: Exception) {
                    Log.e("GalleryService", "Error processing image $mediaId", e)
                }
            }
        } finally {
            progress.value = null
            closeProcessors()
        }
    }

    /**
     * Deletes an image from the database and cleans up face data.
     * Orphaned persons (persons with no remaining cross-refs) will be
     * cleaned up on the next clustering run.
     */
    @Transaction
    private suspend fun deleteImageFromDb(mediaId: Long) {
        val faces = faceDao.getFacesForMedia(mediaId)
        // 1. Update embeddings for persons
        for (face in faces) {
            if (face.personId == null) {
                continue
            }
            val person = personDao.getPersonById(face.personId)
            val normFace = VectorUtils.normalize(face.embedding)
            if (person != null) {
                if (person.count > 1) {
                    val updatedEmbedding = VectorUtils.subtract(person.Embedding, normFace)
                    personDao.updateEmbedding(person.id, updatedEmbedding)
                    personDao.decrementPersonCounter(person.id)
                } else {
                    // only one face left for the person, delete the person entirely
                    ImageUtils.deleteThumbnail(person.thumbnailPath)
                    personDao.deletePerson(person.id)
                }
            }
        }

        // 2. Delete face entries for this media
        faceDao.deleteFacesForMedia(mediaId)

        // 3. Delete the media entity (cross-refs are deleted via CASCADE)
        val entity = mediaDao.getMediaById(mediaId)
        if (entity != null) {
            mediaDao.delete(entity)
        }
    }


    /**
     * Performs face clustering on all faces in the database.
     *
     * - First run (no persons exist): runs full Chinese Whispers clustering.
     * - Subsequent runs: assigns new (unassigned) faces to existing persons
     *   via sequential centroid comparison, or creates new singleton persons.
     * - After clustering, generates person thumbnails from face bounding boxes.
     */
    suspend fun createClusters(): Boolean {
        return withContext(Dispatchers.IO) {
            val allFaces = faceDao.getAllFaces()
            Log.d(TAG, "Loaded ${allFaces.size} faces for clustering")

            if (allFaces.isEmpty()) {
                Log.d(TAG, "No faces to cluster, skipping")
                return@withContext true
            }

            val prefs = context.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE)
            val firstRunCompleted = prefs.getBoolean("first_clustering_run_completed", false)
            val allPersons = personDao.getAllPersons()
            val shouldRunFirstRun = !firstRunCompleted || allPersons.isEmpty()

            if (shouldRunFirstRun) {
                // ── FIRST RUN: Full Chinese Whispers on all faces ──
                Log.d(TAG, "First run: not completed yet (or empty persons), running full Chinese Whispers from scratch")

                // Clear any partial person data to start clean from scratch
                db.withTransaction {
                    personDao.deleteAllPersons()
                    faceDao.clearAllPersonAssignments()
                }

                // Re-query all faces to ensure we have the fresh set
                val facesToCluster = faceDao.getAllFaces()
                if (facesToCluster.isEmpty()) {
                    Log.d(TAG, "No faces to cluster, skipping first run")
                    prefs.edit().putBoolean("first_clustering_run_completed", true).apply()
                    return@withContext true
                }

                val normalizedFaces = facesToCluster.map { VectorUtils.normalize(it.embedding) }
                val clusters = ChineseWhispers.cluster(normalizedFaces)

                val personsToInsert = mutableListOf<PersonEntity>()
                val faceUpdates = mutableListOf<Pair<Long, Long>>() // faceId to personId

                var personCounter = 0

                for (cluster in clusters) {
                    if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                        Log.d(TAG, "First run clustering loop cancelled, aborting.")
                        return@withContext false
                    }

                    personCounter++
                    if (cluster.isEmpty()) continue

                    val personName = "#p$personCounter"

                    // Compute sum of normalized embeddings for this cluster
                    val dim = facesToCluster.first().embedding.size
                    val sum = FloatArray(dim)
                    for (faceIdx in cluster) {
                        val norm = normalizedFaces[faceIdx]
                        for (d in norm.indices) sum[d] += norm[d]
                    }

                    // thumbnail creation
                    val clusterFaces = cluster.map { facesToCluster[it] }
                    val bestFace = clusterFaces.maxByOrNull {
                        (it.boxRight - it.boxLeft) * (it.boxBottom - it.boxTop)
                    } ?: continue

                    val boxArea = (bestFace.boxRight - bestFace.boxLeft) * (bestFace.boxBottom - bestFace.boxTop)
                    val bitmap = ImageUtils.getBitmapFromUri(context, bestFace.mediaId.toMediaUri())
                    var thumbnailPath: String? = null
                    if (bitmap != null) {
                        try {
                            val rect = Rect(bestFace.boxLeft, bestFace.boxTop, bestFace.boxRight, bestFace.boxBottom)
                            val cropped = ImageUtils.cropImage(bitmap, rect)
                            try {
                                thumbnailPath = ImageUtils.createThumbnail(context, cropped)
                            } finally {
                                cropped.recycle()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to crop or save thumbnail for face ${bestFace.faceId}", e)
                        } finally {
                            bitmap.recycle()
                        }
                    }

                    val personId = personCounter.toLong()
                    personsToInsert.add(
                        PersonEntity(
                            id = personId,
                            name = personName,
                            thumbnailPath = thumbnailPath ?: "",
                            thumbnailSize = boxArea,
                            Embedding = sum,
                            count = cluster.size
                        )
                    )

                    for (faceIdx in cluster) {
                        val face = facesToCluster[faceIdx]
                        faceUpdates.add(face.faceId to personId)
                    }
                }

                // Now run a single database transaction to apply all changes
                db.withTransaction {
                    // Double check to make sure we start clean
                    personDao.deleteAllPersons()
                    faceDao.clearAllPersonAssignments()

                    personsToInsert.forEach { personDao.insertPerson(it) }
                    faceUpdates.forEach { (faceId, personId) ->
                        faceDao.updateFacePersonId(faceId, personId)
                    }
                }

                // Set first run completed preference ONLY after successful commit
                prefs.edit().putBoolean("first_clustering_run_completed", true).apply()
                Log.d(TAG, "First run complete: ${personsToInsert.size} persons created and ${faceUpdates.size} faces assigned.")
            } else {
                // ── SUBSEQUENT RUNS: Incremental centroid assignment ──
                val newFaces = allFaces.filter { it.personId == null }

                if (newFaces.isEmpty()) {
                    Log.d(TAG, "No new faces to process")
                } else {
                    Log.d(TAG, "Incremental run: ${newFaces.size} new faces, ${allPersons.size} existing persons")

                    val personsToInsert = mutableListOf<PersonEntity>()
                    val personUpdates = mutableListOf<PersonEntity>()
                    val faceUpdates = mutableListOf<Pair<Long, Long>>() // faceId to personId

                    // We need a local cache of person embeddings to update them as we iterate,
                    // since multiple new faces might be assigned to the same person.
                    val personCache = allPersons.associateBy { it.id }.toMutableMap()
                    val maxId = personCache.keys.maxOrNull() ?: 0L
                    var currentPersonCount = maxId

                    var assignedCount = 0
                    var newPersonCount = 0

                    for (face in newFaces) {
                        if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                            Log.d(TAG, "Incremental clustering loop cancelled, aborting.")
                            return@withContext false
                        }

                        val normEmb = VectorUtils.normalize(face.embedding)
                        val boxArea = (face.boxRight - face.boxLeft) * (face.boxBottom - face.boxTop)

                        val bestMatch = personCache.values.maxByOrNull { person ->
                            val avgVec = VectorUtils.normalize(
                                VectorUtils.divide(person.Embedding, person.count.toFloat())
                            )
                            VectorUtils.dotProduct(normEmb, avgVec)
                        }

                        val personId: Long
                        if (bestMatch == null || VectorUtils.dotProduct(
                                normEmb,
                                VectorUtils.normalize(
                                    VectorUtils.divide(
                                        bestMatch.Embedding,
                                        bestMatch.count.toFloat()
                                    )
                                )
                            ) < ASSIGN_THRESHOLD
                        ) {
                            // Create a new person
                            currentPersonCount++
                            personId = currentPersonCount

                            val bitmap = ImageUtils.getBitmapFromUri(context, face.mediaId.toMediaUri())
                            var thumbnailPath: String? = null
                            if (bitmap != null) {
                                try {
                                    val rect = Rect(face.boxLeft, face.boxTop, face.boxRight, face.boxBottom)
                                    val cropped = ImageUtils.cropImage(bitmap, rect)
                                    try {
                                        thumbnailPath = ImageUtils.createThumbnail(context, cropped)
                                    } finally {
                                        cropped.recycle()
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to create thumbnail for face ${face.faceId}", e)
                                } finally {
                                    bitmap.recycle()
                                }
                            }

                            val newPerson = PersonEntity(
                                id = personId,
                                name = "#p$personId",
                                thumbnailPath = thumbnailPath ?: "",
                                thumbnailSize = boxArea,
                                Embedding = normEmb,
                                count = 1
                            )
                            personCache[personId] = newPerson
                            personsToInsert.add(newPerson)
                            newPersonCount++
                            Log.d(TAG, "Prepared new person '#p$personId' (id=$personId) for face ${face.faceId}")
                        } else {
                            // Assign to existing person
                            personId = bestMatch.id
                            val updatedEmbedding = VectorUtils.add(bestMatch.Embedding, normEmb)

                            var updatedThumbnailPath = bestMatch.thumbnailPath
                            var updatedThumbnailSize = bestMatch.thumbnailSize

                            if (boxArea > bestMatch.thumbnailSize) {
                                val bitmap = ImageUtils.getBitmapFromUri(context, face.mediaId.toMediaUri())
                                if (bitmap != null) {
                                    try {
                                        val rect = Rect(face.boxLeft, face.boxTop, face.boxRight, face.boxBottom)
                                        val cropped = ImageUtils.cropImage(bitmap, rect)
                                        try {
                                            val newThumb = ImageUtils.createThumbnail(context, cropped)
                                            ImageUtils.deleteThumbnail(bestMatch.thumbnailPath)
                                            updatedThumbnailPath = newThumb
                                            updatedThumbnailSize = boxArea
                                        } finally {
                                            cropped.recycle()
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to update thumbnail for person ${bestMatch.id}", e)
                                    } finally {
                                        bitmap.recycle()
                                    }
                                }
                            }

                            val updatedPerson = bestMatch.copy(
                                Embedding = updatedEmbedding,
                                count = bestMatch.count + 1,
                                thumbnailPath = updatedThumbnailPath,
                                thumbnailSize = updatedThumbnailSize
                            )
                            personCache[personId] = updatedPerson

                            personUpdates.removeAll { it.id == personId }
                            personUpdates.add(updatedPerson)
                            assignedCount++
                        }

                        faceUpdates.add(face.faceId to personId)
                    }

                    // Single transaction for incremental updates
                    db.withTransaction {
                        personsToInsert.forEach { personDao.insertPerson(it) }
                        personUpdates.forEach { person ->
                            personDao.updateEmbedding(person.id, person.Embedding)
                            personDao.updatePersonCounter(person.id, person.count.toLong())
                            personDao.updatePersonThumbnail(person.id, person.thumbnailPath, person.thumbnailSize)
                        }
                        faceUpdates.forEach { (faceId, personId) ->
                            faceDao.updateFacePersonId(faceId, personId)
                        }
                    }

                    Log.d(TAG, "Incremental run complete: $assignedCount assigned to existing, $newPersonCount new persons created")
                }
            }
            true
        }
    }

    suspend fun getAllDeviceImages(): List<Uri> =
        withContext(Dispatchers.IO) {
            ImageUtils.scanMediaStore(context).map { (id, _) -> id.toMediaUri() }
        }

    /**
     * Returns all images AND videos merged and sorted by date descending.
     * Videos are included only when no semantic/text search is active.
     */
    suspend fun getAllDeviceMedia(): List<Uri> =
        withContext(Dispatchers.IO) {
            val images = ImageUtils.scanMediaStore(context).map { (id, ts) -> ts to id.toMediaUri() }
            val videos = VideoUtils.scanMediaStore(context).map { (id, ts) -> ts to id.toVideoUri() }
            (images + videos)
                .sortedByDescending { it.first }
                .map { it.second }
        }

    /**
     * Returns video URIs filtered by an optional date range.
     * Used when a search is date-only (no text/name filters).
     */
    suspend fun getDeviceVideosWithDateFilter(fromDate: Long?, toDate: Long?): List<Uri> =
        withContext(Dispatchers.IO) {
            VideoUtils.scanMediaStoreWithDateFilter(context, fromDate, toDate)
                .map { it.toVideoUri() }
        }

    suspend fun createCategory(prompt: String) {
        try {
            initJob.await()
        } catch (e: Exception) {
            Log.e(TAG, "Model init failed during createCategory, retrying", e)
            initJob.await()
        }
        val encoder = textEncoder ?: return
        val textFeatures =
            withContext(Dispatchers.Default) { encoder.getTextFeatures("An image of $prompt") }

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

    fun filterByDate(
        images: List<MediaEntity>,
        fromDate: Long?,
        toDate: Long?
    ): List<MediaEntity> {
        return if (fromDate == null && toDate == null) {
            images
        } else {
            images.filter { entity ->
                val afterFrom = fromDate == null || entity.timestampMs >= fromDate
                val beforeTo = toDate == null || entity.timestampMs <= (toDate + 86400000 - 1)
                afterFrom && beforeTo
            }
        }
    }

    fun filterByDateInfo(
        images: List<MediaDateInfo>,
        fromDate: Long?,
        toDate: Long?
    ): List<MediaDateInfo> {
        return if (fromDate == null && toDate == null) {
            images
        } else {
            images.filter { entity ->
                val afterFrom = fromDate == null || entity.timestampMs >= fromDate
                val beforeTo = toDate == null || entity.timestampMs <= (toDate + 86400000 - 1)
                afterFrom && beforeTo
            }
        }
    }

    suspend fun search(prompt: String, fromDate: Long? = null, toDate: Long? = null): List<Uri> {
        return withContext(Dispatchers.IO) {
            val (names, cleanPrompt) = findNamesInPrompt(prompt)

            // Date-only search: no text, no names — include videos merged with images
            if (cleanPrompt.isBlank() && names.isEmpty()) {
                val imageIds = getDeviceImagesWithDateFilter(fromDate, toDate).map { it.toMediaUri() }
                val videoUris = getDeviceVideosWithDateFilter(fromDate, toDate)
                // Merge images and videos, sorted by timestamp descending
                return@withContext mergeMediaByDate(
                    imageIds = getDeviceImagesWithDateFilter(fromDate, toDate),
                    videoIds = VideoUtils.scanMediaStoreWithDateFilter(context, fromDate, toDate),
                    fromDate = fromDate,
                    toDate = toDate
                )
            }

            // Semantic/text/name search: images only, no videos
            initJob.await()
            val encoder = textEncoder

            var images = if (names.isEmpty()) {
                mediaDao.getAllMedia()
            } else {
                personDao.getImagesByNames(names, names.size)
            }

            images = filterByDate(images, fromDate, toDate)

            if (images.isEmpty()) return@withContext emptyList()

            if (encoder == null) {
                return@withContext images.sortedByDescending { it.timestampMs }
                    .map { it.mediaId.toMediaUri() }
            }

            val textFeatures =
                withContext(Dispatchers.Default) { encoder.getTextFeatures(cleanPrompt) }

            val cores = Runtime.getRuntime().availableProcessors()
            val chunkSize = images.size / cores + 1

            val sortedImages = withContext(Dispatchers.Default) {
                images.chunked(chunkSize).map { chunk ->
                    async {
                        chunk.map { entity ->
                            entity.mediaId to VectorUtils.dotProduct(textFeatures, entity.embedding)
                        }
                    }
                }.awaitAll().flatten().sortedByDescending { it.second }
            }

            sortedImages.map { it.first.toMediaUri() }
        }
    }

    /**
     * Merges image and video IDs sorted by their MediaStore timestamps (date descending).
     * Used for date-only browsing so videos interleave naturally with images.
     */
    private suspend fun mergeMediaByDate(
        imageIds: List<Long>,
        videoIds: List<Long>,
        fromDate: Long?,
        toDate: Long?
    ): List<Uri> = withContext(Dispatchers.IO) {
        // Build timestamp maps from MediaStore
        val imageEntries = mutableListOf<Pair<Long, Uri>>() // timestamp to uri
        if (imageIds.isNotEmpty()) {
            val clauses = mutableListOf<String>()
            val args = mutableListOf<String>()
            if (fromDate != null) {
                clauses.add("${MediaStore.Images.Media.DATE_ADDED} >= ?")
                args.add((fromDate / 1000).toString())
            }
            if (toDate != null) {
                clauses.add("${MediaStore.Images.Media.DATE_ADDED} <= ?")
                args.add(((toDate + 86400000 - 1) / 1000).toString())
            }
            val selection = if (clauses.isEmpty()) null else clauses.joinToString(" AND ")
            val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED),
                selection, if (args.isEmpty()) null else args.toTypedArray(), sort
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val ts = cursor.getLong(dateCol) * 1000L
                    imageEntries.add(ts to id.toMediaUri())
                }
            }
        }

        val videoEntries = mutableListOf<Pair<Long, Uri>>()
        if (videoIds.isNotEmpty()) {
            val clauses = mutableListOf<String>()
            val args = mutableListOf<String>()
            if (fromDate != null) {
                clauses.add("${MediaStore.Video.Media.DATE_ADDED} >= ?")
                args.add((fromDate / 1000).toString())
            }
            if (toDate != null) {
                clauses.add("${MediaStore.Video.Media.DATE_ADDED} <= ?")
                args.add(((toDate + 86400000 - 1) / 1000).toString())
            }
            val selection = if (clauses.isEmpty()) null else clauses.joinToString(" AND ")
            val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"

            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED),
                selection, if (args.isEmpty()) null else args.toTypedArray(), sort
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val ts = cursor.getLong(dateCol) * 1000L
                    videoEntries.add(ts to id.toVideoUri())
                }
            }
        }

        (imageEntries + videoEntries)
            .sortedByDescending { it.first }
            .map { it.second }
    }

    suspend fun searchWithin(
        mediaIds: List<Long>,
        prompt: String?,
        useClip: Boolean,
        fromDate: Long?,
        toDate: Long?,
        sortMode: SortMode? = SortMode.RELEVANCE
    ): List<Uri> {
        if (prompt.isNullOrBlank()) {
            return withContext(Dispatchers.IO) {
                if (sortMode == null){
                    val originalSet = mediaIds.toSet()
                    val images = getDeviceImagesWithDateFilter(fromDate, toDate)
                    val intersection = images.filter { it in originalSet }
                    return@withContext intersection.map { it.toMediaUri() }
                }
                val images = mediaDao.getMediaDatesByIds(mediaIds)
                val filtered = filterByDateInfo(images, fromDate, toDate)

                if (sortMode == SortMode.DATE_DESC) {
                    filtered.sortedByDescending { it.timestampMs }
                        .map { it.mediaId.toMediaUri() }
                } else {
                    // Maintain original order of mediaIds (important for Category similarity sort)
                    val idToIndex = mediaIds.withIndex().associate { it.value to it.index }
                    filtered.sortedBy { idToIndex[it.mediaId] ?: Int.MAX_VALUE }
                        .map { it.mediaId.toMediaUri() }
                }
            }
        }

        val (names, cleanPrompt) = findNamesInPrompt(prompt)

        return withContext(Dispatchers.IO) {
            // Filter by names first if there are any
            val targetIds = if (names.isEmpty()) {
                mediaIds
            } else {
                val personMediaIds = personDao.getMediaIdsByNames(names, names.size)
                mediaIds.intersect(personMediaIds.toSet()).toList()
            }

            if (targetIds.isEmpty()) return@withContext emptyList()

            val images = mutableListOf<MediaEntity>()
            targetIds.chunked(200).forEach { chunk ->
                images.addAll(mediaDao.getMediaByIds(chunk))
            }
            var filtered = filterByDate(images, fromDate, toDate)

            if (filtered.isEmpty()) return@withContext emptyList()

            if (useClip) {
                initJob.await()
                val encoder = textEncoder
                    ?: return@withContext filtered.sortedByDescending { it.timestampMs }
                        .map { it.mediaId.toMediaUri() }

                // If cleanPrompt is blank but names were found, we just show filtered results sorted by date
                if (cleanPrompt.isBlank() && names.isNotEmpty()) {
                    return@withContext filtered.sortedByDescending { it.timestampMs }
                        .map { it.mediaId.toMediaUri() }
                }

                val textFeatures =
                    withContext(Dispatchers.Default) { encoder.getTextFeatures(cleanPrompt) }

                val cores = Runtime.getRuntime().availableProcessors()
                val chunkSize = filtered.size / cores + 1

                val sorted = withContext(Dispatchers.Default) {
                    filtered.chunked(chunkSize).map { chunk ->
                        async {
                            chunk.map { entity ->
                                entity to VectorUtils.dotProduct(
                                    textFeatures,
                                    entity.embedding
                                )
                            }
                        }
                    }.awaitAll().flatten()
                }

                if (sortMode == SortMode.DATE_DESC) {
                    sorted.sortedByDescending { it.first.timestampMs }
                        .map { it.first.mediaId.toMediaUri() }
                } else {
                    sorted.sortedByDescending { it.second }.map { it.first.mediaId.toMediaUri() }
                }
            } else {
                // OCR search within
                filtered =
                    filtered.filter { it.ocrText?.contains(cleanPrompt, ignoreCase = true) == true }

                if (sortMode == SortMode.DATE_DESC) {
                    filtered.sortedByDescending { it.timestampMs }.map { it.mediaId.toMediaUri() }
                } else {
                    // For OCR without a prompt (though we handled prompt null above), or just default to date
                    filtered.sortedByDescending { it.timestampMs }.map { it.mediaId.toMediaUri() }
                }
            }
        }
    }

    suspend fun searchDocuments(
        text: String,
        fromDate: Long? = null,
        toDate: Long? = null
    ): List<Uri> {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Is FTS supported: $ftsSupported")
            if (text.isBlank()) {
                return@withContext mergeMediaByDate(
                    imageIds = getDeviceImagesWithDateFilter(fromDate, toDate),
                    videoIds = VideoUtils.scanMediaStoreWithDateFilter(context, fromDate, toDate),
                    fromDate = fromDate,
                    toDate = toDate
                )
            }

            var results = if (ftsSupported) {
                mediaDao.searchMediaFts(text)
            } else {
                mediaDao.searchMediaSimple(text)
            }

            // Filter by dates
            results = filterByDate(results, fromDate, toDate)

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
     * Prepares a bulk deletion intent for multiple URIs at once.
     * On Android R+, this shows a single system confirmation dialog for all images.
     */
    suspend fun prepareDeleteImages(uris: List<Uri>): PendingIntent? {
        return withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.createDeleteRequest(context.contentResolver, uris)
            } else {
                null
            }
        }
    }

    /**
     * Finalizes bulk deletion in the DB (and on-device for pre-R Android)
     * by calling finalizeDeleteImage for each URI in the list.
     */
    suspend fun finalizeDeleteImages(uris: List<Uri>) {
        withContext(Dispatchers.IO) {
            // Process in smaller chunks to avoid long-running transactions that block the DB
            val mediaIds = uris.mapNotNull { uri ->
                try {
                    ContentUris.parseId(uri)
                } catch (e: Exception) {
                    null
                }
            }

            mediaIds.chunked(50).forEach { chunk ->
                db.withTransaction {
                    chunk.forEach { mediaId ->
                        deleteImageFromDb(mediaId)
                    }
                }
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                uris.forEach { uri ->
                    try {
                        context.contentResolver.delete(uri, null, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to delete image", e)
                    }
                }
            }
        }
    }

    /**
     * Finalizes deletion in DB and performs deletion on older Android versions.
     */
    @Transaction
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

    suspend fun getDeviceImagesWithDateFilter(fromDate: Long?, toDate: Long?): List<Long> =
        withContext(Dispatchers.IO) {
            val list = mutableListOf<Long>()
            var selection: String? = null
            var selectionArgs: Array<String>? = null

            if (fromDate != null || toDate != null) {
                val clauses = mutableListOf<String>()
                val args = mutableListOf<String>()
                if (fromDate != null) {
                    clauses.add("${MediaStore.Images.Media.DATE_ADDED} >= ?")
                    args.add((fromDate / 1000).toString())
                }
                if (toDate != null) {
                    clauses.add("${MediaStore.Images.Media.DATE_ADDED} <= ?")
                    val adjustedToDate = toDate + 86400000 - 1
                    args.add((adjustedToDate / 1000).toString())
                }
                selection = clauses.joinToString(" AND ")
                selectionArgs = args.toTypedArray()
            }

            val projection = arrayOf(MediaStore.Images.Media._ID)
            val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, sort
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    list.add(cursor.getLong(idIdx))
                }
            }
            list
        }
}