package com.example.gallery.folders

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.example.gallery.GalleryService
import com.example.gallery.utils.toMediaUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AlbumsFolderRepository(
    private val context: Context,
    private val service: GalleryService
) : FolderSource {

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

<<<<<<< Updated upstream
    override suspend fun getImages(bucketId: Long): List<Uri> =
        withContext(Dispatchers.IO) {

            val images = mutableListOf<Uri>()

            val projection = arrayOf(
                MediaStore.Images.Media._ID
            )

            val selection =
                "${MediaStore.Images.Media.BUCKET_ID} = ?"

            val selectionArgs = arrayOf(bucketId.toString())

            val sortOrder =
                "${MediaStore.Images.Media.DATE_ADDED} DESC"

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
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
=======
    override suspend fun getImages(
        bucketId: Long,
        prompt: String?,
        useClip: Boolean,
        fromDate: Long?,
        toDate: Long?,
        sortMode: SortMode
    ): List<Uri> = withContext(Dispatchers.IO) {
        val mediaIds = mutableListOf<Long>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
        val selectionArgs = arrayOf(bucketId.toString())

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                mediaIds.add(cursor.getLong(idCol))
            }
        }

        service.searchWithin(mediaIds, prompt, useClip, fromDate, toDate).let {
            if (sortMode == SortMode.DATE_DESC && prompt.isNullOrBlank()) {
                it
            } else {
                it
            }
        }
    }
}
>>>>>>> Stashed changes
