package com.example.gallery.db.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.gallery.db.Converters
import com.example.gallery.db.entities.MediaEntity
import com.example.gallery.db.entities.MediaPersonCrossRef
import com.example.gallery.db.entities.PersonEntity
import com.example.gallery.db.previews.PersonMediaRef
import com.example.gallery.db.previews.PersonPreview
import kotlinx.coroutines.flow.Flow

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
        ORDER BY counter DESC, name ASC
    """
    )
    fun getAllNamesFlow(): Flow<List<String>>

    @Query("UPDATE person SET embedding = :newEmbedding WHERE id = :personId")
    suspend fun updatePersonEmbeddingRaw(personId: Long, newEmbedding: ByteArray)

    suspend fun updatePersonEmbedding(personId: Long, embedding: FloatArray) {
        val bytes = Converters().fromFloatArray(embedding)
        updatePersonEmbeddingRaw(personId, bytes)
    }

    @Query("UPDATE person SET counter = counter + 1 WHERE id = :personId")
    suspend fun incrementPersonCounter(personId: Long)

    @Query("UPDATE person SET counter = CASE WHEN counter > 0 THEN counter - 1 ELSE 0 END WHERE id = :personId")
    suspend fun decrementPersonCounter(personId: Long)

    @Query("UPDATE person SET counter = :newCount WHERE id = :personId")
    suspend fun updatePersonCounter(personId: Long, newCount: Long)

    @Query("UPDATE person SET thumbnailPath = :thumbnailPath, thumbnailSize = :thumbnailSize WHERE id = :personId")
    suspend fun updatePersonThumbnail(personId: Long, thumbnailPath: String, thumbnailSize: Int)

    @Query("SELECT * FROM person WHERE name = :name LIMIT 1")
    suspend fun getPersonByName(name: String): PersonEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(ref: MediaPersonCrossRef)

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
        SELECT mediaId FROM media_person_join
        WHERE personId = :personId
    """
    )
    suspend fun getImagesIdsByPersonId(personId: Long): List<Long>

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

    @Query("SELECT COUNT(*) FROM person")
    suspend fun countPersons(): Int

    @Query("DELETE FROM person WHERE id = :personId")
    suspend fun deletePerson(personId: Long)

    @Query(
        """
    SELECT EXISTS(
        SELECT 1 FROM media_person_join
        WHERE mediaId = :mediaId AND personId = :personId
    )
    """
    )
    suspend fun crossRefExists(mediaId: Long, personId: Long): Boolean

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
}