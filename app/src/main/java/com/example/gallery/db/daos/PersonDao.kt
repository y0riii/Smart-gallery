package com.example.gallery.db.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.gallery.db.entities.MediaEntity
import com.example.gallery.db.entities.MediaPersonCrossRef
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
    suspend fun getPersonById(id: Long): PersonEntity?

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(ref: MediaPersonCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRefs(refs: List<MediaPersonCrossRef>)

    @Query("DELETE FROM person")
    suspend fun deleteAllPersons()

    @Query("DELETE FROM media_person_join")
    suspend fun deleteAllCrossRefs()

    @Query("SELECT * FROM media_person_join WHERE mediaId = :mediaId")
    suspend fun getCrossRefsForMedia(mediaId: Long): List<MediaPersonCrossRef>

    @Transaction
    @Query(
        """
        SELECT p.* FROM person AS p
        JOIN media_person_join AS j ON p.id = j.personId
        WHERE j.mediaId = :mediaId
    """
    )
    suspend fun getPersonsForImage(mediaId: Long): List<PersonEntity>

    @Query(
        """
        SELECT j.mediaId FROM media_person_join AS j
        JOIN media_items AS m ON j.mediaId = m.mediaId
        WHERE j.personId = :personId
        ORDER BY m.timestampMs DESC
    """
    )
    suspend fun getImagesIdsByPersonId(personId: Long): List<Long>

    @Transaction
    @Query(
        """
        SELECT m.* FROM media_items AS m
        JOIN media_person_join AS j ON m.mediaId = j.mediaId
        WHERE j.personId = :personId
        ORDER BY m.timestampMs DESC
    """
    )
    suspend fun getImagesByPersonIdFull(personId: Long): List<MediaEntity>

    @Transaction
    @Query(
        """
    SELECT m.* FROM media_items AS m
    JOIN media_person_join AS j ON m.mediaId = j.mediaId
    JOIN person AS f ON j.personId = f.id
    WHERE f.name IN (:names)
    GROUP BY m.mediaId
    HAVING COUNT(DISTINCT f.name) = :count
    """
    )
    suspend fun getImagesByNames(names: List<String>, count: Int): List<MediaEntity>

    @Query(
        """
        SELECT j.mediaId FROM media_person_join AS j
        JOIN person AS p ON j.personId = p.id
        WHERE p.name IN (:names)
        GROUP BY j.mediaId
        HAVING COUNT(DISTINCT p.name) = :count
    """
    )
    suspend fun getMediaIdsByNames(names: List<String>, count: Int): List<Long>

    @Query("SELECT COUNT(*) FROM person")
    suspend fun countPersons(): Int

    @Query("DELETE FROM person WHERE id = :personId")
    suspend fun deletePerson(personId: Long)

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
        SELECT
            personId,
            mediaId
        FROM media_person_join
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
        SELECT
            personId,
            mediaId
        FROM media_person_join
        """
    )
    fun getPersonMediaRefsFlow(): Flow<List<PersonMediaRef>>

    @Query(
        """
        SELECT j.mediaId FROM media_person_join AS j
        JOIN media_items AS m ON j.mediaId = m.mediaId
        WHERE j.personId = :personId
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
}
