package com.example.gallery.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "collection_media_join",
    primaryKeys = ["collectionId", "mediaId"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MediaEntity::class,
            parentColumns = ["mediaId"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["mediaId"]),
        Index(value = ["collectionId"])
    ]
)
data class CollectionMediaCrossRef(
    val collectionId: Long,
    val mediaId: Long,
    val dateAddedMs: Long
)
