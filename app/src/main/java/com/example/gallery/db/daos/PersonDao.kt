package com.example.gallery.db.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.gallery.db.entities.MediaEntity
import com.example.gallery.db.entities.PersonEntity
import com.example.gallery.db.previews.PersonMediaRef
import com.example.gallery.db.previews.PersonPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

@Dao
interface PersonDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPerson(person: PersonEntity): Long

    @Query("UPDATE person SET name = :newName WHERE id = :personId")
    suspend fun updatePersonName(personId: Long, newName: String)

    @Query("SELECT * FROM person")
    suspend fun getAllPersons(): List<PersonEntity>

    @Query("SELECT * FROM person WHERE id = :id")
    suspend fun getPersonById(id: Long?): PersonEntity?

    @Query("SELECT name FROM person")
    suspend fun getAllNames(): List<String>

    @Query(
        """
        SELECT name FROM person
        ORDER BY name ASC
    """
    )
    fun getAllNamesFlow(): Flow<List<String>>

    /**
     * Feature 4: returns person names sorted identically to PersonFolderRepository.sortedPeople():
     * named people (not matching #p\d+) come first in A-Z order,
     * placeholder people (#p1, #p2…) come last sorted numerically by their number.
     *
     * CASE WHEN bucket: 0 = real name, 1 = placeholder.
     * CAST(SUBSTR(name,3) AS INTEGER) extracts the numeric part of '#p<n>' for numeric ordering.
     */
    @Query(
        """
        SELECT name FROM person
        ORDER BY
            CASE WHEN name GLOB '#p[0-9]*' THEN 1 ELSE 0 END ASC,
            CASE WHEN name GLOB '#p[0-9]*'
                 THEN CAST(SUBSTR(name, 3) AS INTEGER)
                 ELSE LOWER(name)
            END ASC
    """
    )
    fun getAllNamesSortedFlow(): Flow<List<String>>

    @Query("UPDATE person SET Embedding = :embedding WHERE id = :personId")
    suspend fun updateEmbeddingRaw(personId: Long, embedding: ByteArray)

    suspend fun updateEmbedding(personId: Long, embedding: FloatArray) {
        val buffer = java.nio.ByteBuffer.allocate(embedding.size * 4)
        buffer.asFloatBuffer().put(embedding)
        updateEmbeddingRaw(personId, buffer.array())
    }

    @Query("UPDATE person SET count = count + 1 WHERE id = :personId")
    suspend fun incrementPersonCounter(personId: Long)

    @Query("UPDATE person SET count = CASE WHEN count > 0 THEN count - 1 ELSE 0 END WHERE id = :personId")
    suspend fun decrementPersonCounter(personId: Long)

    @Query("UPDATE person SET count = :newCount WHERE id = :personId")
    suspend fun updatePersonCounter(personId: Long, newCount: Long)

    @Query("DELETE FROM person")
    suspend fun deleteAllPersons()

    @Transaction
    @Query(
        """
        SELECT DISTINCT p.* FROM person AS p
        JOIN faces AS f ON p.id = f.personId
        WHERE f.mediaId = :mediaId
    """
    )
    suspend fun getPersonsForImage(mediaId: Long): List<PersonEntity>

    @Query(
        """
        SELECT DISTINCT f.mediaId FROM faces AS f
        JOIN media_items AS m ON f.mediaId = m.mediaId
        WHERE f.personId = :personId
        ORDER BY m.timestampMs DESC
    """
    )
    suspend fun getImagesIdsByPersonId(personId: Long): List<Long>

    @Transaction
    @Query(
        """
        SELECT DISTINCT m.* FROM media_items AS m
        JOIN faces AS f ON m.mediaId = f.mediaId
        WHERE f.personId = :personId
        ORDER BY m.timestampMs DESC
    """
    )
    suspend fun getImagesByPersonIdFull(personId: Long): List<MediaEntity>

    @Transaction
    @Query(
        """
    SELECT m.* FROM media_items AS m
    JOIN faces AS fc ON m.mediaId = fc.mediaId
    JOIN person AS p ON fc.personId = p.id
    WHERE p.name IN (:names)
    GROUP BY m.mediaId
    HAVING COUNT(DISTINCT p.name) = :count
    """
    )
    suspend fun getImagesByNames(names: List<String>, count: Int): List<MediaEntity>


    @Query(
        """
        SELECT fc.mediaId FROM faces AS fc
        JOIN person AS p ON fc.personId = p.id
        WHERE p.name IN (:names)
        GROUP BY fc.mediaId
        HAVING COUNT(DISTINCT p.name) = :count
    """
    )
    suspend fun getMediaIdsByNames(names: List<String>, count: Int): List<Long>

    @Query("SELECT COUNT(*) FROM person")
    suspend fun countPersons(): Int

    @Query("DELETE FROM person WHERE id = :personId")
    suspend fun deletePerson(personId: Long)

    @Query("UPDATE faces SET personId = NULL WHERE personId = :personId")
    suspend fun clearFacesForPerson(personId: Long)

    @Query(
        """
        SELECT
            id,
            name,
            thumbnailPath
        FROM person
        """
    )
    suspend fun getPersonPreviews(): List<PersonPreview>

    @Query(
        """
        SELECT DISTINCT
            personId,
            mediaId
        FROM faces
        WHERE personId IS NOT NULL
        """
    )
    suspend fun getPersonMediaRefs(): List<PersonMediaRef>


    @Transaction
    suspend fun getPersonsWithMediaIds():
            List<Pair<PersonPreview, List<Long>>> {

        val persons = getPersonPreviews()
        val refs = getPersonMediaRefs()

        val mediaMap = refs.groupBy(
            keySelector = { it.personId },
            valueTransform = { it.mediaId }
        )

        return persons.map { person ->
            person to mediaMap[person.id].orEmpty()
        }
    }

    @Query(
        """
        SELECT
            id,
            name,
            thumbnailPath
        FROM person
        """
    )
    fun getPersonPreviewsFlow(): Flow<List<PersonPreview>>

    @Query(
        """
        SELECT DISTINCT
            personId,
            mediaId
        FROM faces
        WHERE personId IS NOT NULL
        """
    )
    fun getPersonMediaRefsFlow(): Flow<List<PersonMediaRef>>

    @Query(
        """
        SELECT DISTINCT f.mediaId FROM faces AS f
        JOIN media_items AS m ON f.mediaId = m.mediaId
        WHERE f.personId = :personId
        ORDER BY m.timestampMs DESC
        """
    )
    fun getImagesIdsByPersonIdFlow(personId: Long): Flow<List<Long>>

    fun getPersonsWithMediaIdsFlow(): Flow<List<Pair<PersonPreview, List<Long>>>> {
        return combine(
            getPersonPreviewsFlow(),
            getPersonMediaRefsFlow()
        ) { persons, refs ->
            val mediaMap = refs.groupBy(
                keySelector = { it.personId },
                valueTransform = { it.mediaId }
            )
            persons.map { person ->
                person to mediaMap[person.id].orEmpty()
            }
        }
    }
    @Query("UPDATE person SET thumbnailPath = :thumbnailPath, thumbnailSize = :thumbnailSize WHERE id = :personId")
    suspend fun updatePersonThumbnail(personId: Long, thumbnailPath: String, thumbnailSize: Int)

}


