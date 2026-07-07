package com.example.gallery.viewModels

import androidx.lifecycle.viewModelScope
import com.example.gallery.GalleryService
import com.example.gallery.folders.FolderItem
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

    override val filteredFolders: List<FolderItem>
        get() {
            val query = folderSearchQuery.trim()
            val baseList = if (query.isEmpty()) {
                folders
            } else {
                folders.filter { it.name.contains(query, ignoreCase = true) }
            }
            val placeholderRegex = Regex("^#p\\d+$", RegexOption.IGNORE_CASE)
            return baseList.sortedWith { a, b ->
                val aIsFav = favoriteFolderIds.contains(a.bucketId)
                val bIsFav = favoriteFolderIds.contains(b.bucketId)

                val aGroup = when {
                    aIsFav -> 1
                    !placeholderRegex.matches(a.name) -> 2
                    else -> 3
                }
                val bGroup = when {
                    bIsFav -> 1
                    !placeholderRegex.matches(b.name) -> 2
                    else -> 3
                }

                if (aGroup != bGroup) {
                    aGroup.compareTo(bGroup)
                } else {
                    if (aGroup == 3) {
                        val aNum = a.name.removePrefix("#p").removePrefix("#P").toIntOrNull()
                        val bNum = b.name.removePrefix("#p").removePrefix("#P").toIntOrNull()
                        if (aNum != null && bNum != null) {
                            aNum.compareTo(bNum)
                        } else {
                            a.name.lowercase().compareTo(b.name.lowercase())
                        }
                    } else {
                        a.name.lowercase().compareTo(b.name.lowercase())
                    }
                }
            }
        }
}