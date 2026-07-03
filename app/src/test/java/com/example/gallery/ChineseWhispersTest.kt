package com.example.gallery

import com.example.gallery.ml.face.ChineseWhispers
import com.example.gallery.utils.VectorUtils
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/**
 * Cleaned, non-redundant Unit Tests for Chinese Whispers Graph Clustering.
 *
 * Verifies graph structural partitions (empty, singletons, cliques, chains, loops)
 * and cosine-similarity threshold borders.
 */
class ChineseWhispersTest {

    private val dim = 128

    private fun axisEmbedding(axis: Int): FloatArray {
        return FloatArray(dim) { if (it == axis) 1f else 0f }
    }

    private fun syntheticEmbedding(centroid: FloatArray, noise: Float, seed: Int): FloatArray {
        val rng = java.util.Random(seed.toLong())
        val result = FloatArray(dim) { i ->
            centroid[i] + (rng.nextFloat() - 0.5f) * noise
        }
        return VectorUtils.normalize(result)
    }

    @Test
    fun test_001_emptyInput_returnsEmpty() {
        assertTrue(ChineseWhispers.cluster(emptyList()).isEmpty())
    }

    @Test
    fun test_002_singleEmbedding_returnsOneCluster() {
        val res = ChineseWhispers.cluster(listOf(axisEmbedding(0)))
        assertEquals(1, res.size)
        assertEquals(0, res[0][0])
    }

    @Test
    fun test_003_twoIdenticalEmbeddings_groupTogether() {
        val v = axisEmbedding(0)
        val res = ChineseWhispers.cluster(listOf(v, v.copyOf()))
        assertEquals(1, res.size)
        assertEquals(2, res[0].size)
    }

    @Test
    fun test_004_twoOrthogonalEmbeddings_separate() {
        val res = ChineseWhispers.cluster(listOf(axisEmbedding(0), axisEmbedding(1)))
        assertEquals(2, res.size)
    }

    @Test
    fun test_005_chain_structure_3nodes_connected() {
        // Node 0 connects to Node 1, Node 1 connects to Node 2.
        // Node 0 and Node 2 similarity is below threshold.
        val v0 = floatArrayOf(1f, 0f)
        val v1 = floatArrayOf(0.707f, 0.707f) // dot(0, 1) = 0.707 (> 0.55)
        val v2 = floatArrayOf(0f, 1f)          // dot(1, 2) = 0.707 (> 0.55), dot(0, 2) = 0.0 (< 0.55)
        val res = ChineseWhispers.cluster(listOf(v0, v1, v2))
        assertEquals(1, res.size)
    }

    @Test
    fun test_006_threshold_just_above_edge() {
        // threshold is 0.55. Cosine similarity 0.56 -> connected.
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0.56f, sqrt(1f - 0.56f * 0.56f))
        val res = ChineseWhispers.cluster(listOf(a, b))
        assertEquals(1, res.size)
    }

    @Test
    fun test_007_threshold_just_below_edge() {
        // Cosine similarity 0.54 -> disconnected.
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0.54f, sqrt(1f - 0.54f * 0.54f))
        val res = ChineseWhispers.cluster(listOf(a, b))
        assertEquals(2, res.size)
    }

    @Test
    fun test_008_triangle_fully_connected() {
        val c = axisEmbedding(0)
        val list = listOf(
            syntheticEmbedding(c, 0.01f, 1),
            syntheticEmbedding(c, 0.01f, 2),
            syntheticEmbedding(c, 0.01f, 3)
        )
        val clusters = ChineseWhispers.cluster(list)
        assertEquals(1, clusters.size)
        assertEquals(3, clusters[0].size)
    }

    @Test
    fun test_009_two_cliques_5plus5() {
        val c1 = axisEmbedding(0)
        val c2 = axisEmbedding(1)
        val list = (0 until 5).map { syntheticEmbedding(c1, 0.001f, it) } +
                   (5 until 10).map { syntheticEmbedding(c2, 0.001f, it) }
        val res = ChineseWhispers.cluster(list)
        assertEquals(2, res.size)
    }

    @Test
    fun test_010_clique_with_one_distant_singleton() {
        val c = axisEmbedding(0)
        val list = (0 until 4).map { syntheticEmbedding(c, 0.001f, it) } + listOf(axisEmbedding(1))
        val res = ChineseWhispers.cluster(list)
        assertEquals(2, res.size)
    }

    @Test
    fun test_011_ring_network_6nodes() {
        val list = (0 until 6).map { i ->
            val angle = i * (Math.PI / 6) // cos(30) = 0.866 -> connected.
            floatArrayOf(Math.cos(angle).toFloat(), Math.sin(angle).toFloat())
        }
        val res = ChineseWhispers.cluster(list)
        assertEquals(1, res.size)
    }

    @Test
    fun test_012_two_lines_separated() {
        val v0 = floatArrayOf(1f, 0f)
        val v1 = floatArrayOf(0.8f, 0.6f)
        val u0 = floatArrayOf(0f, 1f)
        val u1 = floatArrayOf(-0.6f, 0.8f)
        val res = ChineseWhispers.cluster(listOf(v0, v1, u0, u1))
        assertEquals(2, res.size)
    }

    @Test
    fun test_013_linear_chain_4nodes() {
        val v0 = floatArrayOf(1f, 0f)
        val v1 = floatArrayOf(0.8f, 0.6f)
        val v2 = floatArrayOf(0.3f, 0.95f)
        val v3 = floatArrayOf(-0.3f, 0.95f)
        val res = ChineseWhispers.cluster(listOf(v0, v1, v2, v3))
        assertEquals(1, res.size)
    }

    @Test
    fun test_014_dense_clique_10nodes() {
        val c = axisEmbedding(0)
        val list = (0 until 10).map { syntheticEmbedding(c, 0.001f, it) }
        val res = ChineseWhispers.cluster(list)
        assertEquals(1, res.size)
        assertEquals(10, res[0].size)
    }

    @Test
    fun test_015_all_singletons_orthogonal_10nodes() {
        val list = (0 until 10).map { axisEmbedding(it) }
        val res = ChineseWhispers.cluster(list)
        assertEquals(10, res.size)
    }

    // ─────────── Edge Cases ───────────

    @Test
    fun test_016_all_indices_present_in_output() {
        // No index should be lost or appear more than once across all clusters
        val c1 = axisEmbedding(0)
        val c2 = axisEmbedding(1)
        val list = (0 until 6).map { syntheticEmbedding(c1, 0.001f, it) } +
                   (6 until 12).map { syntheticEmbedding(c2, 0.001f, it) }
        val clusters = ChineseWhispers.cluster(list)
        val allIndices = clusters.flatten()
        assertEquals("All 12 indices must appear exactly once", 12, allIndices.size)
        assertEquals("No duplicates allowed", 12, allIndices.toSet().size)
    }

    @Test
    fun test_017_all_identical_embeddings_form_one_cluster() {
        // If every input vector is the same, they must all end up in one cluster
        val v = axisEmbedding(0)
        val list = List(8) { v.copyOf() }
        val res = ChineseWhispers.cluster(list)
        assertEquals(1, res.size)
        assertEquals(8, res[0].size)
    }

    @Test
    fun test_018_cosine_similarity_exactly_at_threshold() {
        // A vector whose cosine similarity is exactly 0.55 with another.
        // cos(theta) = 0.55 -> second component = sqrt(1 - 0.55^2) = sqrt(0.6975)
        val a = floatArrayOf(1f, 0f)
        val cosVal = 0.55f
        val b = floatArrayOf(cosVal, sqrt(1f - cosVal * cosVal))
        val res = ChineseWhispers.cluster(listOf(a, b))
        // At exactly the threshold the two vectors may or may not merge depending on
        // the implementation's comparison (strict > or >=). Either size is acceptable.
        assertTrue("Result must be 1 or 2 clusters", res.size in 1..2)
    }

    @Test
    fun test_019_mixed_duplicates_and_singletons() {
        // Two identical embeddings plus two orthogonal singletons
        val v = axisEmbedding(0)
        val list = listOf(v.copyOf(), v.copyOf(), axisEmbedding(1), axisEmbedding(2))
        val res = ChineseWhispers.cluster(list)
        // Duplicates merge → cluster of 2; two singletons → 2 clusters; total = 3
        assertEquals(3, res.size)
    }

    @Test
    fun test_020_output_cluster_sizes_sum_to_input_size() {
        val c1 = axisEmbedding(0)
        val c2 = axisEmbedding(1)
        val c3 = axisEmbedding(2)
        val list = (0 until 5).map { syntheticEmbedding(c1, 0.001f, it) } +
                   (5 until 9).map { syntheticEmbedding(c2, 0.001f, it) } +
                   (9 until 12).map { syntheticEmbedding(c3, 0.001f, it) }
        val res = ChineseWhispers.cluster(list)
        val totalOut = res.sumOf { it.size }
        assertEquals("Sum of cluster sizes must equal input count", 12, totalOut)
    }

    @Test
    fun test_021_two_node_cluster_contains_both_indices() {
        val v = axisEmbedding(0)
        val res = ChineseWhispers.cluster(listOf(v, v.copyOf()))
        assertEquals(1, res.size)
        val indices = res[0].sorted()
        assertEquals(listOf(0, 1), indices)
    }

    @Test
    fun test_022_deterministic_on_same_input() {
        // Running cluster() twice on the same data must produce the same result
        val c = axisEmbedding(0)
        val list = (0 until 10).map { syntheticEmbedding(c, 0.001f, it) }
        val res1 = ChineseWhispers.cluster(list)
        val res2 = ChineseWhispers.cluster(list)
        assertEquals(res1.size, res2.size)
        assertEquals(
            res1.map { it.sorted() }.sortedBy { it.first() },
            res2.map { it.sorted() }.sortedBy { it.first() }
        )
    }
}

