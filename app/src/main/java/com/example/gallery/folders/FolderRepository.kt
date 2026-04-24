package com.example.gallery.folders

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FolderRepository(private val context: Context) {

    suspend fun getFolders(): List<FolderItem> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )

        val sortOrder = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} ASC," +
                "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val grouped =
            mutableMapOf<Long, MutableList<Pair<Long, String>>>() // bucketId → [(imageId, name)]

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameCol =
                cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val imageId = cursor.getLong(idCol)
                val bucketId = cursor.getLong(bucketIdCol)
                val bucketName = cursor.getString(bucketNameCol) ?: "Unknown"
                grouped.getOrPut(bucketId) { mutableListOf() }.add(imageId to bucketName)
            }
        }

        grouped.map { (bucketId, entries) ->
            val name = entries.first().second
            val thumbUris: List<Uri> = entries.take(4).map { (id, _) ->
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
            FolderItem(
                bucketId = bucketId,
                name = name,
                photoCount = entries.size,
                thumbnailUris = thumbUris
            )
        }.sortedBy { it.name }
    }
}