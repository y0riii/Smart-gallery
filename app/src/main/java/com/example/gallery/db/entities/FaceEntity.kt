package com.example.gallery.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "faces",
    foreignKeys = [
        ForeignKey(
            entity = MediaEntity::class,
            parentColumns = ["mediaId"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("mediaId"), Index("personId")]
)
data class FaceEntity(
    @PrimaryKey(autoGenerate = true)
    val faceId: Long = 0,
    val mediaId: Long,
    val embedding: FloatArray,
    val thumbnailPath: String,
    val thumbnailSize: Int,
    val personId: Long? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceEntity) return false
        return faceId == other.faceId
    }

    override fun hashCode(): Int = faceId.hashCode()
}
