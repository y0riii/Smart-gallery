package com.example.gallery.db

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.gallery.R
import com.example.gallery.db.entities.MediaPersonCrossRef
import com.example.gallery.db.entities.PersonEntity
import com.example.gallery.ml.face.ChineseWhispers
import com.example.gallery.utils.VectorUtils

class FaceClusteringWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "FaceClusteringWorker"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "face_clustering_channel"
        private const val ASSIGN_THRESHOLD = 0.50f
    }

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo())

        return try {
            val db = AppDatabase.getDatabase(applicationContext)
            val faceDao = db.faceDao()
            val personDao = db.personDao()

            // 1. Load all face entities
            val allFaces = faceDao.getAllFaces()
            Log.d(TAG, "Loaded ${allFaces.size} faces for clustering")

            if (allFaces.isEmpty()) {
                Log.d(TAG, "No faces to cluster, skipping")
                return Result.success()
            }

            val newFaces = allFaces.filter{ it.personId == null }

            if (newFaces.isEmpty()){
                Log.d(TAG, "No new faces have been added")
                return Result.success()
            }

            // 2. Identify preserved persons (user-named, non-placeholder)
            val allPersons = personDao.getAllPersons()
            val placeholderRegex = Regex("#p\\d+")
            val preservedPersons = allPersons
                .filter { it.name != null && !it.name.matches(placeholderRegex) }

            if (preservedPersons.isEmpty()) {
                // Delete any existing persons before full re-clustering
                for (person in allPersons) {
                    personDao.deletePerson(person.id)
                }
                faceDao.clearAllPersonAssignments()

                val normalizedFaces = allFaces.map { VectorUtils.normalize(it.embedding) }
                val clusters = ChineseWhispers.cluster(normalizedFaces)
                var personCounter = 0

                for (cluster in clusters) {
                    personCounter++

                    val bestFaceIdx = cluster.maxByOrNull { allFaces[it].thumbnailSize } ?: continue
                    val bestFace = allFaces[bestFaceIdx]
                    val personName = "#p$personCounter"

                    // Compute average embedding for this new placeholder cluster
                    val dim = allFaces.first().embedding.size
                    val sum = FloatArray(dim)
                    for (faceIdx in cluster) {
                        val norm = normalizedFaces[faceIdx]
                        for (d in norm.indices) sum[d] += norm[d]
                    }
//                    val clusterAvg = VectorUtils.normalize(
//                        VectorUtils.divide(sum, cluster.size.toFloat())
//                    )

                    val personId = personDao.insertPerson(
                        PersonEntity(
                            name = personName,
                            thumbnailPath = bestFace.thumbnailPath,
                            thumbnailSize = bestFace.thumbnailSize,
                            Embedding = sum,
                            count = cluster.size
                        )
                    )

                    val processedMediaIds = mutableSetOf<Long>()
                    for (faceIdx in cluster) {
                        val face = allFaces[faceIdx]
                        faceDao.updateFacePersonId(face.faceId, personId)
                        if (processedMediaIds.add(face.mediaId)) {
                            personDao.insertCrossRef(
                                MediaPersonCrossRef(mediaId = face.mediaId, personId = personId)
                            )
                        }
                    }


                }
                return Result.success()
            }

            val preservedPersonIds = preservedPersons.map { it.id }.toSet()
            Log.d(TAG, "Preserved persons (user-named): ${preservedPersonIds.size}")

            // 3. Store average embeddings for each preserved person
            //    Map: personId -> (averageEmbedding, faceCount)
            val preservedCentroids = mutableMapOf<Long, Pair<FloatArray, Int>>()
            for (person in preservedPersons) {
                val personFaces = allFaces.filter { it.personId == person.id }
                if (personFaces.isEmpty()) continue

//                 Sum all embeddings, normalize to get centroid
//                val dim = personFaces.first().embedding.size
//                val sum = FloatArray(dim)
//                for (face in personFaces) {
//                    val norm = VectorUtils.normalize(face.embedding)
//                    for (i in norm.indices) sum[i] += norm[i]
//                }
                val avg = VectorUtils.normalize(
                    VectorUtils.divide(person.Embedding, person.count.toFloat())
                )
                preservedCentroids[person.id] = avg to person.count

//                // Persist the average embedding
//                personDao.updateAverageEmbedding(person.id, avg)
            }
            Log.d(TAG, "Computed average embeddings for ${preservedCentroids.size} preserved persons")

            // 4. Partition faces: preserved faces stay, the rest are candidates
            val candidateFaces = allFaces.filter { it.personId !in preservedPersonIds }
            Log.d(TAG, "Candidate faces: ${candidateFaces.size}")
            Log.d(TAG, "Candidates except new faces: ${candidateFaces.size - newFaces.size}")

            if (candidateFaces.isEmpty()) {
                Log.d(TAG, "No unassigned or placeholder faces, skipping")
                return Result.success()
            }

            // 5. Auto-assign phase: compare each new face against preserved centroids
            val autoAssigned = mutableListOf<Int>()
            val leftoverIndices = mutableListOf<Int>()

            for (i in candidateFaces.indices) {
                val face = candidateFaces[i]
                if (face.personId != null){
                    leftoverIndices.add(i)
                    continue
                }
                val normEmb = VectorUtils.normalize(face.embedding)

                var bestPersonId: Long? = null
                var bestSimilarity = ASSIGN_THRESHOLD

                for ((personId, centroidPair) in preservedCentroids) {
                    val similarity = VectorUtils.dotProduct(normEmb, centroidPair.first)
                    if (similarity > bestSimilarity) {
                        bestSimilarity = similarity
                        bestPersonId = personId
                    }
                }

                if (bestPersonId != null) {
                    // Assign this face to the preserved person
                    faceDao.updateFacePersonId(face.faceId, bestPersonId)
                    personDao.insertCrossRef(
                        MediaPersonCrossRef(mediaId = face.mediaId, personId = bestPersonId)
                    )
                    autoAssigned.add(i)

                    // Update the centroid incrementally: newAvg = normalize((oldAvg * count + newEmb) / (count + 1))
                    val (oldAvg, oldCount) = preservedCentroids[bestPersonId]!!
                    val newCount = oldCount + 1
                    val dim = oldAvg.size
                    val newSum = FloatArray(dim)
                    for (d in 0 until dim) {
                        newSum[d] = oldAvg[d] * oldCount + normEmb[d]
                    }
                    val newAvg = VectorUtils.normalize(
                        VectorUtils.divide(newSum, newCount.toFloat())
                    )
                    preservedCentroids[bestPersonId] = newAvg to newCount
                    personDao.updateEmbedding(bestPersonId, newSum)
                    personDao.incrementPersonCounter(bestPersonId)

                    Log.d(TAG, "Auto-assigned face ${face.faceId} to person $bestPersonId (similarity=$bestSimilarity)")
                } else {
                    leftoverIndices.add(i)
                }
            }

            Log.d(TAG, "Auto-assigned ${autoAssigned.size} faces, ${leftoverIndices.size} leftovers for clustering")

            // 6. Delete old placeholder persons and their data
            val placeholderPersonIds = allPersons
                .filter { it.name == null || it.name.matches(placeholderRegex) }
                .map { it.id }

            for (pid in placeholderPersonIds) {
                personDao.deletePerson(pid)  // CASCADE deletes cross-refs too
            }
//             Clear person assignments for leftover faces
            for (idx in leftoverIndices) {
                val face = candidateFaces[idx]
                if (face.personId != null) {
                    faceDao.updateFacePersonId(face.faceId, 0)
                }
            }

            // 7. Cluster leftover faces with Chinese Whispers
            if (leftoverIndices.isEmpty()) {
                Log.d(TAG, "No leftover faces to cluster")
            } else {
                val leftoverFaces = leftoverIndices.map { candidateFaces[it] }
                val normalizedLeftovers = leftoverFaces.map { VectorUtils.normalize(it.embedding) }

                val clusters = ChineseWhispers.cluster(normalizedLeftovers)
                Log.d(TAG, "Chinese Whispers produced ${clusters.size} clusters from ${leftoverFaces.size} faces")

                // Determine starting counter to avoid name collisions
//                val existingPlaceholderNumbers = allPersons
//                    .mapNotNull { p ->
//                        p.name?.let {
//                            placeholderRegex.matchEntire(it)?.let { it.substring(2).toIntOrNull() }
//                        }
//                    }
                var personCounter = 0

                for (cluster in clusters) {
                    personCounter++

                    val bestFaceIdx = cluster.maxByOrNull { leftoverFaces[it].thumbnailSize } ?: continue
                    val bestFace = leftoverFaces[bestFaceIdx]
                    val personName = "#p$personCounter"

                    // Compute average embedding for this new placeholder cluster
                    val dim = leftoverFaces.first().embedding.size
                    val sum = FloatArray(dim)
                    for (faceIdx in cluster) {
                        val norm = normalizedLeftovers[faceIdx]
                        for (d in norm.indices) sum[d] += norm[d]
                    }
//                    val clusterAvg = VectorUtils.normalize(
//                        VectorUtils.divide(sum, cluster.size.toFloat())
//                    )

                    val personId = personDao.insertPerson(
                        PersonEntity(
                            name = personName,
                            thumbnailPath = bestFace.thumbnailPath,
                            thumbnailSize = bestFace.thumbnailSize,
                            Embedding = sum,
                            count = cluster.size
                        )
                    )

                    val processedMediaIds = mutableSetOf<Long>()
                    for (faceIdx in cluster) {
                        val face = leftoverFaces[faceIdx]
                        faceDao.updateFacePersonId(face.faceId, personId)
                        if (processedMediaIds.add(face.mediaId)) {
                            personDao.insertCrossRef(
                                MediaPersonCrossRef(mediaId = face.mediaId, personId = personId)
                            )
                        }
                    }

                    Log.d(TAG, "Placeholder '$personName' (id=$personId): ${cluster.size} faces, ${processedMediaIds.size} images")
                }
            }

            val preservedFaceCount = allFaces.size - candidateFaces.size
            Log.d(TAG, "Clustering complete: ${autoAssigned.size} auto-assigned, ${leftoverIndices.size} clustered, $preservedFaceCount preserved")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Clustering failed", e)
            Result.retry()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = buildNotification()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Smart Gallery")
            .setContentText("Clustering faces…")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Face Clustering",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while Smart Gallery clusters detected faces."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
