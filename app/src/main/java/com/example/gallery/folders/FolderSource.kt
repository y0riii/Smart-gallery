package com.example.gallery.folders

import android.net.Uri
import com.example.gallery.SortMode

interface FolderSource {
    suspend fun getFolders(): List<FolderItem>

    suspend fun getImages(
        bucketId: Long,
        prompt: String? = null,
        useClip: Boolean = true,
        fromDate: Long? = null,
        toDate: Long? = null,
        sortMode: SortMode = SortMode.RELEVANCE
    ): List<Uri>
}