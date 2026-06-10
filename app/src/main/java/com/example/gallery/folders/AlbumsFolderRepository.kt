package com.example.gallery.folders

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.provider.MediaStore
import com.example.gallery.GalleryService
import com.example.gallery.SortMode
import com.example.gallery.utils.toMediaUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlbumsFolderRepository(
    private val context: Context,
    private val service: GalleryService
) : FolderSource {

    override fun getFoldersFlow(): Flow<List<FolderItem>> {
        return callbackFlow {
            val scope = CoroutineScope(Dispatchers.IO)
            fun load() {
                scope.launch {
                    trySend(getFolders())
                }
            }
            load()

            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    load()
                }
            }
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )

            awaitClose {
                context.contentResolver.unregisterContentObserver(observer)
                scope.cancel()
            }
        }.flowOn(Dispatchers.IO)
    }

    override fun getImagesFlow(
        bucketId: Long,
        prompt: String?,
        useClip: Boolean,
        fromDate: Long?,
        toDate: Long?,
        sortMode: SortMode
    ): Flow<List<Uri>> {
        return callbackFlow {
            val scope = CoroutineScope(Dispatchers.IO)
            fun load() {
                scope.launch {
                    trySend(getImages(bucketId, prompt, useClip, fromDate, toDate, sortMode))
                }
            }
            load()

            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    load()
                }
            }
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )

            awaitClose {
                context.contentResolver.unregisterContentObserver(observer)
                scope.cancel()
            }
        }.flowOn(Dispatchers.IO)
    }

    private suspend fun getFolders(): List<FolderItem> = withContext(Dispatchers.IO) {
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

    private suspend fun getImages(
        bucketId: Long,
        prompt: String?,
        useClip: Boolean,
        fromDate: Long?,
        toDate: Long?,
        sortMode: SortMode
    ): List<Uri> = withContext(Dispatchers.IO) {
        if (prompt.isNullOrBlank() && sortMode != SortMode.RELEVANCE) {
            val uris = mutableListOf<Uri>()
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selectionClauses = mutableListOf("${MediaStore.Images.Media.BUCKET_ID} = ?")
            val selectionArgs = mutableListOf(bucketId.toString())

            if (fromDate != null) {
                selectionClauses.add("${MediaStore.Images.Media.DATE_ADDED} >= ?")
                selectionArgs.add((fromDate / 1000).toString())
            }
            if (toDate != null) {
                selectionClauses.add("${MediaStore.Images.Media.DATE_ADDED} <= ?")
                val endOfDay = toDate + 86400000 - 1
                selectionArgs.add((endOfDay / 1000).toString())
            }

            val selection = selectionClauses.joinToString(" AND ")
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs.toTypedArray(),
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    uris.add(cursor.getLong(idCol).toMediaUri())
                }
            }
            return@withContext uris
        }

        val mediaIds = mutableListOf<Long>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
        val selectionArgs = arrayOf(bucketId.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                mediaIds.add(cursor.getLong(idCol))
            }
        }

        service.searchWithin(mediaIds, prompt, useClip, fromDate, toDate, sortMode)
    }
}
