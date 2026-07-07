package com.example.gallery.folders

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.provider.MediaStore
import com.example.gallery.GalleryService
import com.example.gallery.SortMode
import com.example.gallery.utils.toMediaUri
import com.example.gallery.utils.toVideoUri
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
                scope.launch { trySend(getFolders()) }
            }
            load()

            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) { load() }
            }
            // Observe both image and video changes
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
            )
            context.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
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
                    trySend(getMedia(bucketId, prompt, useClip, fromDate, toDate, sortMode))
                }
            }
            load()

            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) { load() }
            }
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
            )
            context.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
            )

            awaitClose {
                context.contentResolver.unregisterContentObserver(observer)
                scope.cancel()
            }
        }.flowOn(Dispatchers.IO)
    }

    // ── Folder list ──────────────────────────────────────────────────────────

    private suspend fun getFolders(): List<FolderItem> = withContext(Dispatchers.IO) {
        // bucketId → list of (mediaId, bucketName, isVideo)
        val grouped = mutableMapOf<Long, MutableList<Triple<Long, String, Boolean>>>()

        // Images
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            ),
            null, null,
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} ASC, ${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val bucketId = cursor.getLong(bucketIdCol)
                grouped.getOrPut(bucketId) { mutableListOf() }
                    .add(Triple(cursor.getLong(idCol), cursor.getString(nameCol) ?: "Unknown", false))
            }
        }

        // Videos — merged into the same buckets
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.BUCKET_ID,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME
            ),
            null, null,
            "${MediaStore.Video.Media.BUCKET_DISPLAY_NAME} ASC, ${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val bucketId = cursor.getLong(bucketIdCol)
                grouped.getOrPut(bucketId) { mutableListOf() }
                    .add(Triple(cursor.getLong(idCol), cursor.getString(nameCol) ?: "Unknown", true))
            }
        }

        grouped.map { (bucketId, entries) ->
            val name = entries.first().second
            val thumbUris = entries.map { (id, _, isVideo) ->
                if (isVideo) id.toVideoUri() else id.toMediaUri()
            }.padToFour()
            FolderItem(
                bucketId = bucketId,
                name = name,
                photoCount = entries.size,
                thumbnailUris = thumbUris,
                null
            )
        }.sortedBy { it.name }
    }

    // ── Album contents ───────────────────────────────────────────────────────

    private suspend fun getMedia(
        bucketId: Long,
        prompt: String?,
        useClip: Boolean,
        fromDate: Long?,
        toDate: Long?,
        sortMode: SortMode
    ): List<Uri> = withContext(Dispatchers.IO) {
        // No search active: merge images + videos sorted by date
        if (prompt.isNullOrBlank() && sortMode != SortMode.RELEVANCE) {
            return@withContext getMergedAlbumMedia(bucketId, fromDate, toDate)
        }

        // With a prompt (semantic search): images only, videos excluded
        val imageIds = mutableListOf<Long>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.BUCKET_ID} = ?",
            arrayOf(bucketId.toString()),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) imageIds.add(cursor.getLong(idCol))
        }

        if (prompt.isNullOrBlank()) {
            // RELEVANCE with no prompt → fall back to merged date sort
            return@withContext getMergedAlbumMedia(bucketId, fromDate, toDate)
        }

        service.searchWithin(imageIds, prompt, useClip, fromDate, toDate, null)
    }

    /**
     * Merges images and videos from a bucket, sorted by date descending.
     */
    private suspend fun getMergedAlbumMedia(
        bucketId: Long,
        fromDate: Long?,
        toDate: Long?
    ): List<Uri> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<Pair<Long, Uri>>() // timestampMs → uri

        fun buildClauses(
            bucketCol: String,
            dateCol: String
        ): Pair<String, Array<String>> {
            val clauses = mutableListOf("$bucketCol = ?")
            val args = mutableListOf(bucketId.toString())
            if (fromDate != null) { clauses.add("$dateCol >= ?"); args.add((fromDate / 1000).toString()) }
            if (toDate != null) { clauses.add("$dateCol <= ?"); args.add(((toDate + 86400000 - 1) / 1000).toString()) }
            return clauses.joinToString(" AND ") to args.toTypedArray()
        }

        // Images
        val (imageSel, imageArgs) = buildClauses(
            MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.DATE_ADDED
        )
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED),
            imageSel, imageArgs,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                entries.add(cursor.getLong(dateCol) * 1000L to cursor.getLong(idCol).toMediaUri())
            }
        }

        // Videos
        val (videoSel, videoArgs) = buildClauses(
            MediaStore.Video.Media.BUCKET_ID, MediaStore.Video.Media.DATE_ADDED
        )
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED),
            videoSel, videoArgs,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                entries.add(cursor.getLong(dateCol) * 1000L to cursor.getLong(idCol).toVideoUri())
            }
        }

        entries.sortedByDescending { it.first }.map { it.second }
    }
}
