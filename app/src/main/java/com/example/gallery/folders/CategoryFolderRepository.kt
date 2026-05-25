package com.example.gallery.folders

import android.net.Uri
import com.example.gallery.db.daos.CategoryDao
import com.example.gallery.utils.toMediaUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CategoryFolderRepository(
    private val categoryDao: CategoryDao
) : FolderSource {

    override suspend fun getFolders(): List<FolderItem> = withContext(Dispatchers.IO) {
        val categories = categoryDao.getAllCategories()

        categories.map { category ->
            val images = categoryDao.getImagesByCategory(category.id)

            val thumbnails = images.take(4).map { it.mediaId.toMediaUri() }

            FolderItem(
                bucketId = category.id,
                name = category.name,
                photoCount = images.size,
                thumbnailUris = thumbnails,
                null
            )
        }.sortedBy { it.name }
    }

    override suspend fun getImages(bucketId: Long): List<Uri> = withContext(Dispatchers.IO) {
        categoryDao.getImagesByCategory(bucketId).map { it.mediaId.toMediaUri() }
    }

    suspend fun deleteCategory(bucketId: Long) = withContext(Dispatchers.IO) {
        categoryDao.deleteCategory(bucketId)
    }
}
