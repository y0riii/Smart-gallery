package com.example.gallery.faceDetection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix  
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.lang.AutoCloseable
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
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

    // ───────────────────────────── detection ─────────────────────────────

    suspend fun detectFaces(bitmap: Bitmap): List<Face> =
        suspendCoroutine { cont ->
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                detector.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(emptyList()) }
            } catch (e: Exception) {
                cont.resume(emptyList())
            }
        }

    // ───────────────────────── face alignment ────────────────────────────

    /**
     * Aligns a detected face to a canonical 112×112 pose using a
     * similarity transformation derived from facial landmarks.
     *
     * Falls back to [cropFace] when ML Kit does not return enough
     * landmarks for a reliable alignment.
     *
     * @param bitmap  the full original image
     * @param face    the ML Kit [Face] result that carries landmarks
     * @return a 112×112 aligned & cropped face bitmap
     */
    fun alignFace(bitmap: Bitmap, face: Face): Bitmap {
        val srcPoints = extractLandmarks(face)
            ?: return cropFace(bitmap, face.boundingBox)

        val transform = computeSimilarityTransform(srcPoints, REFERENCE_LANDMARKS)

        // Warp the full image with the computed affine matrix
        val aligned = Bitmap.createBitmap(ALIGNED_SIZE, ALIGNED_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(aligned)
        canvas.drawBitmap(bitmap, transform, null)

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
     * @return an Android [Matrix] ready for [Canvas.drawBitmap] or
     *         [Bitmap.createBitmap] that warps the source image so that
     *         the landmarks land on their target positions.
     */
    private fun computeSimilarityTransform(
        src: Array<FloatArray>,
        dst: Array<FloatArray>
    ): Matrix {
        val n = src.size

        // ── Step 1: compute centroids ──
        var srcMeanX = 0f; var srcMeanY = 0f
        var dstMeanX = 0f; var dstMeanY = 0f
        for (i in 0 until n) {
            srcMeanX += src[i][0]; srcMeanY += src[i][1]
            dstMeanX += dst[i][0]; dstMeanY += dst[i][1]
        }
        srcMeanX /= n; srcMeanY /= n
        dstMeanX /= n; dstMeanY /= n

        // ── Step 2: centre the points & compute std-dev ──
        val srcC = Array(n) { floatArrayOf(src[it][0] - srcMeanX, src[it][1] - srcMeanY) }
        val dstC = Array(n) { floatArrayOf(dst[it][0] - dstMeanX, dst[it][1] - dstMeanY) }

        var srcVar = 0f; var dstVar = 0f
        for (i in 0 until n) {
            srcVar += srcC[i][0] * srcC[i][0] + srcC[i][1] * srcC[i][1]
            dstVar += dstC[i][0] * dstC[i][0] + dstC[i][1] * dstC[i][1]
        }
        val srcStd = sqrt(srcVar / n)
        val dstStd = sqrt(dstVar / n)

        // Avoid division by zero for degenerate inputs
        if (srcStd < 1e-6f || dstStd < 1e-6f) {
            return Matrix() // identity – falls back to no transform
        }

        for (i in 0 until n) {
            srcC[i][0] /= srcStd; srcC[i][1] /= srcStd
            dstC[i][0] /= dstStd; dstC[i][1] /= dstStd
        }

        // ── Step 3: 2×2 cross-covariance matrix H = srcᵀ · dst ──
        var h00 = 0f; var h01 = 0f
        var h10 = 0f; var h11 = 0f
        for (i in 0 until n) {
            h00 += srcC[i][0] * dstC[i][0]
            h01 += srcC[i][0] * dstC[i][1]
            h10 += srcC[i][1] * dstC[i][0]
            h11 += srcC[i][1] * dstC[i][1]
        }

        // SVD of a 2×2 matrix – closed-form solution
        val (u, vt) = svd2x2(h00, h01, h10, h11)

        // R = V_H · U_Hᵀ
        var r00 = vt[0] * u[0] + vt[2] * u[1]
        var r01 = vt[0] * u[2] + vt[2] * u[3]
        var r10 = vt[1] * u[0] + vt[3] * u[1]
        var r11 = vt[1] * u[2] + vt[3] * u[3]

        // Fix reflection if determinant < 0
        if (r00 * r11 - r01 * r10 < 0) {
            r00 = vt[0] * u[0] - vt[2] * u[1]
            r01 = vt[0] * u[2] - vt[2] * u[3]
            r10 = vt[1] * u[0] - vt[3] * u[1]
            r11 = vt[1] * u[2] - vt[3] * u[3]
        }

        // ── Step 4: build the full affine matrix ──
        val scale = dstStd / srcStd
        val sR00 = scale * r00; val sR01 = scale * r01
        val sR10 = scale * r10; val sR11 = scale * r11

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
                0f,   0f,   1f
            )
        )
        return matrix
    }

    /**
     * Analytic SVD for a 2×2 matrix.
     *
     * Returns **U** (row-major 2×2, 4 floats) and **Vᵀ** (row-major 2×2,
     * 4 floats).  We only need U and Vᵀ to compute the rotation; the
     * singular values are not returned.
     */
    private fun svd2x2(
        a: Float, b: Float,
        c: Float, d: Float
    ): Pair<FloatArray, FloatArray> {
        // AᵀA = [[a² + c², ab + cd], [ab + cd, b² + d²]]
        val ata00 = a * a + c * c
        val ata01 = a * b + c * d
        val ata11 = b * b + d * d

        // Eigenvalues of the symmetric 2×2 AᵀA
        val avg = (ata00 + ata11) * 0.5f
        val diff = (ata00 - ata11) * 0.5f
        val disc = sqrt(diff * diff + ata01 * ata01)
        val s1sq = avg + disc
        val s2sq = (avg - disc).coerceAtLeast(0f)

        val s1 = sqrt(s1sq).coerceAtLeast(1e-10f)
        val s2 = sqrt(s2sq).coerceAtLeast(1e-10f)

        // Eigenvectors of AᵀA → columns of V
        val theta = kotlin.math.atan2(2f * ata01, ata00 - ata11) / 2f
        val cosT = kotlin.math.cos(theta)
        val sinT = kotlin.math.sin(theta)

        // V  = [[cosT, -sinT], [sinT, cosT]]
        // Vᵀ = [[cosT, sinT], [-sinT, cosT]]
        val vt = floatArrayOf(cosT, sinT, -sinT, cosT)

        // U = A · V · Σ⁻¹
        // AV columns
        val av0x = a * cosT + b * sinT
        val av0y = c * cosT + d * sinT
        val av1x = -a * sinT + b * cosT
        val av1y = -c * sinT + d * cosT

        val u = floatArrayOf(
            av0x / s1, av1x / s2,
            av0y / s1, av1y / s2
        )

        return Pair(u, vt)
    }

    // ───────────────── fallback crop (no alignment) ─────────────────────

    /**
     * Simple bounding-box crop with a 30 % margin expansion.
     * Used as a fallback when landmarks are not available.
     */
    fun cropFace(bitmap: Bitmap, rect: Rect): Bitmap {
        val scale = 1.3f
        val centerX = rect.centerX()
        val centerY = rect.centerY()

        val newWidth = (rect.width() * scale).toInt()
        val newHeight = (rect.height() * scale).toInt()

        val left = (centerX - newWidth / 2).coerceAtLeast(0)
        val top = (centerY - newHeight / 2).coerceAtLeast(0)
        val right = (centerX + newWidth / 2).coerceAtMost(bitmap.width)
        val bottom = (centerY + newHeight / 2).coerceAtMost(bitmap.height)

        return Bitmap.createBitmap(
            bitmap,
            left,
            top,
            right - left,
            bottom - top
        )
    }

    override fun close() {
        detector.close()
    }
}