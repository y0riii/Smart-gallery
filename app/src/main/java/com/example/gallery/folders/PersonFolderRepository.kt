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
import androidx.room.withTransaction
import com.example.gallery.db.entities.PersonEntity
import com.example.gallery.utils.VectorUtils

class PersonFolderRepository(
    private val personDao: PersonDao,
    private val service: GalleryService
) : FolderSource {
    private val MIN_IMAGES = 5

    /**
     * Orders people using the shared [PersonSort] rule (named A–Z, then #p placeholders
     * numerically). No favorites tier here — the repository sorts before favorites are known.
     */
    private fun sortedPeople(list: List<FolderItem>): List<FolderItem> =
        list.sortedWith(PersonSort.comparator(emptySet()))

    override fun getFoldersFlow(): Flow<List<FolderItem>> {
        return personDao.getPersonsWithMediaIdsFlow().map { persons ->
            persons.mapNotNull { (person, mediaIds) ->
                if (mediaIds.size < MIN_IMAGES) return@mapNotNull null

                val thumbnails = mediaIds.map { it.toMediaUri() }.topFourThumbnails()

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

            val thumbnails = mediaIds.map { it.toMediaUri() }.topFourThumbnails()

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

    suspend fun mergePeople(selectedIds: List<Long>, newName: String) = withContext(Dispatchers.IO) {
        if (selectedIds.size < 2) return@withContext

        // Fetch all selected PersonEntity records
        val persons = selectedIds.mapNotNull { personDao.getPersonById(it) }
        if (persons.isEmpty()) return@withContext

        // Determine the best thumbnail among them (the one with the largest thumbnailSize)
        val bestPerson = persons.maxByOrNull { it.thumbnailSize } ?: persons.first()
        val bestThumbnailPath = bestPerson.thumbnailPath
        val bestThumbnailSize = bestPerson.thumbnailSize

        // Compute combined embedding and count
        val dim = persons.firstOrNull()?.Embedding?.size ?: 0
        val combinedEmbedding = FloatArray(dim)
        var totalCount = 0

        for (person in persons) {
            totalCount += person.count
            if (person.Embedding.size == dim) {
                for (i in 0 until dim) {
                    combinedEmbedding[i] += person.Embedding[i]
                }
            }
        }

        val targetPersonId = bestPerson.id

        service.db.withTransaction {
            // Update the target person with the new name, combined embedding, combined count, and best thumbnail
            val updatedTargetPerson = PersonEntity(
                id = targetPersonId,
                name = newName,
                thumbnailPath = bestThumbnailPath,
                thumbnailSize = bestThumbnailSize,
                Embedding = combinedEmbedding,
                count = totalCount
            )
            personDao.insertPerson(updatedTargetPerson)

            // Reassign faces of all other merged people to targetPersonId
            val otherIds = selectedIds.filter { it != targetPersonId }
            for (oldId in otherIds) {
                personDao.reassignFaces(oldId, targetPersonId)
            }

            // Delete other person entities
            for (oldId in otherIds) {
                personDao.deletePerson(oldId)
            }
        }
    }
}
