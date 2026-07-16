package com.example.gallery.ml.face

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import com.example.gallery.utils.ImageUtils
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.lang.AutoCloseable
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.sqrt

class FaceDetectionProcessor : AutoCloseable {

    companion object {
        /** Output size that MobileFaceNet expects */
        private const val ALIGNED_SIZE = 112

        /**
         * Canonical reference coordinates for a 112×112 aligned face.
         * Order: left-eye, right-eye, nose-tip, left-mouth, right-mouth.
         * These positions represent an "ideal" frontally-aligned face
         * inside the 112×112 crop.
         */
        private val REFERENCE_LANDMARKS = arrayOf(
            floatArrayOf(38.2946f, 51.6963f),
            floatArrayOf(73.5318f, 51.5014f),
            floatArrayOf(56.0252f, 71.7366f),
            floatArrayOf(41.5493f, 92.3655f),
            floatArrayOf(70.7299f, 92.2041f)
        )
    }

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
    )

    private fun hasRequiredLandmarks(face: Face): Boolean {
        return face.getLandmark(FaceLandmark.LEFT_EYE) != null &&
                face.getLandmark(FaceLandmark.RIGHT_EYE) != null &&
                face.getLandmark(FaceLandmark.NOSE_BASE) != null &&
                face.getLandmark(FaceLandmark.MOUTH_LEFT) != null &&
                face.getLandmark(FaceLandmark.MOUTH_RIGHT) != null
    }

    private fun isFaceFrontal(face: Face): Boolean {
        return abs(face.headEulerAngleY) < 20 &&  // yaw
                abs(face.headEulerAngleX) < 20 &&  // pitch
                abs(face.headEulerAngleZ) < 15     // roll
    }

    private fun hasValidEyeDistance(face: Face): Boolean {
        val left = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return false
        val right = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return false

        val dx = left.x - right.x
        val dy = left.y - right.y
        val distance = sqrt(dx * dx + dy * dy)

        return distance > 20f
    }

    private fun isGoodFace(face: Face): Boolean {
        return isFaceFrontal(face) &&
                hasRequiredLandmarks(face) &&
                hasValidEyeDistance(face)
    }

    // ───────────────────────────── detection ─────────────────────────────

    suspend fun detectFaces(bitmap: Bitmap): List<Face> =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                detector.process(image)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            val filtered = task.result.filter { face ->
                                isGoodFace(face)
                            }
                            if (cont.isActive) cont.resume(filtered)
                        } else {
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(emptyList())
            }
        }

    // ───────────────────────── face alignment ────────────────────────────

    /**
     * Aligns a detected face to a canonical 112×112 pose using a
     * similarity transformation derived from facial landmarks.
     *
     * Falls back to [ImageUtils.cropImage] when ML Kit does not return enough
     * landmarks for a reliable alignment.
     *
     * @param bitmap  the full original image
     * @param face    the ML Kit [Face] result that carries landmarks
     * @return a 112×112 aligned & cropped face bitmap
     */
    fun alignFace(bitmap: Bitmap, face: Face): Bitmap {
        val srcPoints = extractLandmarks(face)
            ?: return ImageUtils.cropImage(bitmap, face.boundingBox)

        val transform = computeSimilarityTransform(srcPoints, REFERENCE_LANDMARKS)

        // Warp the full image with the computed affine matrix
        val aligned = createBitmap(ALIGNED_SIZE, ALIGNED_SIZE)
        val canvas = Canvas(aligned)

        val paint = Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
        }

        canvas.drawBitmap(bitmap, transform, paint)

        return aligned
    }

    /**
     * Extracts 5 landmark points from an ML Kit [Face].
     *
     * ML Kit provides discrete landmark types; we map them to the
     * 5-point layout used by most face-recognition reference templates:
     *   0 – left eye, 1 – right eye, 2 – nose base,
     *   3 – mouth left, 4 – mouth right.
     *
     * @return an array of 5 [floatArrayOf(x, y)] or `null` if any
     *         landmark is missing.
     */
    private fun extractLandmarks(face: Face): Array<FloatArray>? {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position ?: return null
        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position ?: return null
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: return null

        return arrayOf(
            floatArrayOf(leftEye.x, leftEye.y),
            floatArrayOf(rightEye.x, rightEye.y),
            floatArrayOf(nose.x, nose.y),
            floatArrayOf(mouthLeft.x, mouthLeft.y),
            floatArrayOf(mouthRight.x, mouthRight.y)
        )
    }

    // ──────────────── similarity transform (Umeyama) ────────────────────

    /**
     * Computes the optimal 2-D similarity transformation (rotation,
     * uniform scale, and translation) that maps [src] points onto [dst]
     * points in a least-squares sense.
     *
     * The algorithm is based on the **Umeyama method** (Shinji Umeyama,
     * "Least-squares estimation of transformation parameters between
     * two point patterns", PAMI 1991) which is also used by the Python
     * reference code the user provided.
     *
     * ### How it works – step by step
     *
     * 1. **Centroid removal** – translate both point sets so their means
     *    are at the origin. This separates translation from rotation/scale.
     *
     * 2. **Normalisation** – divide each point set by its standard
     *    deviation so that scale differences are factored out.
     *
     * 3. **Cross-covariance & SVD** – build the 2×2 cross-covariance
     *    matrix H = srcᵀ · dst and decompose it with SVD (H = U·S·Vᵀ).
     *    The rotation that best aligns the two point clouds is R = V·Uᵀ.
     *
     * 4. **Reconstruct the full affine** – combine scale (σ_dst / σ_src),
     *    rotation R, and the centroid translation into an Android
     *    [Matrix] (3×3 affine, last row [0, 0, 1]).
     *
     * @param src detected landmark positions (N × 2)
     * @param dst reference/target positions      (N × 2)
     * @return an Android [android.graphics.Matrix] ready for [Canvas.drawBitmap] or
     *         [Bitmap.createBitmap] that warps the source image so that
     *         the landmarks land on their target positions.
     */
    private fun computeSimilarityTransform(
        src: Array<FloatArray>,
        dst: Array<FloatArray>
    ): Matrix {
        val n = src.size

        // ── Step 1: compute centroids ──
        var srcMeanX = 0f
        var srcMeanY = 0f
        var dstMeanX = 0f
        var dstMeanY = 0f
        for (i in 0 until n) {
            srcMeanX += src[i][0]; srcMeanY += src[i][1]
            dstMeanX += dst[i][0]; dstMeanY += dst[i][1]
        }
        srcMeanX /= n; srcMeanY /= n
        dstMeanX /= n; dstMeanY /= n

        // ── Step 2: centre the points & compute std-dev ──
        val srcC = Array(n) { floatArrayOf(src[it][0] - srcMeanX, src[it][1] - srcMeanY) }
        val dstC = Array(n) { floatArrayOf(dst[it][0] - dstMeanX, dst[it][1] - dstMeanY) }

        var srcVar = 0f
        var dstVar = 0f
        for (i in 0 until n) {
            srcVar += srcC[i][0] * srcC[i][0] + srcC[i][1] * srcC[i][1]
            dstVar += dstC[i][0] * dstC[i][0] + dstC[i][1] * dstC[i][1]
        }
        val srcStd = sqrt(srcVar / (n * 2))
        val dstStd = sqrt(dstVar / (n * 2))

        // Avoid division by zero for degenerate inputs
        if (srcStd < 1e-6f || dstStd < 1e-6f) {
            return Matrix() // identity – falls back to no transform
        }

        for (i in 0 until n) {
            srcC[i][0] /= srcStd; srcC[i][1] /= srcStd
            dstC[i][0] /= dstStd; dstC[i][1] /= dstStd
        }

        // ── Step 3: 2×2 cross-covariance matrix H = srcᵀ · dst ──
        var h00 = 0f
        var h01 = 0f
        var h10 = 0f
        var h11 = 0f
        for (i in 0 until n) {
            h00 += srcC[i][0] * dstC[i][0]
            h01 += srcC[i][0] * dstC[i][1]
            h10 += srcC[i][1] * dstC[i][0]
            h11 += srcC[i][1] * dstC[i][1]
        }

        val r = computeRotationTranspose(h00, h01, h10, h11)

        // ── Step 4: build the full affine matrix ──
        val scale = dstStd / srcStd
        val sR00 = scale * r[0][0]
        val sR01 = scale * r[0][1]
        val sR10 = scale * r[1][0]
        val sR11 = scale * r[1][1]

        val tx = dstMeanX - (sR00 * srcMeanX + sR01 * srcMeanY)
        val ty = dstMeanY - (sR10 * srcMeanX + sR11 * srcMeanY)

        // Android Matrix stores values in row-major:
        //   [ MSCALE_X  MSKEW_X   MTRANS_X ]
        //   [ MSKEW_Y   MSCALE_Y  MTRANS_Y ]
        //   [ MPERSP_0  MPERSP_1  MPERSP_2 ]
        val matrix = Matrix()
        matrix.setValues(
            floatArrayOf(
                sR00, sR01, tx,
                sR10, sR11, ty,
                0f, 0f, 1f
            )
        )
        return matrix
    }

    private fun computeRotationTranspose(
        a: Float,
        b: Float,
        c: Float,
        d: Float
    ): Array<FloatArray> {
        val s = a + d
        val t = c - b

        val norm = sqrt(s * s + t * t)

        val x = s / norm
        val y = t / norm

        return arrayOf(
            floatArrayOf(x, y),
            floatArrayOf(-y, x)
        )
    }

    override fun close() {
        detector.close()
    }
}