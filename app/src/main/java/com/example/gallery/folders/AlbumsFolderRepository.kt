package com.example.gallery.folders

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.example.gallery.utils.toMediaUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AlbumsFolderRepository(private val context: Context) : FolderSource {

    override suspend fun getFolders(): List<FolderItem> = withContext(Dispatchers.IO) {
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
            val thumbUris: List<Uri> = entries.take(4).map { (id, _) -> id.toMediaUri() }
            FolderItem(
                bucketId = bucketId,
                name = name,
                photoCount = entries.size,
                thumbnailUris = thumbUris,
                null
            )
        }.sortedBy { it.name }
    }

    override suspend fun getImages(
        bucketId: Long,
        fromDate: Long?,
        toDate: Long?,
        sortMode: SortMode
    ): List<Uri> =
        withContext(Dispatchers.IO) {

            val images = mutableListOf<Uri>()

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED
            )

            var selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
            val selectionArgs = mutableListOf(bucketId.toString())

            if (fromDate != null) {
                selection += " AND ${MediaStore.Images.Media.DATE_ADDED} >= ?"
                selectionArgs.add((fromDate / 1000).toString()) // MediaStore uses seconds
            }
            if (toDate != null) {
                selection += " AND ${MediaStore.Images.Media.DATE_ADDED} <= ?"
                selectionArgs.add((toDate / 1000).toString())
            }

            // Albums are always sorted by date added. Relevance isn't applicable here in the same way as CLIP.
            val sortOrder =
                "${MediaStore.Images.Media.DATE_ADDED} DESC"

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs.toTypedArray(),
                sortOrder
            )?.use { cursor ->

                val idCol =
                    cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)

                while (cursor.moveToNext()) {
                    images.add(cursor.getLong(idCol).toMediaUri())
                }
            }

            images
        }
}
