package com.example.gallery.folders

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import com.example.gallery.db.daos.CategoryDao

class CategoryFolderRepository(
    private val categoryDao: CategoryDao
) : FolderSource {

    override suspend fun getFolders(): List<FolderItem> {
        val categories = categoryDao.getAllCategories()

        return categories.map { category ->
            val images = categoryDao.getImagesByCategory(category.id)

            val thumbnails = images.take(4).map {
                ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    it.mediaId
                )
            }

            FolderItem(
                bucketId = category.id,
                name = category.name,
                photoCount = images.size,
                thumbnailUris = thumbnails,
                null
            )
        }.sortedBy { it.name }
    }

    override suspend fun getImages(bucketId: Long): List<Uri> {
        val images = categoryDao.getImagesByCategory(bucketId)
        return images.map {
            ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                it.mediaId
            )
        }
    }

    suspend fun deleteCategory(bucketId: Long) {
        categoryDao.deleteCategory(bucketId)
    }
}
