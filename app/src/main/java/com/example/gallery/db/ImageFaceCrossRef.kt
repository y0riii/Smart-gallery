package com.example.gallery.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "media_face_join",
    primaryKeys = ["mediaId", "faceId"],
    foreignKeys = [
        ForeignKey(
            entity = MediaEntity::class,
            parentColumns = ["mediaId"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = FaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["faceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("faceId")]
)
data class ImageFaceCrossRef(
    val mediaId: Long,
    val faceId: Long
)