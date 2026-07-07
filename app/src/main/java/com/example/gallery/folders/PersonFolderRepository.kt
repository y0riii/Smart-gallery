package com.example.gallery.folders

import android.net.Uri
import androidx.core.net.toUri
import com.example.gallery.GalleryService
import com.example.gallery.SortMode
import com.example.gallery.db.daos.PersonDao
import com.example.gallery.utils.toMediaUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

class PersonFolderRepository(
    private val personDao: PersonDao,
    private val service: GalleryService
) : FolderSource {
    private val MIN_IMAGES = 5

    // Matches auto-generated placeholder names like "#p1", "#p2", "#p10", etc.
    private val PLACEHOLDER_REGEX = Regex("^#p\\d+$", RegexOption.IGNORE_CASE)

    /** Returns true if the name is a system-generated placeholder (e.g. "#p1"). */
    private fun isPlaceholder(name: String) = PLACEHOLDER_REGEX.matches(name)

    /**
     * Sorts people into two buckets:
     *  1. Named people (not placeholders) — sorted A–Z case-insensitively.
     *  2. Placeholder people (#p1, #p2, …) — sorted numerically by their number.
     */
    private fun sortedPeople(list: List<FolderItem>): List<FolderItem> {
        val named = list.filter { !isPlaceholder(it.name) }.sortedBy { it.name.lowercase() }
        val unnamed = list.filter { isPlaceholder(it.name) }.sortedBy {
            // Extract the numeric suffix so #p2 comes before #p10
            it.name.removePrefix("#p").toIntOrNull() ?: Int.MAX_VALUE
        }
        return named + unnamed
    }

    override fun getFoldersFlow(): Flow<List<FolderItem>> {
        return personDao.getPersonsWithMediaIdsFlow().map { persons ->
            persons.mapNotNull { (person, mediaIds) ->
                if (mediaIds.size < MIN_IMAGES) return@mapNotNull null

                val thumbnails = mediaIds.map { it.toMediaUri() }.padToFour()

                FolderItem(
                    bucketId = person.id,
                    name = person.name ?: "Unknown",
                    photoCount = mediaIds.size,
                    thumbnailUris = thumbnails,
                    insideFolderThumbnail = File(person.thumbnailPath).toUri()
                )
                // Use two-bucket sort: named people A–Z, then placeholders (#p1, #p2…) numerically
            }.let { sortedPeople(it) }
        }.flowOn(Dispatchers.IO)
    }

    override fun getImagesFlow(
        bucketId: Long,
        prompt: String?,
        useClip: Boolean,
        fromDate: Long?,
        toDate: Long?,
        sortMode: SortMode
    ): Flow<List<Uri>> {
        return personDao.getImagesIdsByPersonIdFlow(bucketId).map { mediaIds ->
            service.searchWithin(mediaIds, prompt, useClip, fromDate, toDate, sortMode)
        }.flowOn(Dispatchers.IO)
    }

    suspend fun renameFolder(bucketId: Long, name: String) = withContext(Dispatchers.IO) {
        personDao.updatePersonName(bucketId, name)
    }

    suspend fun deleteFolder(bucketId: Long) = withContext(Dispatchers.IO) {
        personDao.clearFacesForPerson(bucketId)
        personDao.deletePerson(bucketId)
    }

    suspend fun getFolders(): List<FolderItem> = withContext(Dispatchers.IO) {
        val persons = personDao.getPersonsWithMediaIds()
        persons.mapNotNull { (person, mediaIds) ->
            if (mediaIds.size < MIN_IMAGES) return@mapNotNull null

            val thumbnails = mediaIds.map { it.toMediaUri() }.padToFour()

            FolderItem(
                bucketId = person.id,
                name = person.name ?: "Unknown",
                photoCount = mediaIds.size,
                thumbnailUris = thumbnails,
                insideFolderThumbnail = File(person.thumbnailPath).toUri()
            )
            // Use two-bucket sort: named people A–Z, then placeholders (#p1, #p2…) numerically
        }.let { sortedPeople(it) }
    }
}
