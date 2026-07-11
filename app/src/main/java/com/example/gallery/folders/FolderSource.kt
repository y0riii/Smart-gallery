package com.example.gallery.folders

import com.example.gallery.SearchResult
import com.example.gallery.SortMode
import kotlinx.coroutines.flow.Flow

interface FolderSource {
    fun getFoldersFlow(): Flow<List<FolderItem>>

    // Emits a SearchResult (URIs + relevance boundary) so an open folder can show the same
    // "less relevant" separator that home search does when filtered by a semantic prompt.
    fun getImagesFlow(
        bucketId: Long,
        prompt: String? = null,
        useClip: Boolean = true,
        fromDate: Long? = null,
        toDate: Long? = null,
        sortMode: SortMode = SortMode.RELEVANCE,
        includeVideos: Boolean = false
    ): Flow<SearchResult>

    /**
     * Removes [mediaIds] from the folder [bucketId] WITHOUT deleting the files — i.e. drops their
     * membership (used by the "remove from album" delete option). Default no-op for folders that
     * have no membership to remove (device MediaStore folders, People).
     */
    suspend fun removeMediaFromFolder(bucketId: Long, mediaIds: List<Long>) {}
}