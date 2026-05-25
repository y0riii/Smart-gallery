package com.example.gallery.utils

import kotlin.math.sqrt

class VectorUtils {
    companion object {
        fun dotProduct(vecA: FloatArray, vecB: FloatArray): Float {
            var sum = 0.0f
            for (i in vecA.indices) {
                sum += vecA[i] * vecB[i]
            }
            return sum
        }

        fun normalize(vector: FloatArray): FloatArray {
            var norm = 0.0f
            for (v in vector) {
                norm += v * v
            }
            norm = sqrt(norm)
            if (norm == 0.0f) return vector.copyOf()

            return divide(vector, norm)
        }

        fun divide(vector: FloatArray, scalar: Float): FloatArray {
            val result = FloatArray(vector.size)
            for (i in vector.indices) {
                result[i] = vector[i] / scalar
            }
            return result
        }

        fun add(vecA: FloatArray, vecB: FloatArray): FloatArray {
            val result = FloatArray(vecA.size)
            for (i in vecA.indices) {
                result[i] = vecA[i] + vecB[i]
            }
            return result
        }

        fun subtract(vecA: FloatArray, vecB: FloatArray): FloatArray {
            val result = FloatArray(vecA.size)
            for (i in vecA.indices) {
                result[i] = vecA[i] - vecB[i]
            }
            return result
        }

        fun euclideanDistance(vecA: FloatArray, vecB: FloatArray): Float {
            var sum = 0.0f
            for (i in vecA.indices) {
                val diff = vecA[i] - vecB[i]
                sum += diff * diff
            }
            return sqrt(sum.toDouble()).toFloat()
        }
    }
}