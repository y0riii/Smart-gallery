package com.example.gallery.db.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.gallery.db.entities.FaceEntity

@Dao
interface FaceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFace(face: FaceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFaces(faces: List<FaceEntity>)

    @Query("SELECT * FROM faces")
    suspend fun getAllFaces(): List<FaceEntity>

    @Query("SELECT * FROM faces WHERE mediaId = :mediaId")
    suspend fun getFacesForMedia(mediaId: Long): List<FaceEntity>

    @Query("DELETE FROM faces WHERE mediaId = :mediaId")
    suspend fun deleteFacesForMedia(mediaId: Long)

    @Query("UPDATE faces SET personId = :personId WHERE faceId = :faceId")
    suspend fun updateFacePersonId(faceId: Long, personId: Long)

    @Query("UPDATE faces SET personId = NULL")
    suspend fun clearAllPersonAssignments()

    @Query("SELECT COUNT(*) FROM faces")
    suspend fun countFaces(): Int
}
