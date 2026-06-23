package com.example.gallery

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gallery.db.AppDatabase
import com.example.gallery.utils.VectorUtils
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

/**
 * Standalone instrumented test that evaluates Chinese Whispers clustering quality.
 *
 * For every face embedding, the silhouette score is computed:
 *   s(i) = (b(i) - a(i)) / max(a(i), b(i))
 *
 * where:
 *   a(i) = mean intra-cluster distance (avg cosine distance to other faces in the same cluster)
 *   b(i) = min mean inter-cluster distance (smallest avg cosine distance to faces of any *other* cluster)
 *
 * Cosine distance = 1 - cosine_similarity (on normalized embeddings).
 *
 * Per-cluster statistics: Min, Max, Mean, Std of the silhouette scores of all faces in that cluster.
 *
 * Run with:
 *   ./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.gallery.ClusterEvaluationTest
 *
 * Output: formatted table in logcat (tag: ClusterEval) + CSV file on device.
 */
@RunWith(AndroidJUnit4::class)
class ClusterEvaluationTest {

    companion object {
        private const val TAG = "ClusterEval"
    }

    @Test
    fun evaluateClusters() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getDatabase(context)
        val faceDao = db.faceDao()
        val personDao = db.personDao()

        // ── 1. Load all data ───────────────────────────────────────────────
        val allFaces = faceDao.getAllFaces()
        val allPersons = personDao.getAllPersons()

        Log.d(TAG, "Total faces: ${allFaces.size}")
        Log.d(TAG, "Total persons (clusters): ${allPersons.size}")

        // Build a map: personId -> list of normalized embeddings
        val clusterMap = mutableMapOf<Long, MutableList<FloatArray>>()
        for (face in allFaces) {
            val pid = face.personId ?: continue // skip unassigned faces
            val normalized = VectorUtils.normalize(face.embedding)
            clusterMap.getOrPut(pid) { mutableListOf() }.add(normalized)
        }

        // Build person name lookup
        val personNameMap = allPersons.associate { it.id to (it.name ?: "unknown") }

        val clusterIds = clusterMap.keys.toList()
        Log.d(TAG, "Clusters with faces: ${clusterIds.size}")

        if (clusterIds.isEmpty()) {
            Log.w(TAG, "No clusters found. Aborting evaluation.")
            return@runBlocking
        }

        // ── 2. Compute silhouette scores ───────────────────────────────────
        // For each face, compute a(i) and b(i)

        // Pre-compute all embeddings in order, grouped by cluster
        data class FaceInfo(
            val clusterId: Long,
            val embedding: FloatArray,
            val indexInCluster: Int
        )

        val allClusterFaces = mutableListOf<FaceInfo>()
        val clusterEmbeddings = mutableMapOf<Long, List<FloatArray>>()

        for (cid in clusterIds) {
            val embeddings = clusterMap[cid]!!
            clusterEmbeddings[cid] = embeddings
            for ((idx, emb) in embeddings.withIndex()) {
                allClusterFaces.add(FaceInfo(cid, emb, idx))
            }
        }

        // For efficiency, pre-compute mean distances from each face to each cluster
        // silhouetteScores[clusterId] = list of scores for faces in that cluster
        val silhouetteScores = mutableMapOf<Long, MutableList<Double>>()

        for (cid in clusterIds) {
            silhouetteScores[cid] = mutableListOf()
        }

        for (faceInfo in allClusterFaces) {
            val myCluster = faceInfo.clusterId
            val myEmb = faceInfo.embedding
            val myClusterEmbeddings = clusterEmbeddings[myCluster]!!

            // a(i): mean intra-cluster distance
            val ai: Double
            if (myClusterEmbeddings.size <= 1) {
                // Singleton cluster: a(i) = 0 by convention
                ai = 0.0
            } else {
                var sumDist = 0.0
                var count = 0
                for ((idx, otherEmb) in myClusterEmbeddings.withIndex()) {
                    if (idx == faceInfo.indexInCluster) continue
                    val cosineSim = VectorUtils.dotProduct(myEmb, otherEmb).toDouble()
                    val cosineDist = 1.0 - cosineSim
                    sumDist += cosineDist
                    count++
                }
                ai = sumDist / count
            }

            // b(i): min mean inter-cluster distance
            var bi = Double.MAX_VALUE
            for (otherId in clusterIds) {
                if (otherId == myCluster) continue
                val otherEmbeddings = clusterEmbeddings[otherId]!!
                var sumDist = 0.0
                for (otherEmb in otherEmbeddings) {
                    val cosineSim = VectorUtils.dotProduct(myEmb, otherEmb).toDouble()
                    val cosineDist = 1.0 - cosineSim
                    sumDist += cosineDist
                }
                val meanDist = sumDist / otherEmbeddings.size
                if (meanDist < bi) {
                    bi = meanDist
                }
            }

            // If only one cluster exists, b(i) is undefined; set silhouette = 0
            if (bi == Double.MAX_VALUE) {
                bi = 0.0
            }

            val si = if (ai == 0.0 && bi == 0.0) {
                0.0
            } else {
                (bi - ai) / maxOf(ai, bi)
            }

            silhouetteScores[myCluster]!!.add(si)
        }

        // ── 3. Aggregate per-cluster statistics ────────────────────────────
        data class ClusterStats(
            val clusterId: Long,
            val personName: String,
            val faceCount: Int,
            val minSilhouette: Double,
            val maxSilhouette: Double,
            val meanSilhouette: Double,
            val stdSilhouette: Double
        )

        val statsTable = mutableListOf<ClusterStats>()

        for (cid in clusterIds) {
            val scores = silhouetteScores[cid]!!
            if (scores.isEmpty()) continue

            val min = scores.min()
            val max = scores.max()
            val mean = scores.average()
            val variance = scores.map { (it - mean) * (it - mean) }.average()
            val std = sqrt(variance)

            statsTable.add(
                ClusterStats(
                    clusterId = cid,
                    personName = personNameMap[cid] ?: "unknown",
                    faceCount = scores.size,
                    minSilhouette = min,
                    maxSilhouette = max,
                    meanSilhouette = mean,
                    stdSilhouette = std
                )
            )
        }

        // Sort by mean silhouette descending (best clusters first)
        statsTable.sortByDescending { it.meanSilhouette }

        // ── 4. Compute global silhouette score ─────────────────────────────
        val allScores = silhouetteScores.values.flatten()
        val globalMean = if (allScores.isNotEmpty()) allScores.average() else 0.0
        val globalVariance = if (allScores.isNotEmpty()) allScores.map { (it - globalMean) * (it - globalMean) }.average() else 0.0
        val globalStd = sqrt(globalVariance)

        // ── 5. Print formatted table ───────────────────────────────────────
        val separator = "─".repeat(100)
        val headerFormat = "%-8s │ %-14s │ %6s │ %10s │ %10s │ %10s │ %10s"
        val rowFormat    = "%-8d │ %-14s │ %6d │ %10.4f │ %10.4f │ %10.4f │ %10.4f"

        Log.d(TAG, "")
        Log.d(TAG, "╔${"═".repeat(100)}╗")
        Log.d(TAG, "║  CLUSTER EVALUATION — SILHOUETTE SCORES${" ".repeat(42)}║")
        Log.d(TAG, "╚${"═".repeat(100)}╝")
        Log.d(TAG, "")
        Log.d(TAG, headerFormat.format("Cluster", "Person Name", "Faces", "Min", "Max", "Mean", "Std"))
        Log.d(TAG, separator)

        for (stat in statsTable) {
            Log.d(TAG, rowFormat.format(
                stat.clusterId,
                stat.personName.take(14),
                stat.faceCount,
                stat.minSilhouette,
                stat.maxSilhouette,
                stat.meanSilhouette,
                stat.stdSilhouette
            ))
        }

        Log.d(TAG, separator)
        Log.d(TAG, "GLOBAL:   Total faces: ${allScores.size}  |  Mean silhouette: %.4f  |  Std: %.4f".format(globalMean, globalStd))
        Log.d(TAG, "          Min: %.4f  |  Max: %.4f".format(
            allScores.minOrNull() ?: 0.0,
            allScores.maxOrNull() ?: 0.0
        ))
        Log.d(TAG, separator)
        Log.d(TAG, "")

        // ── 6. Write CSV to device for easy export ─────────────────────────
        val csvLines = buildString {
            appendLine("cluster_id,person_name,face_count,min_silhouette,max_silhouette,mean_silhouette,std_silhouette")
            for (stat in statsTable) {
                appendLine("${stat.clusterId},${stat.personName},${stat.faceCount},%.6f,%.6f,%.6f,%.6f".format(
                    stat.minSilhouette, stat.maxSilhouette, stat.meanSilhouette, stat.stdSilhouette
                ))
            }
            appendLine()
            appendLine("GLOBAL,,${allScores.size},%.6f,%.6f,%.6f,%.6f".format(
                allScores.minOrNull() ?: 0.0,
                allScores.maxOrNull() ?: 0.0,
                globalMean,
                globalStd
            ))
        }

        writeCsvToDownloads(context, "silhouette_scores_sequential.csv", csvLines)

        // ── 7. Additional evaluation metrics ───────────────────────────────
//        printAdditionalMetrics(clusterEmbeddings, clusterIds, personNameMap, context)

        // ── 8. Summary ─────────────────────────────────────────────────────
//        Log.d(TAG, "")
//        Log.d(TAG, "╔${'═'.repeat(100)}╗")
//        Log.d(TAG, "║  ALL CSVs SAVED TO: Downloads/cluster_eval/${" ".repeat(51)}║")
//        Log.d(TAG, "╚${'═'.repeat(100)}╝")
//        Log.d(TAG, "")
//        Log.d(TAG, "  Files: silhouette_scores.csv, cohesion.csv, separation.csv, size_distribution.csv")
//        Log.d(TAG, "  You can access them from your device's Downloads folder or pull via:")
//        Log.d(TAG, "  adb pull /sdcard/Download/cluster_eval/ ./cluster_eval_results/")
//        Log.d(TAG, "")
    }

    /**
     * Prints additional cluster quality metrics beyond silhouette scores:
     * - Intra-cluster cohesion (mean pairwise cosine similarity within each cluster)
     * - Inter-cluster separation (mean cosine similarity between cluster centroids)
     * - Cluster size distribution statistics
     */
    private fun printAdditionalMetrics(
        clusterEmbeddings: Map<Long, List<FloatArray>>,
        clusterIds: List<Long>,
        personNameMap: Map<Long, String>,
        context: Context
    ) {
        val separator = "─".repeat(100)

        // ── A. Intra-cluster cohesion ──────────────────────────────────────
        Log.d(TAG, "╔${"═".repeat(100)}╗")
        Log.d(TAG, "║  ADDITIONAL METRIC: INTRA-CLUSTER COHESION (Mean Pairwise Cosine Similarity)${" ".repeat(23)}║")
        Log.d(TAG, "╚${"═".repeat(100)}╝")
        Log.d(TAG, "")
        Log.d(TAG, "%-8s │ %-14s │ %6s │ %12s".format("Cluster", "Person Name", "Faces", "Cohesion"))
        Log.d(TAG, separator)

        val cohesionMap = mutableMapOf<Long, Double>()
        for (cid in clusterIds) {
            val embeddings = clusterEmbeddings[cid]!!
            if (embeddings.size <= 1) {
                cohesionMap[cid] = 1.0 // perfect cohesion for singleton
                Log.d(TAG, "%-8d │ %-14s │ %6d │ %12s".format(
                    cid, (personNameMap[cid] ?: "unknown").take(14), embeddings.size, "N/A (1 face)"
                ))
                continue
            }
            var sumSim = 0.0
            var count = 0
            for (i in embeddings.indices) {
                for (j in i + 1 until embeddings.size) {
                    sumSim += VectorUtils.dotProduct(embeddings[i], embeddings[j]).toDouble()
                    count++
                }
            }
            val meanSim = sumSim / count
            cohesionMap[cid] = meanSim
            Log.d(TAG, "%-8d │ %-14s │ %6d │ %12.4f".format(
                cid, (personNameMap[cid] ?: "unknown").take(14), embeddings.size, meanSim
            ))
        }
        Log.d(TAG, separator)
        if (cohesionMap.isNotEmpty()) {
            Log.d(TAG, "Overall mean intra-cluster cohesion: %.4f".format(cohesionMap.values.average()))
        }
        Log.d(TAG, "")

        // Write cohesion CSV
        val cohesionCsv = buildString {
            appendLine("cluster_id,person_name,face_count,cohesion")
            for (cid in clusterIds) {
                val embeddings = clusterEmbeddings[cid]!!
                val name = (personNameMap[cid] ?: "unknown")
                val cohesion = cohesionMap[cid] ?: Double.NaN
                appendLine("$cid,$name,${embeddings.size},%.6f".format(cohesion))
            }
            if (cohesionMap.isNotEmpty()) {
                appendLine()
                appendLine("OVERALL_MEAN,,,%.6f".format(cohesionMap.values.average()))
            }
        }
        writeCsvToDownloads(context, "cohesion.csv", cohesionCsv)

        // ── B. Inter-cluster separation (centroid distances) ───────────────
        Log.d(TAG, "╔${"═".repeat(100)}╗")
        Log.d(TAG, "║  ADDITIONAL METRIC: INTER-CLUSTER SEPARATION (Centroid Cosine Similarity)${" ".repeat(25)}║")
        Log.d(TAG, "╚${"═".repeat(100)}╝")
        Log.d(TAG, "")

        // Compute centroids
        val centroids = mutableMapOf<Long, FloatArray>()
        for (cid in clusterIds) {
            val embeddings = clusterEmbeddings[cid]!!
            val dim = embeddings.first().size
            val sum = FloatArray(dim)
            for (emb in embeddings) {
                for (d in emb.indices) sum[d] += emb[d]
            }
            centroids[cid] = VectorUtils.normalize(VectorUtils.divide(sum, embeddings.size.toFloat()))
        }

        if (clusterIds.size > 1) {
            val interSimilarities = mutableListOf<Double>()
            val sortedIds = clusterIds.sorted()
            for (i in sortedIds.indices) {
                for (j in i + 1 until sortedIds.size) {
                    val sim = VectorUtils.dotProduct(
                        centroids[sortedIds[i]]!!,
                        centroids[sortedIds[j]]!!
                    ).toDouble()
                    interSimilarities.add(sim)
                }
            }
            Log.d(TAG, "Pairwise centroid similarities (should be LOW for good separation):")
            Log.d(TAG, "  Mean:  %.4f".format(interSimilarities.average()))
            Log.d(TAG, "  Min:   %.4f".format(interSimilarities.min()))
            Log.d(TAG, "  Max:   %.4f".format(interSimilarities.max()))
            Log.d(TAG, "  Std:   %.4f".format(
                sqrt(interSimilarities.map { (it - interSimilarities.average()).let { d -> d * d } }.average())
            ))
        } else {
            Log.d(TAG, "Only one cluster — inter-cluster separation is not applicable.")
        }
        Log.d(TAG, "")

        // Write separation CSV
        if (clusterIds.size > 1) {
            val sepInterSimilarities = mutableListOf<Double>()
            val sepSortedIds = clusterIds.sorted()
            val separationCsv = buildString {
                appendLine("cluster_a,cluster_b,person_a,person_b,centroid_similarity")
                for (i in sepSortedIds.indices) {
                    for (j in i + 1 until sepSortedIds.size) {
                        val sim = VectorUtils.dotProduct(
                            centroids[sepSortedIds[i]]!!,
                            centroids[sepSortedIds[j]]!!
                        ).toDouble()
                        sepInterSimilarities.add(sim)
                        appendLine("${sepSortedIds[i]},${sepSortedIds[j]},${personNameMap[sepSortedIds[i]] ?: "unknown"},${personNameMap[sepSortedIds[j]] ?: "unknown"},%.6f".format(sim))
                    }
                }
                appendLine()
                appendLine("SUMMARY,,,,")
                appendLine("mean,,,,%.6f".format(sepInterSimilarities.average()))
                appendLine("min,,,,%.6f".format(sepInterSimilarities.min()))
                appendLine("max,,,,%.6f".format(sepInterSimilarities.max()))
                appendLine("std,,,,%.6f".format(
                    sqrt(sepInterSimilarities.map { (it - sepInterSimilarities.average()).let { d -> d * d } }.average())
                ))
            }
            writeCsvToDownloads(context, "separation.csv", separationCsv)
        }

        // ── C. Cluster size distribution ───────────────────────────────────
        Log.d(TAG, "╔${"═".repeat(100)}╗")
        Log.d(TAG, "║  ADDITIONAL METRIC: CLUSTER SIZE DISTRIBUTION${" ".repeat(53)}║")
        Log.d(TAG, "╚${"═".repeat(100)}╝")
        Log.d(TAG, "")

        val sizes = clusterIds.map { clusterEmbeddings[it]!!.size }
        val totalFaces = sizes.sum()
        val meanSize = sizes.average()
        val medianSize = sizes.sorted().let { s ->
            if (s.size % 2 == 0) (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
            else s[s.size / 2].toDouble()
        }
        val sizeVariance = sizes.map { (it - meanSize) * (it - meanSize) }.average()
        val sizeStd = sqrt(sizeVariance)

        Log.d(TAG, "  Total clusters:   ${clusterIds.size}")
        Log.d(TAG, "  Total faces:      $totalFaces")
        Log.d(TAG, "  Mean size:        %.2f".format(meanSize))
        Log.d(TAG, "  Median size:      %.1f".format(medianSize))
        Log.d(TAG, "  Std deviation:    %.2f".format(sizeStd))
        Log.d(TAG, "  Min size:         ${sizes.min()}")
        Log.d(TAG, "  Max size:         ${sizes.max()}")
        Log.d(TAG, "  Singleton count:  ${sizes.count { it == 1 }}")
        Log.d(TAG, "")

        // Size histogram (buckets)
        val buckets = listOf(1, 2, 5, 10, 20, 50, 100, Int.MAX_VALUE)
        val bucketLabels = listOf("1", "2", "3-5", "6-10", "11-20", "21-50", "51-100", "100+")
        Log.d(TAG, "  Size distribution histogram:")
        for (b in buckets.indices) {
            val lower = if (b == 0) 1 else buckets[b - 1] + 1
            val upper = buckets[b]
            val count = sizes.count { it in lower..upper }
            if (count > 0) {
                val bar = "█".repeat(count)
                Log.d(TAG, "    %-8s │ %4d │ %s".format(bucketLabels[b], count, bar))
            }
        }
        Log.d(TAG, "")

        // Write size distribution CSV
        val sizeDistCsv = buildString {
            appendLine("cluster_id,person_name,face_count")
            for (cid in clusterIds) {
                val size = clusterEmbeddings[cid]!!.size
                appendLine("$cid,${personNameMap[cid] ?: "unknown"},$size")
            }
            appendLine()
            appendLine("SUMMARY,,")
            appendLine("total_clusters,,${clusterIds.size}")
            appendLine("total_faces,,$totalFaces")
            appendLine("mean_size,,%.2f".format(meanSize))
            appendLine("median_size,,%.1f".format(medianSize))
            appendLine("std_deviation,,%.2f".format(sizeStd))
            appendLine("min_size,,${sizes.min()}")
            appendLine("max_size,,${sizes.max()}")
            appendLine("singleton_count,,${sizes.count { it == 1 }}")
            appendLine()
            appendLine("HISTOGRAM,,")
            appendLine("bucket,cluster_count,")
            for (b in buckets.indices) {
                val lower = if (b == 0) 1 else buckets[b - 1] + 1
                val upper = buckets[b]
                val count = sizes.count { it in lower..upper }
                if (count > 0) {
                    appendLine("${bucketLabels[b]},$count,")
                }
            }
        }
        writeCsvToDownloads(context, "size_distribution.csv", sizeDistCsv)

        Log.d(TAG, separator)
        Log.d(TAG, "Evaluation complete.")
    }

    /**
     * Writes a CSV file to Downloads/cluster_eval/ using the MediaStore API.
     * Works on Android 10+ without any storage permissions.
     */
    private fun writeCsvToDownloads(context: Context, fileName: String, content: String) {
        val resolver = context.contentResolver

        // Delete existing file with same name to avoid duplicates
        resolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(fileName, Environment.DIRECTORY_DOWNLOADS + "/cluster_eval/")
        )

        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/cluster_eval")
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(content.toByteArray())
            }
            Log.d(TAG, "CSV written to Downloads/cluster_eval/$fileName")
        } else {
            Log.e(TAG, "Failed to create MediaStore entry for $fileName")
        }
    }
}
