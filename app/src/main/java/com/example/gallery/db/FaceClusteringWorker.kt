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

            // 2. Normalize embeddings for cosine similarity
            val normalizedEmbeddings = allFaces.map { VectorUtils.normalize(it.embedding) }

            // 3. Run Chinese Whispers clustering
            val clusters = ChineseWhispers.cluster(normalizedEmbeddings)
            Log.d(TAG, "Chinese Whispers produced ${clusters.size} clusters")

            // 4. Save previous person names for preservation
            val oldPersons = personDao.getAllPersons()
            val oldPersonNames = oldPersons.associate { it.id to it.name }

            // Build a map from old personId -> set of mediaIds for overlap matching
            val oldPersonMediaMap = mutableMapOf<Long, MutableSet<Long>>()
            for (face in allFaces) {
                val pid = face.personId ?: continue
                oldPersonMediaMap.getOrPut(pid) { mutableSetOf() }.add(face.mediaId)
            }

            // 5. Clear old person data
            personDao.deleteAllCrossRefs()
            personDao.deleteAllPersons()
            faceDao.clearAllPersonAssignments()

            // 6. Create new persons and cross-refs for each cluster
            var personCounter = 0
            for (cluster in clusters) {
                personCounter++

                // Find the face with the largest thumbnail in this cluster (best quality)
                val bestFaceIdx = cluster.maxByOrNull { allFaces[it].thumbnailSize } ?: continue
                val bestFace = allFaces[bestFaceIdx]

                // Try to preserve user-assigned names by matching cluster overlap
                val clusterMediaIds = cluster.map { allFaces[it].mediaId }.toSet()
                var preservedName: String? = null
                var bestOverlap = 0
                for ((oldPid, oldMediaIds) in oldPersonMediaMap) {
                    val overlap = clusterMediaIds.intersect(oldMediaIds).size
                    if (overlap > bestOverlap) {
                        bestOverlap = overlap
                        val name = oldPersonNames[oldPid]
                        // Only preserve non-placeholder names (not "#p123")
                        if (name != null && !name.matches(Regex("#p\\d+"))) {
                            preservedName = name
                        }
                    }
                }

                val personName = preservedName ?: "#p$personCounter"

                // Insert the new person
                val personId = personDao.insertPerson(
                    PersonEntity(
                        name = personName,
                        thumbnailPath = bestFace.thumbnailPath,
                        thumbnailSize = bestFace.thumbnailSize
                    )
                )

                // Update face assignments and create cross-refs
                val processedMediaIds = mutableSetOf<Long>()
                for (faceIdx in cluster) {
                    val face = allFaces[faceIdx]
                    faceDao.updateFacePersonId(face.faceId, personId)

                    // De-duplicate cross-refs: one per (mediaId, personId)
                    if (processedMediaIds.add(face.mediaId)) {
                        personDao.insertCrossRef(
                            MediaPersonCrossRef(
                                mediaId = face.mediaId,
                                personId = personId
                            )
                        )
                    }
                }

                Log.d(TAG, "Person '$personName' (id=$personId): ${cluster.size} faces, ${processedMediaIds.size} images")
            }

            Log.d(TAG, "Clustering complete: $personCounter persons created")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Clustering failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
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
