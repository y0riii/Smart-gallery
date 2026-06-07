package com.example.gallery.folders

import android.net.Uri

interface FolderSource {
    suspend fun getFolders(): List<FolderItem>

<<<<<<< Updated upstream
    suspend fun getImages(bucketId: Long): List<Uri>
=======
    suspend fun getImages(
        bucketId: Long,
        prompt: String? = null,
        useClip: Boolean = true,
        fromDate: Long? = null,
        toDate: Long? = null,
        sortMode: SortMode = SortMode.RELEVANCE
    ): List<Uri>
>>>>>>> Stashed changes
}