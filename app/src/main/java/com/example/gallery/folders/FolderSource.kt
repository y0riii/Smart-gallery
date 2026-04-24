package com.example.gallery.folders

import android.net.Uri

interface FolderSource {
    suspend fun getFolders(): List<FolderItem>

    suspend fun getImages(bucketId: Long): List<Uri>
}