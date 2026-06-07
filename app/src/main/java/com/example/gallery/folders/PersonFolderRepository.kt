package com.example.gallery.folders

import android.net.Uri
import androidx.core.net.toUri
import com.example.gallery.db.daos.PersonDao
import com.example.gallery.db.entities.MediaEntity
import com.example.gallery.utils.toMediaUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PersonFolderRepository(
    private val personDao: PersonDao
) : FolderSource {
    private val MIN_IMAGES = 10

    override suspend fun getFolders(): List<FolderItem> = withContext(Dispatchers.IO) {
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

    override suspend fun getImages(
        bucketId: Long,
        fromDate: Long?,
        toDate: Long?,
        sortMode: SortMode
    ): List<Uri> = withContext(Dispatchers.IO) {
        // Person doesn't have a specific relevance score in DB cross-ref currently, 
        // so relevance is default (usually insertion/date order).
        // We use getImagesByPersonIdFull which returns sorted by date.
        val images = personDao.getImagesByPersonIdFull(bucketId)
        
        val filtered = if (fromDate == null && toDate == null) {
            images
        } else {
            images.filter { entity: MediaEntity ->
                val afterFrom = fromDate == null || entity.timestampMs >= fromDate
                val beforeTo = toDate == null || entity.timestampMs <= toDate
                afterFrom && beforeTo
            }
        }
        filtered.map { it.mediaId.toMediaUri() }
    }

    suspend fun renameFolder(bucketId: Long, name: String) = withContext(Dispatchers.IO) {
        personDao.updatePersonName(bucketId, name)
    }
}
