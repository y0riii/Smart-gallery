package com.example.gallery

import com.example.gallery.utils.VectorUtils
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/**
 * Cleaned, non-redundant Unit Tests for [VectorUtils].
 *
 * Verifies mathematical vectors operations (dot product, normalization, division,
 * addition, subtraction, Euclidean distance) and boundary conditions.
 */
class VectorUtilsTest {

    private val delta = 1e-5f

    @Test
    fun test_001_dotProduct_orthogonal() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertEquals(0f, VectorUtils.dotProduct(a, b), delta)
    }

    @Test
    fun test_002_dotProduct_parallel() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(2f, 4f, 6f)
        // 2 + 8 + 18 = 28
        assertEquals(28f, VectorUtils.dotProduct(a, b), delta)
    }

    @Test
    fun test_003_dotProduct_opposite() {
        val a = floatArrayOf(1f, -1f)
        val b = floatArrayOf(-1f, 1f)
        assertEquals(-2f, VectorUtils.dotProduct(a, b), delta)
    }

    @Test
    fun test_004_dotProduct_empty() {
        val a = FloatArray(0)
        val b = FloatArray(0)
        assertEquals(0f, VectorUtils.dotProduct(a, b), delta)
    }

    @Test
    fun test_005_dotProduct_different_scales() {
        val a = floatArrayOf(0.5f, 0.5f)
        val b = floatArrayOf(0.1f, 10f)
        // 0.05 + 5.0 = 5.05
        assertEquals(5.05f, VectorUtils.dotProduct(a, b), delta)
    }

    @Test
    fun test_006_normalize_standard() {
        val v = floatArrayOf(3f, 4f)
        val norm = VectorUtils.normalize(v)
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), norm, delta)
    }

    @Test
    fun test_007_normalize_zero_vector_returns_copy() {
        val v = floatArrayOf(0f, 0f, 0f)
        val norm = VectorUtils.normalize(v)
        assertArrayEquals(v, norm, delta)
    }

    @Test
    fun test_008_normalize_negative_coordinates() {
        val v = floatArrayOf(-3f, -4f)
        val norm = VectorUtils.normalize(v)
        assertArrayEquals(floatArrayOf(-0.6f, -0.8f), norm, delta)
    }

    @Test
    fun test_009_divide_standard() {
        val v = floatArrayOf(10f, 20f, -5f)
        val res = VectorUtils.divide(v, 5f)
        assertArrayEquals(floatArrayOf(2f, 4f, -1f), res, delta)
    }

    @Test
    fun test_010_divide_by_zero() {
        val v = floatArrayOf(1f, 2f)
        val res = VectorUtils.divide(v, 0f)
        assertTrue(res[0].isInfinite())
        assertTrue(res[1].isInfinite())
    }

    @Test
    fun test_011_add_standard() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(4f, 5f, 6f)
        val res = VectorUtils.add(a, b)
        assertArrayEquals(floatArrayOf(5f, 7f, 9f), res, delta)
    }

    @Test
    fun test_012_add_empty() {
        val a = FloatArray(0)
        val b = FloatArray(0)
        val res = VectorUtils.add(a, b)
        assertEquals(0, res.size)
    }

    @Test
    fun test_013_subtract_standard() {
        val a = floatArrayOf(5f, 2f, -1f)
        val b = floatArrayOf(3f, 4f, 1f)
        val res = VectorUtils.subtract(a, b)
        assertArrayEquals(floatArrayOf(2f, -2f, -2f), res, delta)
    }

    @Test
    fun test_014_subtract_empty() {
        val a = FloatArray(0)
        val b = FloatArray(0)
        val res = VectorUtils.subtract(a, b)
        assertEquals(0, res.size)
    }

    @Test
    fun test_015_euclideanDistance_same() {
        val a = floatArrayOf(1f, 2f, 3f)
        assertEquals(0f, VectorUtils.euclideanDistance(a, a), delta)
    }

    @Test
    fun test_016_euclideanDistance_standard() {
        val a = floatArrayOf(0f, 0f)
        val b = floatArrayOf(3f, 4f)
        assertEquals(5f, VectorUtils.euclideanDistance(a, b), delta)
    }

    @Test
    fun test_017_euclideanDistance_empty() {
        val a = FloatArray(0)
        val b = FloatArray(0)
        assertEquals(0f, VectorUtils.euclideanDistance(a, b), delta)
    }

    @Test
    fun test_018_normalize_idempotent() {
        val v = floatArrayOf(1.5f, 2.5f, 3.5f)
        val norm1 = VectorUtils.normalize(v)
        val norm2 = VectorUtils.normalize(norm1)
        assertArrayEquals(norm1, norm2, delta)
    }

    @Test
    fun test_019_normalize_almost_zero() {
        val v = floatArrayOf(0f, 0f, 0.0001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), VectorUtils.normalize(v), delta)
    }

    @Test
    fun test_020_normalize_moderately_large() {
        val v = floatArrayOf(10000f, 10000f)
        val expected = 1f / sqrt(2f)
        assertArrayEquals(floatArrayOf(expected, expected), VectorUtils.normalize(v), delta)
    }

    // ─────────── Edge Cases ───────────

    @Test
    fun test_021_dotProduct_single_element() {
        // 1D dot product is simply a * b
        assertEquals(6f, VectorUtils.dotProduct(floatArrayOf(2f), floatArrayOf(3f)), delta)
    }

    @Test
    fun test_022_dotProduct_symmetry() {
        // dot(a, b) must equal dot(b, a)
        val a = floatArrayOf(1.5f, -2.3f, 0.7f)
        val b = floatArrayOf(-0.4f, 1.1f, 3.2f)
        assertEquals(VectorUtils.dotProduct(a, b), VectorUtils.dotProduct(b, a), delta)
    }

    @Test
    fun test_023_dotProduct_with_zero_vector() {
        // Dot product of anything with a zero vector must be 0
        val a = floatArrayOf(99f, -55f, 13f)
        val z = floatArrayOf(0f, 0f, 0f)
        assertEquals(0f, VectorUtils.dotProduct(a, z), delta)
    }

    @Test
    fun test_024_normalize_single_element_positive() {
        // A 1-D positive vector normalizes to [1.0]
        assertArrayEquals(floatArrayOf(1f), VectorUtils.normalize(floatArrayOf(42f)), delta)
    }

    @Test
    fun test_025_normalize_single_element_negative() {
        // A 1-D negative vector normalizes to [-1.0]
        assertArrayEquals(floatArrayOf(-1f), VectorUtils.normalize(floatArrayOf(-7f)), delta)
    }

    @Test
    fun test_026_normalize_result_is_unit_length() {
        // The Euclidean norm of any non-zero normalized vector must equal 1.0
        val v = floatArrayOf(3.7f, -1.2f, 8.5f, 0.3f)
        val norm = VectorUtils.normalize(v)
        var sumSq = 0f
        for (x in norm) sumSq += x * x
        assertEquals(1f, sumSq, delta)
    }

    @Test
    fun test_027_normalize_preserves_sign_structure() {
        val v = floatArrayOf(-1f, 2f, -3f)
        val norm = VectorUtils.normalize(v)
        assertTrue(norm[0] < 0f)
        assertTrue(norm[1] > 0f)
        assertTrue(norm[2] < 0f)
    }

    @Test
    fun test_028_normalize_preserves_ratio() {
        // After normalization, ratios of components must be preserved
        val v = floatArrayOf(10f, 20f)
        val norm = VectorUtils.normalize(v)
        assertEquals(2f, norm[1] / norm[0], delta)
    }

    @Test
    fun test_029_divide_by_negative_scalar() {
        val v = floatArrayOf(6f, -9f, 3f)
        val res = VectorUtils.divide(v, -3f)
        assertArrayEquals(floatArrayOf(-2f, 3f, -1f), res, delta)
    }

    @Test
    fun test_030_divide_by_one_is_identity() {
        val v = floatArrayOf(1.5f, -3.3f, 7f)
        assertArrayEquals(v, VectorUtils.divide(v, 1f), delta)
    }

    @Test
    fun test_031_add_with_negatives_cancel() {
        // Adding a vector to its negation must yield a zero vector
        val a = floatArrayOf(1f, -2f, 3f)
        val neg = floatArrayOf(-1f, 2f, -3f)
        val res = VectorUtils.add(a, neg)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), res, delta)
    }

    @Test
    fun test_032_subtract_self_yields_zero() {
        // v - v must be all zeros
        val v = floatArrayOf(5f, -3f, 8f, 2.5f)
        val res = VectorUtils.subtract(v, v)
        for (x in res) assertEquals(0f, x, delta)
    }

    @Test
    fun test_033_euclideanDistance_symmetry() {
        // dist(a, b) must equal dist(b, a)
        val a = floatArrayOf(1f, 5f, -2f)
        val b = floatArrayOf(4f, 1f, 3f)
        assertEquals(VectorUtils.euclideanDistance(a, b), VectorUtils.euclideanDistance(b, a), delta)
    }

    @Test
    fun test_034_euclideanDistance_single_dimension() {
        // 1-D Euclidean distance is just abs(a - b)
        val a = floatArrayOf(-3f)
        val b = floatArrayOf(4f)
        assertEquals(7f, VectorUtils.euclideanDistance(a, b), delta)
    }

    @Test
    fun test_035_euclideanDistance_unit_vectors_high_dim() {
        // Distance between two orthogonal unit vectors in any dimension is always sqrt(2)
        val dim = 128
        val a = FloatArray(dim).also { it[0] = 1f }
        val b = FloatArray(dim).also { it[1] = 1f }
        assertEquals(sqrt(2f), VectorUtils.euclideanDistance(a, b), delta)
    }
}

