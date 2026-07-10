package com.example.gallery.utils

import kotlin.math.sqrt

/**
 * Small float-vector helpers used across the ML pipeline (CLIP/face embeddings). Embeddings are
 * stored L2-normalized, so [dotProduct] of two of them is their cosine similarity — that's the score
 * behind semantic search, AI-album matching, and face clustering. All functions are allocation-light
 * loops (no external linear-algebra dependency) and operate element-wise on same-length arrays.
 */
class VectorUtils {
    companion object {
        /** Dot product of two equal-length vectors. On unit vectors this equals cosine similarity. */
        fun dotProduct(vecA: FloatArray, vecB: FloatArray): Float {
            var sum = 0.0f
            for (i in vecA.indices) {
                sum += vecA[i] * vecB[i]
            }
            return sum
        }

        /** Returns a unit-length (L2-normalized) copy of [vector]; a zero vector is returned unchanged. */
        fun normalize(vector: FloatArray): FloatArray {
            var norm = 0.0f
            for (v in vector) {
                norm += v * v
            }
            norm = sqrt(norm)
            if (norm == 0.0f) return vector.copyOf()

            return divide(vector, norm)
        }

        /** Element-wise division of [vector] by [scalar] (used by [normalize]). */
        fun divide(vector: FloatArray, scalar: Float): FloatArray {
            val result = FloatArray(vector.size)
            for (i in vector.indices) {
                result[i] = vector[i] / scalar
            }
            return result
        }

        /** Element-wise sum. Used to fold a new face into a person's running embedding sum. */
        fun add(vecA: FloatArray, vecB: FloatArray): FloatArray {
            val result = FloatArray(vecA.size)
            for (i in vecA.indices) {
                result[i] = vecA[i] + vecB[i]
            }
            return result
        }

        /** Element-wise difference. Used to remove a deleted face from a person's embedding sum. */
        fun subtract(vecA: FloatArray, vecB: FloatArray): FloatArray {
            val result = FloatArray(vecA.size)
            for (i in vecA.indices) {
                result[i] = vecA[i] - vecB[i]
            }
            return result
        }

        /**
         * Mean of several (normalized) embeddings, re-normalized to unit length — used to collapse a
         * video's per-keyframe CLIP embeddings into one searchable vector. Returns an empty array for
         * an empty input.
         */
        fun meanPool(vectors: List<FloatArray>): FloatArray {
            if (vectors.isEmpty()) return FloatArray(0)
            val dim = vectors[0].size
            val sum = FloatArray(dim)
            for (v in vectors) {
                for (d in 0 until dim) sum[d] += v[d]
            }
            for (d in 0 until dim) sum[d] /= vectors.size
            return normalize(sum)
        }
    }
}