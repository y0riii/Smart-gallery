package com.example.gallery.viewModels

import androidx.lifecycle.viewModelScope
import com.example.gallery.GalleryService
import com.example.gallery.folders.FolderItem
import com.example.gallery.folders.PersonFolderRepository
import com.example.gallery.folders.PersonSort
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

    fun mergeSelectedFolders(newName: String) {
        val selectedIds = selectedFolderBucketIds.toList()
        if (selectedIds.size < 2) return
        viewModelScope.launch {
            personFolderRepository.mergePeople(selectedIds, newName)
            clearFolderSelection()
        }
    }

    override suspend fun performDeleteFolders(context: android.content.Context, bucketIds: List<Long>) {
        bucketIds.forEach { personId ->
            personFolderRepository.deleteFolder(personId)
        }
    }

    // People screen adds a favorites-first tier on top of the shared ordering rule.
    override val filteredFolders: List<FolderItem>
        get() {
            val query = folderSearchQuery.trim()
            val baseList = if (query.isEmpty()) {
                folders
            } else {
                folders.filter { it.name.contains(query, ignoreCase = true) }
            }
            return baseList.sortedWith(PersonSort.comparator(favoriteFolderIds))
        }
}