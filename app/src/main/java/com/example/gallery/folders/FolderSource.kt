package com.example.gallery.folders

import android.net.Uri
import com.example.gallery.SortMode
import kotlinx.coroutines.flow.Flow

interface FolderSource {
    fun getFoldersFlow(): Flow<List<FolderItem>>

    fun getImagesFlow(
        bucketId: Long,
        prompt: String? = null,
        useClip: Boolean = true,
        fromDate: Long? = null,
        toDate: Long? = null,
        sortMode: SortMode = SortMode.RELEVANCE
    ): Flow<List<Uri>>
}