package com.example.gallery.folders

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import com.example.gallery.db.FaceDao

class PersonFolderRepository(
    private val faceDao: FaceDao
) : FolderSource {
    val MIN_IMAGES = 10

    override suspend fun getFolders(): List<FolderItem> {
        val faces = faceDao.getAllFaces()

        return faces.mapNotNull { face ->
            val images = faceDao.getImagesByFaces(listOf(face.id), 1)

            if (images.size < MIN_IMAGES) return@mapNotNull null

            val thumbnails = images.take(4).map {
                ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    it.mediaId
                )
            }

            FolderItem(
                bucketId = face.id,
                name = face.name ?: "Unknown",
                photoCount = images.size,
                thumbnailUris = thumbnails
            )
        }.sortedBy { it.name }
    }

    override suspend fun getImages(bucketId: Long): List<Uri> {
        val images = faceDao.getImagesByFaces(listOf(bucketId), 1)
        return images.map {
            ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                it.mediaId
            )
        }
    }
}