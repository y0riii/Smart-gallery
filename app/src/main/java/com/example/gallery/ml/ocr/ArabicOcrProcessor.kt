package com.example.gallery.ml.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import androidx.core.graphics.createBitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.lang.AutoCloseable

/**
 * Arabic OCR via Tesseract (ML Kit has no Arabic model). Reads printed Arabic text from a bitmap.
 *
 * Tesseract needs its trained-data file on disk, so on first use we copy `ara.traineddata` from
 * `assets/tessdata/` to app storage. **The app must ship `app/src/main/assets/tessdata/ara.traineddata`**
 * (download from the tessdata_best or tessdata_fast repository). If that file is missing, or Tesseract
 * fails to init, this processor degrades gracefully to "disabled" — [recognizeText] just returns null
 * and indexing continues with the Latin (ML Kit) OCR only, so a missing asset never breaks indexing.
 *
 * Note: Tesseract is best on clean printed/document Arabic and is slower than ML Kit; it runs per
 * image during indexing, so expect indexing to be somewhat slower when this is enabled.
 */
class ArabicOcrProcessor(private val context: Context) : AutoCloseable {

    // Null when Arabic OCR is unavailable (missing traineddata / init failure) → acts as disabled.
    private var tess: TessBaseAPI? = null
    private var executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    init {
        initTesseract()
    }

    private fun initTesseract() {
        try {
            val baseDir = File(context.filesDir, "tesseract")
            copyTrainedDataIfNeeded(context, baseDir)
            val api = TessBaseAPI()
            if (api.init(baseDir.absolutePath, LANG)) {
                // Sparse-text segmentation finds scattered text in photos (signs, labels, captions)
                // far better than Tesseract's default full-page assumption; the DPI hint stops its
                // internal scale guesswork now that we feed it a high-resolution image.
                api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
                api.setVariable("user_defined_dpi", "300")
                tess = api
            } else {
                api.recycle()
                Log.e(TAG, "Tesseract init failed (is $LANG.traineddata present in assets/tessdata?)")
            }
        } catch (e: Throwable) {
            // Most commonly: assets/tessdata/ara.traineddata not bundled. Stay disabled, don't crash.
            Log.e(TAG, "Arabic OCR unavailable — running without it", e)
            tess = null
        }
    }

    /**
     * Recognizes Arabic text in [bitmap], or null if there's none / low confidence / unavailable.
     *
     * Feeds Tesseract a grayscale copy (its binarizer prefers that) and then drops the result if the
     * mean confidence is below [MIN_CONFIDENCE] — this throws away the garbage Tesseract emits on
     * text-less photos, which otherwise pollutes the search index with false matches.
     */
    fun recognizeText(bitmap: Bitmap): String? {
        val api = tess ?: return null
        val gray = toGrayscale(bitmap)
        
        val future = executor.submit(java.util.concurrent.Callable {
            try {
                api.setImage(gray)
                val text = api.getUTF8Text()?.trim()
                val confidence = api.meanConfidence()
                if (text.isNullOrEmpty() || confidence < MIN_CONFIDENCE) null else text
            } catch (e: Throwable) {
                Log.e(TAG, "Arabic OCR failed for a frame", e)
                null
            } finally {
                try { api.clear() } catch (_: Throwable) { }
            }
        })

        return try {
            future.get(5, java.util.concurrent.TimeUnit.MINUTES)
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.e(TAG, "Tesseract native deadlock detected! Abandoning thread and instance.")
            future.cancel(true)
            executor.shutdownNow()
            
            // Re-create the thread pool and a brand new Tesseract instance for future images
            executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            tess = null
            initTesseract()
            null
        } catch (e: Exception) {
            Log.e(TAG, "Arabic OCR execution failed", e)
            null
        } finally {
            if (gray !== bitmap) gray.recycle()
        }
    }

    /** Desaturated copy of [src] (Tesseract's internal thresholding works better on grayscale).
     *  MUST be ARGB_8888 — tesseract4android's setImage reads 32-bit bitmaps; feeding it RGB_565
     *  makes it see a blank image and return no text. (The source bitmap may still be RGB_565; only
     *  this copy, which is what setImage receives, has to be ARGB_8888.) */
    private fun toGrayscale(src: Bitmap): Bitmap {
        val out = createBitmap(src.width, src.height)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    override fun close() {
        try { tess?.recycle() } catch (_: Throwable) { }
        tess = null
    }

    /** Copies `<LANG>.traineddata` from assets to `<baseDir>/tessdata/` once (Tesseract reads a path). */
    private fun copyTrainedDataIfNeeded(context: Context, baseDir: File) {
        val tessdata = File(baseDir, "tessdata")
        if (!tessdata.exists()) tessdata.mkdirs()
        val out = File(tessdata, "$LANG.traineddata")
        if (out.exists() && out.length() > 0L) return
        context.assets.open("tessdata/$LANG.traineddata").use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
    }

    companion object {
        private const val TAG = "ArabicOcrProcessor"
        private const val LANG = "ara"
        // Tesseract mean confidence (0–100) below which we treat the result as garbage and drop it.
        private const val MIN_CONFIDENCE = 60
    }
}
