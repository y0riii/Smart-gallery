package com.example.gallery.folders

import android.net.Uri

data class FolderItem(
    val bucketId: Long,
    val name: String,
    val photoCount: Int,
    val thumbnailUris: List<Uri>,   // up to 4 preview images
    val insideFolderThumbnail: Uri?,
    // True for user-created albums (Room-backed, keyed by a negative bucketId) vs. read-only device
    // MediaStore folders. Only user albums can be renamed-into / deleted / have media added.
    val isUserAlbum: Boolean = false
)

/**
 * Returns the top 4 images to preview in a folder's 2x2 mosaic tile.
 *
 * Callers pass URIs already ordered by relevance (category similarity, person face size,
 * collection/album recency), so "top 4" = the first 4. Folders with fewer than 4 images return
 * fewer — thumbnails are NOT duplicated to fill the grid; the empty slots render blank
 * (see FolderTile.ThumbCell, which draws a placeholder for a null cell).
 */
fun List<Uri>.topFourThumbnails(): List<Uri> = take(4)

// ── User-album vs device-folder bucketId encoding ──────────────────────────────────────────────
// The Albums tab shows user albums (Room collections) alongside device MediaStore folders in one
// list. Device folders are keyed by MediaStore BUCKET_ID — a 32-bit int derived from a path hashCode,
// which is NEGATIVE roughly half the time. The old convention ("bucketId < 0 ⇒ user album") therefore
// mis-routed every negative-id device folder to the user-album path, so they opened empty even though
// the list showed a count. To make collisions impossible we key user albums ABOVE the entire 32-bit
// int range, where no device BUCKET_ID (which always fits in an Int) can ever land.
private const val USER_ALBUM_BUCKET_BASE = 10_000_000_000L // 10e9, well past Int.MAX_VALUE (~2.1e9)

/** The folder-list bucketId used for a user album (Room collection [collectionId]). */
fun userAlbumBucketId(collectionId: Long): Long = USER_ALBUM_BUCKET_BASE + collectionId

/** True if [bucketId] denotes a user album; false for a device MediaStore folder (any Int value). */
fun isUserAlbumBucket(bucketId: Long): Boolean = bucketId >= USER_ALBUM_BUCKET_BASE

/** The Room collection id encoded in a user-album [bucketId] (inverse of [userAlbumBucketId]). */
fun collectionIdOf(bucketId: Long): Long = bucketId - USER_ALBUM_BUCKET_BASE