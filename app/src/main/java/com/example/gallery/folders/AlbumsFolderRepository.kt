package com.example.gallery.folders

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.provider.MediaStore
import com.example.gallery.GalleryService
import com.example.gallery.SearchResult
import com.example.gallery.SortMode
import com.example.gallery.db.daos.CollectionDao
import com.example.gallery.utils.VideoUtils
import com.example.gallery.utils.toMediaUri
import com.example.gallery.utils.toVideoUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Albums" tab's data source. Unlike the other folder repositories (which read Room), this one
 * reads the device's **MediaStore** directly, so albums mirror the phone's real photo/video folders
 * (Camera, Screenshots, Downloads, …) live — including files added by other apps.
 *
 * Both flows use the same reactive pattern: emit once immediately, then re-emit whenever MediaStore
 * changes. Because MediaStore has no Flow API, we bridge its callback-based [ContentObserver] into a
 * Flow with [callbackFlow] — register the observer on open, re-query on each change, and unregister
 * in [awaitClose] when the collector goes away.
 */
class AlbumsFolderRepository(
    private val context: Context,
    private val service: GalleryService,
    private val collectionDao: CollectionDao
) : FolderSource {

    // The Albums tab is a COMBINED view: user-created albums (Room-backed "collections", deletable,
    // media can be added) plus the device's own MediaStore folders (Camera/Screenshots/…, read-only).
    // User albums are keyed by an out-of-Int-range bucketId (see userAlbumBucketId) so they never
    // collide with real MediaStore bucket ids — which are 32-bit ints and often negative — and can be
    // routed apart in getImagesFlow / deletes.
    override fun getFoldersFlow(): Flow<List<FolderItem>> {
        return combine(userAlbumsFlow(), deviceFoldersFlow()) { albums, device ->
            albums + device
        }.flowOn(Dispatchers.IO)
    }

    /** User-created albums, reactively followed from Room. */
    private fun userAlbumsFlow(): Flow<List<FolderItem>> =
        collectionDao.getCollectionsWithMediaIdsFlow().map { collections ->
            // One query to find which member ids (across all albums) are videos, so thumbnails use
            // the right URI scheme — a video built as an image URI renders as a broken thumbnail.
            val videoIds = VideoUtils.videoIdsAmong(context, collections.flatMap { it.second })
            collections.map { (collection, mediaIds) ->
                FolderItem(
                    bucketId = userAlbumBucketId(collection.id),
                    name = collection.name,
                    photoCount = mediaIds.size,
                    thumbnailUris = mediaIds
                        .map { if (it in videoIds) it.toVideoUri() else it.toMediaUri() }
                        .topFourThumbnails(),
                    insideFolderThumbnail = null,
                    isUserAlbum = true
                )
            }.sortedBy { it.name }
        }

    /** The device's MediaStore folders, re-emitted on any media change via a ContentObserver. */
    private fun deviceFoldersFlow(): Flow<List<FolderItem>> {
        return callbackFlow {
            // A dedicated scope so each MediaStore change can launch a fresh (re)load off the
            // observer's callback thread; cancelled in awaitClose to avoid leaking work.
            val scope = CoroutineScope(Dispatchers.IO)
            fun load() {
                scope.launch { trySend(getFolders()) }
            }
            load() // initial emission so the UI has data before any change fires

            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) { load() }
            }
            // Observe both image and video changes (albums mix photos and videos).
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
            )
            context.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
            )

            // Runs when the collector stops: tear down the observer and cancel pending loads.
            awaitClose {
                context.contentResolver.unregisterContentObserver(observer)
                scope.cancel()
            }
        }
    }

    override fun getImagesFlow(
        bucketId: Long,
        prompt: String?,
        useClip: Boolean,
        fromDate: Long?,
        toDate: Long?,
        sortMode: SortMode,
        includeVideos: Boolean
    ): Flow<SearchResult> {
        // Out-of-Int-range bucketId → a user album (collection). Follow its membership reactively from
        // Room. (A negative bucketId is a normal device folder — MediaStore bucket ids are often < 0.)
        if (isUserAlbumBucket(bucketId)) {
            return collectionDao.getImagesIdsByCollectionFlow(collectionIdOf(bucketId)).map { mediaIds ->
                // Default browse (no search / no date filter): show ALL members, images AND videos,
                // in date-added order, building each id's correct content URI. For a search/date
                // filter, searchWithin scores the members — now including video members when the
                // "Search in videos" toggle is on (includeVideos).
                if (prompt.isNullOrBlank() && fromDate == null && toDate == null) {
                    val videoIds = VideoUtils.videoIdsAmong(context, mediaIds)
                    SearchResult.all(
                        mediaIds.map { if (it in videoIds) it.toVideoUri() else it.toMediaUri() }
                    )
                } else {
                    service.searchWithin(mediaIds, prompt, useClip, fromDate, toDate, sortMode, includeVideos)
                }
            }.flowOn(Dispatchers.IO)
        }
        return callbackFlow {
            val scope = CoroutineScope(Dispatchers.IO)
            fun load() {
                scope.launch {
                    trySend(getMedia(bucketId, prompt, useClip, fromDate, toDate, sortMode, includeVideos))
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
        // Group device folders by DISPLAY NAME (not BUCKET_ID) so the same folder name at two paths —
        // e.g. legacy "/WhatsApp/Media/WhatsApp Images" and scoped-storage
        // "Android/media/com.whatsapp/…/WhatsApp Images" — collapses into ONE album instead of two.
        val grouped = LinkedHashMap<String, MutableList<Pair<Long, Boolean>>>() // name → (mediaId, isVideo)
        val repBucketId = HashMap<String, Long>()                               // name → representative BUCKET_ID

        fun record(name: String, bucketId: Long, mediaId: Long, isVideo: Boolean) {
            grouped.getOrPut(name) { mutableListOf() }.add(mediaId to isVideo)
            // Keep the smallest bucketId as this name's stable representative (used only for routing).
            repBucketId[name] = minOf(repBucketId[name] ?: Long.MAX_VALUE, bucketId)
        }

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
                // Skip media with no bucket id — a null id reads as 0, and several such items under
                // different names would collide on representative id 0 → duplicate LazyGrid keys → crash.
                if (cursor.isNull(bucketIdCol)) continue
                record(cursor.getString(nameCol) ?: "Unknown", cursor.getLong(bucketIdCol), cursor.getLong(idCol), false)
            }
        }

        // Videos — merged into the same name groups
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
                if (cursor.isNull(bucketIdCol)) continue
                record(cursor.getString(nameCol) ?: "Unknown", cursor.getLong(bucketIdCol), cursor.getLong(idCol), true)
            }
        }

        grouped.map { (name, entries) ->
            val thumbUris = entries.map { (id, isVideo) ->
                if (isVideo) id.toVideoUri() else id.toMediaUri()
            }.topFourThumbnails()
            FolderItem(
                bucketId = repBucketId[name] ?: name.hashCode().toLong(),
                name = name,
                photoCount = entries.size,
                thumbnailUris = thumbUris,
                null
            )
        }
            // Safety net: the folder-grid keys tiles by bucketId, so never emit two with the same id.
            .distinctBy { it.bucketId }
            .sortedBy { it.name }
    }

    /**
     * Resolves a device folder's DISPLAY NAME from its representative [bucketId] (one small query).
     * Browsing then re-selects media by name, which is what merges same-named folders at different
     * paths into one album. Returns null if the bucket no longer exists.
     */
    private fun bucketDisplayName(bucketId: Long): String? {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            "${MediaStore.Images.Media.BUCKET_ID} = ?", arrayOf(bucketId.toString()), null
        )?.use { if (it.moveToFirst() && !it.isNull(0)) return it.getString(0) }
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media.BUCKET_DISPLAY_NAME),
            "${MediaStore.Video.Media.BUCKET_ID} = ?", arrayOf(bucketId.toString()), null
        )?.use { if (it.moveToFirst() && !it.isNull(0)) return it.getString(0) }
        return null
    }

    /**
     * All media (images + videos) URIs in the device folder identified by [bucketId] — resolved by
     * display name, so same-named folders combine. Used by "merge albums" to pull a device folder's
     * contents into a new user album (the device folder itself is left untouched).
     */
    suspend fun deviceFolderMedia(bucketId: Long): List<Uri> = withContext(Dispatchers.IO) {
        val name = bucketDisplayName(bucketId) ?: return@withContext emptyList()
        getMergedAlbumMedia(name, null, null)
    }

    // ── Album contents ───────────────────────────────────────────────────────

    private suspend fun getMedia(
        bucketId: Long,
        prompt: String?,
        useClip: Boolean,
        fromDate: Long?,
        toDate: Long?,
        sortMode: SortMode,
        includeVideos: Boolean
    ): SearchResult = withContext(Dispatchers.IO) {
        // Resolve the folder's display name from its representative bucketId, then select media BY NAME
        // so same-named folders at different paths are shown together.
        val bucketName = bucketDisplayName(bucketId)
            ?: return@withContext SearchResult.all(emptyList())

        // No search active: merge images + videos sorted by date (no relevance split).
        if (prompt.isNullOrBlank() && sortMode != SortMode.RELEVANCE) {
            return@withContext SearchResult.all(getMergedAlbumMedia(bucketName, fromDate, toDate))
        }

        // With a prompt (semantic/OCR search): search the folder's images, plus its videos when the
        // "Search in videos" toggle is on (searchWithin scores them from the Room video index).
        val searchIds = mutableListOf<Long>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?",
            arrayOf(bucketName),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) searchIds.add(cursor.getLong(idCol))
        }
        if (includeVideos) {
            searchIds.addAll(VideoUtils.scanAlbumVideosByName(context, bucketName, fromDate, toDate))
        }

        if (prompt.isNullOrBlank()) {
            // RELEVANCE with no prompt → fall back to merged date sort
            return@withContext SearchResult.all(getMergedAlbumMedia(bucketName, fromDate, toDate))
        }

        service.searchWithin(searchIds, prompt, useClip, fromDate, toDate, null, includeVideos)
    }

    /**
     * Merges images and videos from a device folder (selected by its display [bucketName], so
     * same-named folders at different paths combine), sorted by date descending.
     */
    private suspend fun getMergedAlbumMedia(
        bucketName: String,
        fromDate: Long?,
        toDate: Long?
    ): List<Uri> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<Pair<Long, Uri>>() // timestampMs → uri

        fun buildClauses(
            bucketCol: String,
            dateCol: String
        ): Pair<String, Array<String>> {
            val clauses = mutableListOf("$bucketCol = ?")
            val args = mutableListOf(bucketName)
            if (fromDate != null) { clauses.add("$dateCol >= ?"); args.add((fromDate / 1000).toString()) }
            if (toDate != null) { clauses.add("$dateCol <= ?"); args.add(((toDate + 86400000 - 1) / 1000).toString()) }
            return clauses.joinToString(" AND ") to args.toTypedArray()
        }

        // Images
        val (imageSel, imageArgs) = buildClauses(
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED
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
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED
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
