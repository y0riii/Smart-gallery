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
        val persons = personDao.getAllPersons()

        persons.mapNotNull { person ->
            val images = personDao.getImagesByPersons(listOf(person.id), 1)

            if (images.size < MIN_IMAGES) return@mapNotNull null

            val thumbnails = images.take(4).map { it.mediaId.toMediaUri() }

            FolderItem(
                bucketId = person.id,
                name = person.name ?: "Unknown",
                photoCount = images.size,
                thumbnailUris = thumbnails,
                insideFolderThumbnail = File(person.thumbnailPath).toUri()
            )
        }.sortedBy { it.name }
    }

    override suspend fun getImages(bucketId: Long): List<Uri> = withContext(Dispatchers.IO) {
        personDao.getImagesByPersons(listOf(bucketId), 1).map { it.mediaId.toMediaUri() }
    }

    suspend fun renameFolder(bucketId: Long, name: String) = withContext(Dispatchers.IO) {
        personDao.updatePersonName(bucketId, name)
    }
}
