package com.example.gallery.folders

import android.net.Uri
import androidx.core.net.toUri
import com.example.gallery.db.daos.PersonDao
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

    override suspend fun getImages(bucketId: Long): List<Uri> = withContext(Dispatchers.IO) {
        personDao.getImagesIdsByPersonId(bucketId).map { it.toMediaUri() }
    }

    suspend fun renameFolder(bucketId: Long, name: String) = withContext(Dispatchers.IO) {
        personDao.updatePersonName(bucketId, name)
    }
}
