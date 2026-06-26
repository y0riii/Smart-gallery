package com.example.gallery.utils

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [ImageUtils].
 *
 * Verifies image manipulation helpers including bounding box scaling,
 * bitmap cropping bounding boxes, and media URI conversion.
 */
@RunWith(AndroidJUnit4::class)
class ImageUtilsTest {

    @Test
    fun test_scaleRect_preservesCenter_scalesDimensions() {
        val original = Rect(100, 100, 200, 200) // width=100, height=100, center=(150, 150)
        val scaled = ImageUtils.scaleRect(original, 1.5f)

        // Center must still be 150, 150
        assertEquals(150f, scaled.exactCenterX(), 0.1f)
        assertEquals(150f, scaled.exactCenterY(), 0.1f)

        // Width and height must be 150
        assertEquals(150, scaled.width())
        assertEquals(150, scaled.height())

        // Coordinates check: center=150, width/height=150 -> left=75, top=75, right=225, bottom=225
        assertEquals(75, scaled.left)
        assertEquals(75, scaled.top)
        assertEquals(225, scaled.right)
        assertEquals(225, scaled.bottom)
    }

    @Test
    fun test_cropImage_extractsCorrectSubarea() {
        // Create a 10x10 synthetic bitmap where top-left is white (0xFFFFFFFF) and bottom-right is black (0xFF000000)
        val source = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.WHITE)
        source.setPixel(8, 8, android.graphics.Color.BLACK)

        // Crop the 2x2 area in the bottom right corner
        val cropRect = Rect(8, 8, 10, 10)
        val cropped = ImageUtils.cropImage(source, cropRect)

        assertEquals(2, cropped.width)
        assertEquals(2, cropped.height)
        assertEquals(android.graphics.Color.BLACK, cropped.getPixel(0, 0))
    }

    @Test
    fun test_toMediaUri_convertsCorrectly() {
        val mediaId = 12345L
        val uri: Uri = mediaId.toMediaUri()

        assertNotNull(uri)
        assertEquals("content", uri.scheme)
        assertEquals(MediaStore.AUTHORITY, uri.authority)
        assertTrue(uri.toString().contains("12345"))
    }
}
