package com.example.gallery.ml.face

import com.example.gallery.utils.VectorUtils

/**
 * Pure-Kotlin implementation of the Chinese Whispers graph clustering algorithm.
 *
 * 1. Build an adjacency list: for each pair of faces, compute cosine similarity.
 *    If similarity > [EDGE_THRESHOLD], add an edge with that similarity as weight.
 * 2. Assign each node a unique label.
 * 3. For [MAX_ITERATIONS] rounds:
 *    - Shuffle node order.
 *    - For each node, set its label to the label with the highest sum of edge
 *      weights among its neighbors.
 * 4. Return groups of node indices sharing the same label.
 *
 * Complexity: O(N²) for graph construction (N = number of faces),
 * then O(iterations × N × avg_degree) for label propagation.
 * For ~5,000 faces this takes a few seconds — acceptable for a background worker.
 */
object ChineseWhispers {

    private const val EDGE_THRESHOLD = 0.55f
    private const val MAX_ITERATIONS = 20

    /**
     * Clusters a list of face embeddings.
     *
     * @param embeddings  list of normalized 128-d float arrays
     * @return a list of clusters, where each cluster is a list of indices
     *         into the original [embeddings] list
     */
    fun cluster(embeddings: List<FloatArray>): List<List<Int>> {
        val n = embeddings.size
        if (n == 0) return emptyList()
        if (n == 1) return listOf(listOf(0))

        // ── Step 1: Build adjacency list with cosine similarity weights ──
        // adjacency[i] = list of (neighborIndex, weight)
        val adjacency = Array(n) { mutableListOf<Pair<Int, Float>>() }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val similarity = VectorUtils.dotProduct(embeddings[i], embeddings[j])
                if (similarity > EDGE_THRESHOLD) {
                    adjacency[i].add(j to similarity)
                    adjacency[j].add(i to similarity)
                }
            }
        }

        // ── Step 2: Assign each node a unique label ──
        val labels = IntArray(n) { it }

        // ── Step 3: Iterate ──
        val indices = (0 until n).toMutableList()
        for (iteration in 0 until MAX_ITERATIONS) {
            indices.shuffle()
            var changed = false

            for (node in indices) {
                val neighbors = adjacency[node]
                if (neighbors.isEmpty()) continue

                // Accumulate weight per label among neighbors
                val labelWeights = HashMap<Int, Float>(neighbors.size)
                for ((neighbor, weight) in neighbors) {
                    val neighborLabel = labels[neighbor]
                    labelWeights[neighborLabel] = (labelWeights[neighborLabel] ?: 0f) + weight
                }

                // Pick the label with the highest total weight
                val bestLabel = labelWeights.maxByOrNull { it.value }?.key ?: labels[node]
                if (bestLabel != labels[node]) {
                    labels[node] = bestLabel
                    changed = true
                }
            }

            // Early termination if no labels changed
            if (!changed) break
        }

        // ── Step 4: Group nodes by label ──
        return labels.indices
            .groupBy { labels[it] }
            .values
            .toList()
    }
}
