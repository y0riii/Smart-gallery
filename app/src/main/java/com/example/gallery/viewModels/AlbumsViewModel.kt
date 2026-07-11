package com.example.gallery.viewModels

import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.gallery.GalleryService
import com.example.gallery.db.daos.CollectionDao
import com.example.gallery.db.entities.CollectionEntity
import com.example.gallery.db.entities.CollectionMediaCrossRef
import com.example.gallery.folders.AlbumsFolderRepository
import com.example.gallery.folders.FolderItem
import com.example.gallery.folders.collectionIdOf
import com.example.gallery.folders.isUserAlbumBucket
import kotlinx.coroutines.launch

/**
 * The Albums tab. Shows the device's MediaStore folders AND user-created albums (Room-backed
 * "collections"), and owns the CRUD for the user-created ones — create, delete, and add media.
 * User albums are keyed by an out-of-Int-range bucketId (see userAlbumBucketId) in the folder list,
 * so they never collide with device MediaStore bucket ids (which are 32-bit ints, often negative);
 * the positive collection id is used by the add-to-album picker.
 */
class AlbumsViewModel(
    private val albumsFolderRepository: AlbumsFolderRepository,
    private val collectionDao: CollectionDao,
    service: GalleryService
) : FoldersViewModel(albumsFolderRepository, service) {

    // Enable the grid's multi-select delete. Device folders can't actually be deleted, so
    // performDeleteFolders below simply skips them.
    override val canDeleteFolders: Boolean
        get() = true

    // "Remove from album" is offered only inside a user album; a device folder has no membership to
    // remove from (the file physically lives there), so it only gets "delete from device".
    override fun removeFromFolderLabel(folder: FolderItem): String? =
        if (folder.isUserAlbum) "Remove from album" else null

    fun createAlbum(name: String) {
        val trimmed = name.trim()
        // "Detected Duplicates" is reserved for the app's auto-populated duplicates album, so users
        // can't create one with that name (case-insensitive).
        if (trimmed.isBlank() ||
            trimmed.equals(GalleryService.DUPLICATES_ALBUM_NAME, ignoreCase = true)) return
        viewModelScope.launch {
            if (collectionDao.getCollectionByName(trimmed) == null) {
                collectionDao.insertCollection(CollectionEntity(name = trimmed))
                // Request the folder grid scroll up so the new album (in "Your albums", near the top)
                // is visible.
                scrollFoldersToTopEvent++
            }
        }
    }

    /**
     * Renames a user album (identified by its folder [bucketId]). No-op for a device folder, a blank
     * name, the reserved duplicates name, the reserved duplicates album itself, or a name already
     * taken by another album.
     */
    fun renameAlbum(bucketId: Long, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank() ||
            trimmed.equals(GalleryService.DUPLICATES_ALBUM_NAME, ignoreCase = true) ||
            !isUserAlbumBucket(bucketId)) return
        viewModelScope.launch {
            val targetId = collectionIdOf(bucketId)
            // The reserved auto "Detected Duplicates" album can never be renamed.
            val duplicatesAlbum = collectionDao.getCollectionByName(GalleryService.DUPLICATES_ALBUM_NAME)
            if (duplicatesAlbum != null && duplicatesAlbum.id == targetId) return@launch
            if (collectionDao.getCollectionByName(trimmed) == null) {
                collectionDao.updateCollectionName(targetId, trimmed)
            }
        }
    }

    /**
     * Adds media to an album. [albumId] is the POSITIVE collection id (from the picker). Duplicate
     * (album, media) pairs are ignored by the cross-ref primary key + IGNORE conflict strategy, so
     * an image already in the album is never added twice.
     */
    fun addMediaToAlbum(uris: Set<Uri>, albumId: Long) {
        viewModelScope.launch {
            // Ensure a media_items row exists for each item first, so the cross-ref's foreign key is
            // satisfied — otherwise adding a not-yet-indexed item (notably a video) crashes.
            service.ensureMediaRows(uris)
            val refs = uris.mapNotNull { uri ->
                uri.lastPathSegment?.toLongOrNull()?.let { mediaId ->
                    CollectionMediaCrossRef(
                        collectionId = albumId,
                        mediaId = mediaId,
                        dateAddedMs = System.currentTimeMillis()
                    )
                }
            }
            if (refs.isNotEmpty()) collectionDao.insertCrossRefs(refs)
        }
    }

    /**
     * Merges the selected folders into a NEW user album named [newName], containing the deduped union
     * of all their media (an image in several of the selected folders is added only once).
     *
     * The result is always a new user-created album. Only the selected *user* albums are deleted
     * (their members move into the new one); *system* (device) folders are never deleted — their media
     * is just copied into the new album. So: two user albums → both replaced by the merged one; two
     * device folders → a new user album, both folders kept; a mix → the user album is replaced, the
     * device folder is kept.
     */
    fun mergeAlbums(bucketIds: List<Long>, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank() ||
            trimmed.equals(GalleryService.DUPLICATES_ALBUM_NAME, ignoreCase = true) ||
            bucketIds.size < 2) return

        viewModelScope.launch {
            // 1. Gather every member (deduped, insertion order preserved), note which USER albums to
            //    delete, and collect device-media URIs so we can back them with media_items rows.
            val allIds = LinkedHashSet<Long>()
            val deviceUris = mutableListOf<Uri>()
            val userCollectionsToDelete = mutableListOf<Long>()
            for (bucketId in bucketIds) {
                if (isUserAlbumBucket(bucketId)) {
                    val cid = collectionIdOf(bucketId)
                    collectionDao.getCrossRefsForCollection(cid).forEach { allIds.add(it.mediaId) }
                    userCollectionsToDelete.add(cid)
                } else {
                    // System/device folder — copy its media in (folder itself is left untouched).
                    albumsFolderRepository.deviceFolderMedia(bucketId).forEach { uri ->
                        uri.lastPathSegment?.toLongOrNull()?.let { if (allIds.add(it)) deviceUris.add(uri) }
                    }
                }
            }
            if (allIds.isEmpty()) { clearFolderSelection(); return@launch }

            // 2. Device media may be un-indexed — insert placeholder rows so the new album's cross-ref
            //    foreign keys are satisfied. (User-album members already have rows.)
            service.ensureMediaRows(deviceUris)

            // 3. Delete the merged user albums, create the new album, add all members — atomically.
            service.db.withTransaction {
                userCollectionsToDelete.forEach { collectionDao.deleteCollection(it) }
                val name = uniqueCollectionName(trimmed)
                val newId = collectionDao.insertCollection(CollectionEntity(name = name))
                val now = System.currentTimeMillis()
                // dateAddedMs decreases with insertion order so browse order (DESC) matches this order.
                collectionDao.insertCrossRefs(
                    allIds.mapIndexed { index, mediaId ->
                        CollectionMediaCrossRef(newId, mediaId, now - index)
                    }
                )
            }
            clearFolderSelection()
            scrollFoldersToTopEvent++
        }
    }

    /** [base], or "base (2)", "base (3)"… if the name is already taken by a surviving album. */
    private suspend fun uniqueCollectionName(base: String): String {
        if (collectionDao.getCollectionByName(base) == null) return base
        var i = 2
        while (collectionDao.getCollectionByName("$base ($i)") != null) i++
        return "$base ($i)"
    }

    /** Album previews for the add-to-album picker (positive collection ids). */
    suspend fun getAlbumsList() = collectionDao.getCollectionPreviews()

    /** Deletes the currently open album; no-op if the open folder is a device folder. */
    fun deleteSelectedAlbum() {
        val folder = selectedFolder ?: return
        if (!folder.isUserAlbum) return
        viewModelScope.launch {
            collectionDao.deleteCollection(collectionIdOf(folder.bucketId))
            selectedFolder = null
        }
    }

    override suspend fun performDeleteFolders(context: android.content.Context, bucketIds: List<Long>) {
        // Only user albums are deletable; device folders (incl. those with negative bucket ids) are
        // silently skipped.
        for (bucketId in bucketIds) {
            if (isUserAlbumBucket(bucketId)) collectionDao.deleteCollection(collectionIdOf(bucketId))
        }
    }
}
