package com.example.gallery.ml.face

import android.content.Context
import android.graphics.Rect
import android.util.Log
import androidx.room.withTransaction
import com.example.gallery.GalleryService
import com.example.gallery.db.AppDatabase
import com.example.gallery.db.entities.FaceEntity
import com.example.gallery.db.entities.PersonEntity
import com.example.gallery.utils.ImageUtils
import com.example.gallery.utils.VectorUtils
import com.example.gallery.utils.toMediaUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Owns all face-clustering logic (extracted from GalleryService so that god object stays focused
 * on coordination). Groups face embeddings into people:
 *
 * - First run / periodic re-cluster: full Chinese Whispers over every face, identity-preserving.
 * - Subsequent runs: assign only the new (unassigned) faces to the nearest existing person via
 *   top-3 member-face similarity, or spin up a new singleton person.
 *
 * Stateless apart from its injected dependencies; the run flags / ML lock stay in GalleryService,
 * which serializes calls to [createClusters] so this never overlaps indexing. See §5 of
 * ARCHITECTURE.md.
 */
class FaceClusterer(
    private val context: Context,
    private val db: AppDatabase
) {
    private val faceDao = db.faceDao()
    private val personDao = db.personDao()

    companion object {
        private const val TAG = "FaceClusterer"

        // After this many new faces have been assigned incrementally, run a full re-cluster to
        // correct any drift (name-preserving). Prevents incremental mistakes from accumulating.
        private const val FULL_RECLUSTER_THRESHOLD = 150
        private const val PREF_FACES_SINCE_FULL = "faces_since_full_cluster"
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
            val facesSinceFull = prefs.getInt(PREF_FACES_SINCE_FULL, 0)
            // Periodic drift-correcting re-cluster is user-toggleable (default ON). When off, only
            // the first run / empty-persons cases still force a full cluster.
            val autoReclusterEnabled = prefs.getBoolean(GalleryService.PREF_AUTO_RECLUSTER, true)
            val allPersons = personDao.getAllPersons()
            // Run a full (re)cluster on the first ever run, when there are no persons yet, or once
            // enough new faces have accumulated that incremental drift should be corrected.
            val shouldRunFirstRun = !firstRunCompleted || allPersons.isEmpty() ||
                (autoReclusterEnabled && facesSinceFull >= FULL_RECLUSTER_THRESHOLD)

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
}
