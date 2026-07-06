package com.example.gallery.utils

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object VideoUtils {

    /**
     * Scans MediaStore for all videos on the device.
     * Returns a list of (videoId, timestampMs) pairs, sorted by date descending.
     */
    fun scanMediaStore(context: Context): List<Pair<Long, Long>> {
        val list = mutableListOf<Pair<Long, Long>>()

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_ADDED
        )
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, sort
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val timestamp = cursor.getLong(dateIdx) * 1000L
                list.add(id to timestamp)
            }
        }
        return list
    }

    /**
     * Scans videos filtered by an optional date range.
     */
    fun scanMediaStoreWithDateFilter(
        context: Context,
        fromDate: Long?,
        toDate: Long?
    ): List<Long> {
        val list = mutableListOf<Long>()
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (fromDate != null) {
            clauses.add("${MediaStore.Video.Media.DATE_ADDED} >= ?")
            args.add((fromDate / 1000).toString())
        }
        if (toDate != null) {
            clauses.add("${MediaStore.Video.Media.DATE_ADDED} <= ?")
            args.add(((toDate + 86400000 - 1) / 1000).toString())
        }

        val selection = if (clauses.isEmpty()) null else clauses.joinToString(" AND ")
        val selectionArgs = if (args.isEmpty()) null else args.toTypedArray()
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID),
            selection, selectionArgs, sort
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            while (cursor.moveToNext()) {
                list.add(cursor.getLong(idIdx))
            }
        }
        return list
    }

    /**
     * Scans videos in a specific album (bucket), with optional date filter.
     */
    fun scanAlbumVideos(
        context: Context,
        bucketId: Long,
        fromDate: Long? = null,
        toDate: Long? = null
    ): List<Long> {
        val list = mutableListOf<Long>()
        val clauses = mutableListOf("${MediaStore.Video.Media.BUCKET_ID} = ?")
        val args = mutableListOf(bucketId.toString())

        if (fromDate != null) {
            clauses.add("${MediaStore.Video.Media.DATE_ADDED} >= ?")
            args.add((fromDate / 1000).toString())
        }
        if (toDate != null) {
            clauses.add("${MediaStore.Video.Media.DATE_ADDED} <= ?")
            args.add(((toDate + 86400000 - 1) / 1000).toString())
        }

        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID),
            clauses.joinToString(" AND "),
            args.toTypedArray(),
            sort
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            while (cursor.moveToNext()) {
                list.add(cursor.getLong(idIdx))
            }
        }
        return list
    }

    /**
     * Returns the set of bucket IDs that contain at least one video.
     * Used to decide which albums should include videos.
     */
    fun getVideoBucketIds(context: Context): Set<Long> {
        val set = mutableSetOf<Long>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media.BUCKET_ID),
            null, null, null
        )?.use { cursor ->
            val col = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            while (cursor.moveToNext()) {
                set.add(cursor.getLong(col))
            }
        }
        return set
    }
}

/**
 * Converts a MediaStore video ID to its content URI.
 */
fun Long.toVideoUri(): Uri =
    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, this)

/**
 * Returns true if this URI points to a video in the MediaStore.
 */
fun Uri.isVideoUri(): Boolean =
    toString().startsWith(MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString())

/**
 * Composable that loads a video thumbnail Bitmap asynchronously.
 */
@Composable
fun rememberVideoThumbnail(uri: Uri): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(
                        uri,
                        android.util.Size(512, 512),
                        null
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }?.let {
            bitmap = it
        }
    }
    return bitmap
}
