package com.example.gallery.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
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
    }
}