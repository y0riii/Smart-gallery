package com.example.gallery.viewModels

import androidx.lifecycle.viewModelScope
import com.example.gallery.GalleryService
import com.example.gallery.folders.PersonFolderRepository
import kotlinx.coroutines.launch

class PeopleViewModel(
    private val personFolderRepository: PersonFolderRepository,
    service: GalleryService
) : FoldersViewModel(personFolderRepository, service) {
    fun renameFolder(bucketId: Long, name: String) {
        viewModelScope.launch {
            personFolderRepository.renameFolder(bucketId, name)
        }
    }

    override suspend fun performDeleteFolders(context: android.content.Context, bucketIds: List<Long>) {
        bucketIds.forEach { personId ->
            personFolderRepository.deleteFolder(personId)
        }
    }
}