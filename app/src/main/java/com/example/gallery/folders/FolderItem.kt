package com.example.gallery.folders

import android.net.Uri

data class FolderItem(
    val bucketId: Long,
    val name: String,
    val photoCount: Int,
    val thumbnailUris: List<Uri>,   // up to 4 preview images
    val insideFolderThumbnail: Uri?
)

fun List<Uri>.padToFour(): List<Uri> {
    if (isEmpty()) return emptyList()
    if (size >= 4) return take(4)
    val result = ArrayList<Uri>(4)
    while (result.size < 4) {
        result.addAll(take(4 - result.size))
    }
    return result
}