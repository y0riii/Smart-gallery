package com.example.gallery.folders

import android.net.Uri
import androidx.core.net.toUri
import com.example.gallery.GalleryService
import com.example.gallery.SortMode
import com.example.gallery.db.daos.PersonDao
import com.example.gallery.utils.toMediaUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

class PersonFolderRepository(
    private val personDao: PersonDao,
    private val service: GalleryService
) : FolderSource {
    private val MIN_IMAGES = 10

    override fun getFoldersFlow(): Flow<List<FolderItem>> {
        return personDao.getPersonsWithMediaIdsFlow().map { persons ->
            persons.mapNotNull { (person, mediaIds) ->
                if (mediaIds.size < MIN_IMAGES) return@mapNotNull null

                val thumbnails = mediaIds.take(4).map { it.toMediaUri() }

                FolderItem(
                    bucketId = person.id,
                    name = person.name ?: "Unknown",
                    photoCount = mediaIds.size,
                    thumbnailUris = thumbnails,
                    insideFolderThumbnail = File(person.thumbnailPath).toUri()
                )
            }.sortedBy { it.name }
        }.flowOn(Dispatchers.IO)
    }

    override fun getImagesFlow(
        bucketId: Long,
        prompt: String?,
        useClip: Boolean,
        fromDate: Long?,
        toDate: Long?,
        sortMode: SortMode
    ): Flow<List<Uri>> {
        return personDao.getImagesIdsByPersonIdFlow(bucketId).map { mediaIds ->
            service.searchWithin(mediaIds, prompt, useClip, fromDate, toDate, sortMode)
        }.flowOn(Dispatchers.IO)
    }

    suspend fun renameFolder(bucketId: Long, name: String) = withContext(Dispatchers.IO) {
        personDao.updatePersonName(bucketId, name)
    }

    suspend fun getFolders(): List<FolderItem> = withContext(Dispatchers.IO) {
        val persons = personDao.getPersonsWithMediaIds()
        persons.mapNotNull { (person, mediaIds) ->
            if (mediaIds.size < MIN_IMAGES) return@mapNotNull null

            val thumbnails = mediaIds.take(4).map { it.toMediaUri() }

            FolderItem(
                bucketId = person.id,
                name = person.name ?: "Unknown",
                photoCount = mediaIds.size,
                thumbnailUris = thumbnails,
                insideFolderThumbnail = File(person.thumbnailPath).toUri()
            )
        }.sortedBy { it.name }
    }
}
