package com.example.gallery.db

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Room database type converters in [Converters].
 *
 * Verifies representation conversion logic between [FloatArray] and [ByteArray]
 * to ensure that embedding data is persisted and retrieved losslessly.
 */
class ConvertersTest {

    private val converters = Converters()

    @Test
    fun test_converters_lossless_roundtrip_regular() {
        val original = floatArrayOf(0.1f, -0.2f, 3.14159f, 42.0f)
        val bytes = converters.fromFloatArray(original)
        
        // Float is 4 bytes, so length should be 4 * size
        assertEquals(original.size * 4, bytes.size)

        val retrieved = converters.toFloatArray(bytes)
        assertArrayEquals(original, retrieved, 1e-6f)
    }

    @Test
    fun test_converters_empty_array() {
        val original = FloatArray(0)
        val bytes = converters.fromFloatArray(original)
        assertEquals(0, bytes.size)

        val retrieved = converters.toFloatArray(bytes)
        assertEquals(0, retrieved.size)
    }

    @Test
    fun test_converters_single_element() {
        val original = floatArrayOf(999.999f)
        val bytes = converters.fromFloatArray(original)
        assertEquals(4, bytes.size)

        val retrieved = converters.toFloatArray(bytes)
        assertArrayEquals(original, retrieved, 1e-6f)
    }

    @Test
    fun test_converters_extreme_floats() {
        val original = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        val bytes = converters.fromFloatArray(original)
        val retrieved = converters.toFloatArray(bytes)

        assertTrue(retrieved[0].isNaN())
        assertEquals(Float.POSITIVE_INFINITY, retrieved[1])
        assertEquals(Float.NEGATIVE_INFINITY, retrieved[2])
    }

    @Test
    fun test_converters_all_zeros() {
        val original = FloatArray(128) { 0.0f }
        val bytes = converters.fromFloatArray(original)
        assertEquals(512, bytes.size)

        val retrieved = converters.toFloatArray(bytes)
        assertArrayEquals(original, retrieved, 1e-6f)
    }

    // ─────────── Edge Cases ───────────

    @Test
    fun test_converters_max_and_min_float_values() {
        // Float.MAX_VALUE and Float.MIN_VALUE (smallest positive) must survive roundtrip
        val original = floatArrayOf(Float.MAX_VALUE, Float.MIN_VALUE)
        val retrieved = converters.toFloatArray(converters.fromFloatArray(original))
        assertArrayEquals(original, retrieved, 0f)
    }

    @Test
    fun test_converters_negative_values_preserved() {
        val original = floatArrayOf(-1f, -100f, -0.00001f, -Float.MAX_VALUE)
        val retrieved = converters.toFloatArray(converters.fromFloatArray(original))
        assertArrayEquals(original, retrieved, 0f)
    }

    @Test
    fun test_converters_byte_count_formula_is_correct() {
        // For an array of size N, byte count must always be N * 4
        for (n in listOf(1, 16, 128, 256, 512)) {
            val arr = FloatArray(n) { it.toFloat() }
            assertEquals(n * 4, converters.fromFloatArray(arr).size)
        }
    }

    @Test
    fun test_converters_512d_embedding_roundtrip() {
        // Simulate a real 512-d CLIP embedding with values in [-1, 1]
        val original = FloatArray(512) { (it - 256f) / 256f }
        val retrieved = converters.toFloatArray(converters.fromFloatArray(original))
        assertArrayEquals(original, retrieved, 1e-6f)
    }

    @Test
    fun test_converters_mixed_signs_and_magnitudes() {
        val original = floatArrayOf(0f, 1f, -1f, 0.5f, -0.5f, 1000f, -1000f)
        val retrieved = converters.toFloatArray(converters.fromFloatArray(original))
        assertArrayEquals(original, retrieved, 0f)
    }
}

