package com.example.gallery.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface FaceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFace(face: FaceEntity): Long

    @Query("UPDATE face_recognition SET name = :newName WHERE id = :faceId")
    suspend fun updateFaceName(faceId: Long, newName: String)

    @Query("SELECT * FROM face_recognition")
    suspend fun getAllFaces(): List<FaceEntity>

    @Query("SELECT name FROM face_recognition")
    suspend fun getAllNames(): List<String>

    @Query(
        """
        SELECT name FROM face_recognition
        ORDER BY counter DESC, name ASC
    """
    )
    fun getAllNamesFlow(): Flow<List<String>>

    @Query("UPDATE face_recognition SET embedding = :newEmbedding WHERE id = :faceId")
    suspend fun updateFaceEmbeddingRaw(faceId: Long, newEmbedding: ByteArray)

    suspend fun updateFaceEmbedding(faceId: Long, embedding: FloatArray) {
        val bytes = Converters().fromFloatArray(embedding)
        updateFaceEmbeddingRaw(faceId, bytes)
    }

    @Query("UPDATE face_recognition SET counter = counter + 1 WHERE id = :faceId")
    suspend fun incrementFaceCounter(faceId: Long)

    @Query("UPDATE face_recognition SET counter = CASE WHEN counter > 0 THEN counter - 1 ELSE 0 END WHERE id = :faceId")
    suspend fun decrementFaceCounter(faceId: Long)

    @Query("UPDATE face_recognition SET counter = :newCount WHERE id = :faceId")
    suspend fun updateFaceCounter(faceId: Long, newCount: Long)

    @Query("SELECT * FROM face_recognition WHERE name = :name LIMIT 1")
    suspend fun getFaceByName(name: String): FaceEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(ref: ImageFaceCrossRef)

    @Transaction
    @Query(
        """
        SELECT f.* FROM face_recognition AS f
        JOIN media_face_join AS j ON f.id = j.faceId
        WHERE j.mediaId = :mediaId
    """
    )
    suspend fun getFacesForImage(mediaId: Long): List<FaceEntity>

    @Transaction
    @Query(
        """
        SELECT m.* FROM media_items AS m
        JOIN media_face_join AS j ON m.mediaId = j.mediaId
        WHERE j.faceId IN (:faceIds)
        GROUP BY m.mediaId
        HAVING COUNT(DISTINCT j.faceId) = :count
    """
    )
    suspend fun getImagesByFaces(faceIds: List<Long>, count: Int): List<MediaEntity>

    @Transaction
    @Query(
        """
    SELECT m.* FROM media_items AS m
    JOIN media_face_join AS j ON m.mediaId = j.mediaId
    JOIN face_recognition AS f ON j.faceId = f.id
    WHERE f.name IN (:names)
    GROUP BY m.mediaId
    HAVING COUNT(DISTINCT f.name) = :count
    """
    )
    suspend fun getImagesByNames(names: List<String>, count: Int): List<MediaEntity>

    @Query("SELECT COUNT(*) FROM face_recognition")
    suspend fun countFaces(): Int
}