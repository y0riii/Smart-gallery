package com.example.gallery.folders

import android.net.Uri

data class FolderItem(
    val bucketId: Long,
    val name: String,
    val photoCount: Int,
    val thumbnailUris: List<Uri>   // up to 4 preview images
)