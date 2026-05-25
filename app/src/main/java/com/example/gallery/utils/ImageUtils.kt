package com.example.gallery.utils

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

class ImageUtils {
    companion object {
        /**
         * Finds all image URIs on the device.
         */
        fun scanMediaStore(context: Context): List<Pair<Long, Long>> {
            val list = mutableListOf<Pair<Long, Long>>()

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED
            )
            val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, sort
            )?.use { cursor ->

                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val timestamp = cursor.getLong(dateIdx) * 1000L
                    list.add(id to timestamp)
                }
            }

            Log.d("MediaRepository", "MediaStore scanned: ${list.size} items")
            return list
        }

        /**
         * Loads a Bitmap from a URI, handling potential sampling.
         */
        fun getBitmapFromUri(context: Context, uri: Uri): Bitmap? {
            return try {
                // Load bounds to avoid loading a huge image into memory
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }

                // Calculate sample size
                options.inSampleSize = max(options.outWidth / 1024, options.outHeight / 1024)
                options.inJustDecodeBounds = false

                // Decode bitmap with sample size
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        fun cropImage(source: Bitmap, rect: Rect): Bitmap {
            val left = rect.left.coerceAtLeast(0)
            val top = rect.top.coerceAtLeast(0)
            val width = rect.width().coerceAtMost(source.width - left)
            val height = rect.height().coerceAtMost(source.height - top)

            return Bitmap.createBitmap(source, left, top, width, height)
        }

        suspend fun createThumbnail(context: Context, bitmap: Bitmap): String =
            withContext(Dispatchers.IO) {
                val resized = bitmap.scale(256, 256)
                val fileName = "thumb_${System.currentTimeMillis()}.jpg"
                val file = File(context.filesDir, fileName)

                FileOutputStream(file).use { out ->
                    resized.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }

                file.absolutePath
            }

        suspend fun deleteThumbnail(path: String): Boolean = withContext(Dispatchers.IO) {
            val file = File(path)
            file.exists() && file.delete()
        }
    }
}

/**
 * Converts a MediaStore image ID to its content URI.
 * Replaces the verbose ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id) pattern.
 */
fun Long.toMediaUri(): Uri =
    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, this)