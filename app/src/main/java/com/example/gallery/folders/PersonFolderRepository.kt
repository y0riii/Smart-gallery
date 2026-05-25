package com.example.gallery.folders

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import com.example.gallery.db.daos.PersonDao
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

            val thumbnails = images.take(4).map {
                ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    it.mediaId
                )
            }

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
        val images = personDao.getImagesByPersons(listOf(bucketId), 1)
        images.map {
            ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                it.mediaId
            )
        }
    }

    suspend fun renameFolder(bucketId: Long, name: String) = withContext(Dispatchers.IO) {
        personDao.updatePersonName(bucketId, name)
    }
}
