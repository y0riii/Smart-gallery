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
import com.example.gallery.ml.text.TextEncoderProvider
import com.example.gallery.utils.ImageUtils
import com.example.gallery.utils.VideoUtils
import com.example.gallery.utils.VectorUtils
import com.example.gallery.utils.toMediaUri
import com.example.gallery.utils.toVideoUri
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import android.database.ContentObserver
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Central coordinator for all on-device media work. Constructed cheaply in several places
 * (MainActivity + each background worker); shared cross-cutting state (progress, the media cache,
 * the ML execution lock, the run flags) lives in the [companion object] so every instance sees it.
 *
 * Responsibilities are grouped into the sections below, in file order:
 *  - Work scheduling & recluster triggers   (isUriDeleted, startIndexingWorkManager, forceRecluster)
 *  - Companion: shared state                 (progress, media cache, mlExecutionLock, run flags)
 *  - INDEXING                                (indexImagesBackground, processAndInsertImages, deleteImageFromDb)
 *  - FACE CLUSTERING                         (createClusters, cleanupOrphanThumbnails)
 *  - MEDIASTORE QUERIES                      (getAllDeviceMedia*, date-filter helpers, mergeMediaByDate)
 *  - AI CATEGORIES                           (createCategory)
 *  - SEARCH                                  (search, searchWithin, searchDocuments, findNamesInPrompt)
 *  - DELETION                                (prepare/finalize delete, single & bulk)
 *
 * See ARCHITECTURE.md for the end-to-end flows these methods participate in.
 */
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

        // After this many new faces have been assigned incrementally, run a full re-cluster to
        // correct any drift (name-preserving — see runFullClustering). Prevents incremental
        // mistakes from accumulating forever.
        private const val FULL_RECLUSTER_THRESHOLD = 300
        private const val PREF_FACES_SINCE_FULL = "faces_since_full_cluster"

        // Minimum CLIP text↔image similarity for a search hit to count as a "strong" match.
        // Results at/above this appear before the "less relevant" separator; below it, after.
        // Tune to taste — CLIP similarities for good matches typically sit around 0.25–0.35.
        private const val SEARCH_RELEVANCE_THRESHOLD = 0.2f

        // How many images to accumulate before committing them in one DB transaction.
        // Batching amortizes the per-commit fsync cost that dominates indexing on large libraries.
        private const val INDEX_BATCH_SIZE = 24

        val progress = MutableStateFlow<Float?>(null)

        // Process-wide cache of all indexed media (with embeddings), used by semantic search so
        // repeat queries don't re-read and re-deserialize every embedding BLOB from disk. Kept in
        // the companion (not per-instance) so an invalidation from the indexing worker is visible
        // to the GalleryService instance the UI/search uses. Set to null to invalidate.
        @Volatile
        private var cachedMedia: List<MediaEntity>? = null

        fun invalidateMediaCache() {
            cachedMedia = null
        }

        @Volatile
        var isIndexingRunning = false

        @Volatile
        var isClusteringRunning = false

        val mlExecutionLock = kotlinx.coroutines.sync.Mutex()

        @Volatile
        private var lastReclusterTime = 0L
    }

    val db = AppDatabase.getDatabase(context)
    private val mediaDao = db.mediaDao()
    private val personDao = db.personDao()
    private val categoryDao = db.categoryDao()
    private val faceDao = db.faceDao()

    // The shared text encoder + its lazy init live in TextEncoderProvider; these getters just
    // adapt it to this instance's context.
    private val textEncoder: ClipTextEncoder?
        get() = TextEncoderProvider.get(context)

    private val initJob: Deferred<Unit>
        get() = TextEncoderProvider.ensureInitialized(context)

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

    // ═══════════════════════════ INDEXING ═══════════════════════════
    // Keeps Room in sync with the device (add new / drop deleted) and computes each new photo's
    // ML features (CLIP embedding, OCR text, face embeddings). Writes are batched; see §4 of
    // ARCHITECTURE.md for the full pipeline and crash-safety guarantees.

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
        }
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

        // Batched DB writes: committing one transaction per image means one fsync per photo,
        // which dominates indexing time on large libraries. We accumulate up to INDEX_BATCH_SIZE
        // images and commit them together. On cancellation the unflushed buffer is simply dropped
        // and those images get re-indexed next run (indexing is a device-vs-DB diff, so it's safe).
        val mediaBuffer = mutableListOf<MediaEntity>()
        val faceBuffer = mutableListOf<FaceEntity>()
        val crossRefBuffer = mutableListOf<MediaCategoryCrossRef>()

        suspend fun flushBuffers() {
            if (mediaBuffer.isEmpty() && faceBuffer.isEmpty() && crossRefBuffer.isEmpty()) return
            val mediaToInsert = mediaBuffer.toList()
            val facesToInsert = faceBuffer.toList()
            val crossRefsToInsert = crossRefBuffer.toList()
            mediaBuffer.clear(); faceBuffer.clear(); crossRefBuffer.clear()
            withContext(Dispatchers.IO) {
                db.withTransaction {
                    if (mediaToInsert.isNotEmpty()) mediaDao.insertAll(mediaToInsert)
                    if (facesToInsert.isNotEmpty()) faceDao.insertFaces(facesToInsert)
                    if (crossRefsToInsert.isNotEmpty()) categoryDao.insertCrossRefs(crossRefsToInsert)
                }
            }
            invalidateMediaCache()
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

                // Check if we are paused (either manually or from the notification action)
                if (GalleryIndexerWorker.isPaused) {
                    Log.d("GalleryService", "Indexing is paused. Flushing pending writes and releasing ML models...")
                    // Persist what we've processed so a kill during the pause doesn't waste it.
                    flushBuffers()
                    closeProcessors()
                    progress.value = null

                    // Wait loop
                    while (GalleryIndexerWorker.isPaused) {
                        if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                            break
                        }
                        kotlinx.coroutines.delay(1000) // check every 1 second
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

                        // Compute face embeddings + category assignments, then queue for a batched insert.
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

                            // Category auto-assignment (category list is intentionally re-queried
                            // per image so newly created categories are picked up mid-run).
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

                            mediaBuffer.add(MediaEntity(mediaId, timestamp, false, features, text))
                            faceBuffer.addAll(faceEntities)
                            crossRefBuffer.addAll(crossRefs)
                        }

                        if (mediaBuffer.size >= INDEX_BATCH_SIZE) {
                            flushBuffers()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GalleryService", "Error processing image $mediaId", e)
                } finally {
                    // Always advance the counter so the notification reaches 100%
                    // even if the bitmap failed to load or an error was thrown.
                    counter++
                    progress.value = counter / totalCount.toFloat()
                    onProgress(counter, totalCount)
                }
            }
        } finally {
            // Persist whatever is still buffered — even on cancellation — so no completed work is
            // thrown away. NonCancellable guarantees the final commit runs to completion.
            withContext(NonCancellable) {
                try {
                    flushBuffers()
                } catch (e: Exception) {
                    Log.e("GalleryService", "Final buffer flush failed", e)
                }
            }
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

        // The set of indexed media changed — drop the search embedding cache.
        invalidateMediaCache()
    }


    // ═══════════════════════════ FACE CLUSTERING ═══════════════════════════
    // Groups face embeddings into people. First run = full Chinese Whispers; later runs assign
    // new faces to the nearest existing person. Runs under mlExecutionLock so it never overlaps
    // indexing. See §5 of ARCHITECTURE.md.

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
            val facesSinceFull = prefs.getInt(PREF_FACES_SINCE_FULL, 0)
            val allPersons = personDao.getAllPersons()
            // Run a full (re)cluster on the first ever run, when there are no persons yet, or once
            // enough new faces have accumulated that incremental drift should be corrected.
            val shouldRunFirstRun = !firstRunCompleted || allPersons.isEmpty() ||
                facesSinceFull >= FULL_RECLUSTER_THRESHOLD

            if (shouldRunFirstRun) {
                // ── FIRST RUN: Full Chinese Whispers on all faces ──
                Log.d(TAG, "First run: not completed yet (or empty persons), running full Chinese Whispers from scratch")

                // NOTE: we intentionally do NOT wipe persons here. The wipe-and-rebuild is
                // performed atomically in the single transaction below, so that a crash/kill
                // during the (potentially long) Chinese Whispers computation never leaves the
                // People screen empty — the old clustering stays intact until the new one is
                // ready to commit in one shot.
                val facesToCluster = faceDao.getAllFaces()
                if (facesToCluster.isEmpty()) {
                    Log.d(TAG, "No faces to cluster, skipping first run")
                    prefs.edit()
                        .putBoolean("first_clustering_run_completed", true)
                        .putInt(PREF_FACES_SINCE_FULL, 0)
                        .apply()
                    return@withContext true
                }

                val normalizedFaces = facesToCluster.map { VectorUtils.normalize(it.embedding) }
                val clusters = ChineseWhispers.cluster(normalizedFaces)

                // Preserve person identity across the re-cluster: each old person's id + name is
                // carried to the new cluster that inherited the most of that person's faces, so
                // user-assigned names AND favorites (which are keyed by person id) survive. Clusters
                // that don't inherit an identity get fresh ids above the current max.
                val carriedIdentity = computeCarriedIdentity(clusters, facesToCluster)
                val oldNameById = allPersons.associate { it.id to it.name }
                val maxOldId = allPersons.maxOfOrNull { it.id } ?: 0L

                val personsToInsert = mutableListOf<PersonEntity>()
                val faceUpdates = mutableListOf<Pair<Long, Long>>() // faceId to personId

                var freshId = maxOldId

                for ((clusterIndex, cluster) in clusters.withIndex()) {
                    if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                        Log.d(TAG, "First run clustering loop cancelled, aborting.")
                        return@withContext false
                    }

                    if (cluster.isEmpty()) continue

                    // Carry the old person's id + name if this cluster owns one; else a fresh id.
                    val carriedOldId = carriedIdentity[clusterIndex]
                    val personId: Long
                    val personName: String
                    if (carriedOldId != null) {
                        personId = carriedOldId
                        personName = oldNameById[carriedOldId] ?: "#p$carriedOldId"
                    } else {
                        freshId++
                        personId = freshId
                        personName = "#p$personId"
                    }

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

                // Set first-run flag + reset the drift counter ONLY after successful commit.
                prefs.edit()
                    .putBoolean("first_clustering_run_completed", true)
                    .putInt(PREF_FACES_SINCE_FULL, 0)
                    .apply()
                Log.d(TAG, "Full cluster complete: ${personsToInsert.size} persons created and ${faceUpdates.size} faces assigned.")
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

                    // We need a local cache of person entities to update them as we iterate,
                    // since multiple new faces might be assigned to the same person.
                    val personCache = allPersons.associateBy { it.id }.toMutableMap()
                    val maxId = personCache.keys.maxOrNull() ?: 0L
                    var currentPersonCount = maxId

                    // Per-person normalized member-face embeddings. New faces are matched against
                    // these ACTUAL members (nearest-neighbour), not a single drifting centroid.
                    // Grown in-loop so faces assigned earlier in this run are matchable by later ones.
                    val memberEmbeddings = HashMap<Long, MutableList<FloatArray>>()
                    for (f in allFaces) {
                        val pid = f.personId ?: continue
                        memberEmbeddings.getOrPut(pid) { mutableListOf() }
                            .add(VectorUtils.normalize(f.embedding))
                    }

                    var assignedCount = 0
                    var newPersonCount = 0

                    for (face in newFaces) {
                        if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                            Log.d(TAG, "Incremental clustering loop cancelled, aborting.")
                            return@withContext false
                        }

                        val normEmb = VectorUtils.normalize(face.embedding)
                        val boxArea = (face.boxRight - face.boxLeft) * (face.boxBottom - face.boxTop)

                        // Nearest-neighbour match: score each person by the MEAN of its top-3
                        // member-face similarities to this face. Robust to a single lucky match and
                        // to centroid drift. Uses the same face↔face cutoff as Chinese Whispers.
                        var bestPersonId: Long? = null
                        var bestScore = Float.NEGATIVE_INFINITY
                        for ((pid, embs) in memberEmbeddings) {
                            var s1 = Float.NEGATIVE_INFINITY
                            var s2 = Float.NEGATIVE_INFINITY
                            var s3 = Float.NEGATIVE_INFINITY
                            for (e in embs) {
                                val sim = VectorUtils.dotProduct(normEmb, e)
                                when {
                                    sim > s1 -> { s3 = s2; s2 = s1; s1 = sim }
                                    sim > s2 -> { s3 = s2; s2 = sim }
                                    sim > s3 -> { s3 = sim }
                                }
                            }
                            val top = listOf(s1, s2, s3).filter { it.isFinite() }
                            if (top.isEmpty()) continue
                            val score = top.sum() / top.size
                            if (score > bestScore) {
                                bestScore = score
                                bestPersonId = pid
                            }
                        }

                        val personId: Long
                        if (bestPersonId == null || bestScore < ChineseWhispers.EDGE_THRESHOLD) {
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
                            memberEmbeddings[personId] = mutableListOf(normEmb)
                            personsToInsert.add(newPerson)
                            newPersonCount++
                            Log.d(TAG, "Prepared new person '#p$personId' (id=$personId) for face ${face.faceId}")
                        } else {
                            // Assign to the matched existing person
                            val bestMatch = personCache.getValue(bestPersonId)
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
                            memberEmbeddings.getValue(personId).add(normEmb)

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

                    // Track how many new faces have been assigned incrementally since the last full
                    // cluster, so drift gets corrected by a full re-cluster once it crosses the limit.
                    prefs.edit()
                        .putInt(PREF_FACES_SINCE_FULL, facesSinceFull + newFaces.size)
                        .apply()

                    Log.d(TAG, "Incremental run complete: $assignedCount assigned to existing, $newPersonCount new persons created")
                }
            }

            // Delete thumbnail files left behind by re-clustering or interrupted runs
            // (files written to disk whose person row no longer references them).
            cleanupOrphanThumbnails()
            true
        }
    }

    /**
     * Maps a cluster index → the old personId whose identity (id + name) it should inherit, so a
     * full re-cluster keeps user-assigned names and favorites (both keyed by person id). Each old
     * person is inherited by the single new cluster that got the most of that person's faces;
     * clusters that don't win any old identity are left out (the caller assigns them fresh ids).
     *
     * @param clusters CW output — lists of indices into [faces]
     * @param faces    the faces that were clustered (each still carries its previous personId)
     */
    private fun computeCarriedIdentity(
        clusters: List<List<Int>>,
        faces: List<FaceEntity>
    ): Map<Int, Long> {
        // For each cluster, which old person contributed the most of its faces, and how many.
        val majorityOldId = HashMap<Int, Long>()
        val majorityCount = HashMap<Int, Int>()
        clusters.forEachIndexed { i, cluster ->
            val counts = HashMap<Long, Int>()
            for (idx in cluster) {
                val oldId = faces[idx].personId ?: continue
                counts[oldId] = (counts[oldId] ?: 0) + 1
            }
            val best = counts.maxByOrNull { it.value } ?: return@forEachIndexed
            majorityOldId[i] = best.key
            majorityCount[i] = best.value
        }

        // Each old id is inherited by the cluster that holds the most of its faces.
        val winningClusterForOldId = HashMap<Long, Int>()
        majorityOldId.forEach { (clusterIdx, oldId) ->
            val current = winningClusterForOldId[oldId]
            if (current == null || (majorityCount[clusterIdx] ?: 0) > (majorityCount[current] ?: 0)) {
                winningClusterForOldId[oldId] = clusterIdx
            }
        }

        val result = HashMap<Int, Long>()
        winningClusterForOldId.forEach { (oldId, clusterIdx) -> result[clusterIdx] = oldId }
        return result
    }

    /**
     * Deletes person-thumbnail image files in filesDir that are no longer referenced by any
     * [PersonEntity]. These accumulate when clustering re-runs (old thumbnails replaced) or when
     * a clustering pass is interrupted after writing a thumbnail but before committing its person.
     */
    private suspend fun cleanupOrphanThumbnails() = withContext(Dispatchers.IO) {
        try {
            val referenced = personDao.getAllPersons()
                .mapNotNull { it.thumbnailPath.takeIf { path -> path.isNotEmpty() } }
                .toSet()
            context.filesDir
                .listFiles { file -> file.isFile && file.name.startsWith("thumb_") && file.name.endsWith(".jpg") }
                ?.forEach { file ->
                    if (file.absolutePath !in referenced && file.delete()) {
                        Log.d(TAG, "Deleted orphan thumbnail ${file.name}")
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Orphan thumbnail cleanup failed", e)
        }
    }

    // ═══════════════════════════ MEDIASTORE QUERIES ═══════════════════════════
    // Direct reads of the device's photo/video store (no Room). Used to browse "all media" and
    // to resolve date-filtered id lists that search/albums then refine.

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

    /** Same as [getAllDeviceMedia] but also returns the timestamp (ms) for each URI. */
    suspend fun getAllDeviceMediaWithTimestamps(): List<Pair<Uri, Long>> =
        withContext(Dispatchers.IO) {
            val images = ImageUtils.scanMediaStore(context).map { (id, ts) -> id.toMediaUri() to ts }
            val videos = VideoUtils.scanMediaStore(context).map { (id, ts) -> id.toVideoUri() to ts }
            (images + videos).sortedByDescending { it.second }
        }

    fun getAllDeviceMediaFlow(): Flow<List<Pair<Uri, Long>>> = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                launch {
                    trySend(getAllDeviceMediaWithTimestamps())
                }
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
        )
        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
        )

        // Load initial state
        trySend(getAllDeviceMediaWithTimestamps())

        awaitClose {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }.flowOn(Dispatchers.IO)

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
            val allImages = getAllMediaCached()

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

    /**
     * Returns all indexed media (with embeddings), served from an in-memory cache so repeat
     * semantic searches don't re-read and re-deserialize every embedding BLOB from disk.
     * The cache is invalidated by [invalidateMediaCache] whenever media is inserted or deleted.
     */
    private suspend fun getAllMediaCached(): List<MediaEntity> {
        cachedMedia?.let { return it }
        return mediaDao.getAllMedia().also { cachedMedia = it }
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

    // ═══════════════════════════ SEARCH ═══════════════════════════
    // Three entry points: semantic CLIP search (search / searchWithin), OCR document search
    // (searchDocuments), and date-only browsing. @name mentions are resolved by findNamesInPrompt.

    suspend fun search(prompt: String, fromDate: Long? = null, toDate: Long? = null): SearchResult {
        return withContext(Dispatchers.IO) {
            val (names, cleanPrompt) = findNamesInPrompt(prompt)

            // Date-only search: no text, no names — include videos merged with images.
            // No similarity scores here, so the whole list is "relevant" (no separator).
            if (cleanPrompt.isBlank() && names.isEmpty()) {
                return@withContext SearchResult.all(
                    mergeMediaByDate(
                        imageIds = getDeviceImagesWithDateFilter(fromDate, toDate),
                        videoIds = VideoUtils.scanMediaStoreWithDateFilter(context, fromDate, toDate),
                        fromDate = fromDate,
                        toDate = toDate
                    )
                )
            }

            // Semantic/text/name search: images only, no videos
            initJob.await()
            val encoder = textEncoder

            var images = if (names.isEmpty()) {
                getAllMediaCached()
            } else {
                personDao.getImagesByNames(names, names.size)
            }

            images = filterByDate(images, fromDate, toDate)

            if (images.isEmpty()) return@withContext SearchResult.all(emptyList())

            if (encoder == null) {
                // No model → fall back to date order; nothing to score against, so no separator.
                return@withContext SearchResult.all(
                    images.sortedByDescending { it.timestampMs }.map { it.mediaId.toMediaUri() }
                )
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

            // Sorted best-first, so the strong matches are the leading run above the threshold.
            val relevantCount = sortedImages.count { it.second >= SEARCH_RELEVANCE_THRESHOLD }
            SearchResult(sortedImages.map { it.first.toMediaUri() }, relevantCount)
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
    ): SearchResult {
        if (prompt.isNullOrBlank()) {
            return withContext(Dispatchers.IO) {
                if (sortMode == null){
                    val originalSet = mediaIds.toSet()
                    val images = getDeviceImagesWithDateFilter(fromDate, toDate)
                    val intersection = images.filter { it in originalSet }
                    return@withContext SearchResult.all(intersection.map { it.toMediaUri() })
                }
                val images = mediaDao.getMediaDatesByIds(mediaIds)
                val filtered = filterByDateInfo(images, fromDate, toDate)

                if (sortMode == SortMode.DATE_DESC) {
                    SearchResult.all(
                        filtered.sortedByDescending { it.timestampMs }.map { it.mediaId.toMediaUri() }
                    )
                } else {
                    // Maintain original order of mediaIds (important for Category similarity sort)
                    val idToIndex = mediaIds.withIndex().associate { it.value to it.index }
                    SearchResult.all(
                        filtered.sortedBy { idToIndex[it.mediaId] ?: Int.MAX_VALUE }
                            .map { it.mediaId.toMediaUri() }
                    )
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

            if (targetIds.isEmpty()) return@withContext SearchResult.all(emptyList())

            val images = mutableListOf<MediaEntity>()
            targetIds.chunked(200).forEach { chunk ->
                images.addAll(mediaDao.getMediaByIds(chunk))
            }
            var filtered = filterByDate(images, fromDate, toDate)

            if (filtered.isEmpty()) return@withContext SearchResult.all(emptyList())

            if (useClip) {
                initJob.await()
                val encoder = textEncoder
                    ?: return@withContext SearchResult.all(
                        filtered.sortedByDescending { it.timestampMs }.map { it.mediaId.toMediaUri() }
                    )

                // If cleanPrompt is blank but names were found, we just show filtered results sorted by date
                if (cleanPrompt.isBlank() && names.isNotEmpty()) {
                    return@withContext SearchResult.all(
                        filtered.sortedByDescending { it.timestampMs }.map { it.mediaId.toMediaUri() }
                    )
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
                    // Date order: no relevance split.
                    SearchResult.all(
                        sorted.sortedByDescending { it.first.timestampMs }
                            .map { it.first.mediaId.toMediaUri() }
                    )
                } else {
                    // Relevance order: split strong matches from weak ones at the threshold.
                    val ranked = sorted.sortedByDescending { it.second }
                    val relevantCount = ranked.count { it.second >= SEARCH_RELEVANCE_THRESHOLD }
                    SearchResult(ranked.map { it.first.mediaId.toMediaUri() }, relevantCount)
                }
            } else {
                // OCR search within — substring match, date-ordered, no similarity score.
                filtered =
                    filtered.filter { it.ocrText?.contains(cleanPrompt, ignoreCase = true) == true }
                SearchResult.all(
                    filtered.sortedByDescending { it.timestampMs }.map { it.mediaId.toMediaUri() }
                )
            }
        }
    }

    suspend fun searchDocuments(
        text: String,
        fromDate: Long? = null,
        toDate: Long? = null
    ): SearchResult {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Is FTS supported: $ftsSupported")
            // OCR/document search is date-ordered text matching — no similarity split.
            if (text.isBlank()) {
                return@withContext SearchResult.all(
                    mergeMediaByDate(
                        imageIds = getDeviceImagesWithDateFilter(fromDate, toDate),
                        videoIds = VideoUtils.scanMediaStoreWithDateFilter(context, fromDate, toDate),
                        fromDate = fromDate,
                        toDate = toDate
                    )
                )
            }

            var results = if (ftsSupported) {
                mediaDao.searchMediaFts(text)
            } else {
                mediaDao.searchMediaSimple(text)
            }

            // Filter by dates
            results = filterByDate(results, fromDate, toDate)

            SearchResult.all(results.map { it.mediaId.toMediaUri() })
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

    // ═══════════════════════════ DELETION ═══════════════════════════
    // Two phases: prepare* builds the MediaStore delete request (system confirmation dialog on
    // R+), then finalize* removes the rows from Room. Cleans up faces/persons via cascade + the
    // person-count adjustments in deleteImageFromDb. See §9 of ARCHITECTURE.md.

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