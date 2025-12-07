package com.example.gallery.ml

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
            val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
            if (norm == 0.0f) return vector
            return vector.map { it / norm }.toFloatArray()
        }
    }
}