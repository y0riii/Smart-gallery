package com.example.gallery.folders

import android.net.Uri

data class FolderItem(
    val bucketId: Long,
    val name: String,
    val photoCount: Int,
    val thumbnailUris: List<Uri>,   // up to 4 preview images
    val insideFolderThumbnail: Uri?
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