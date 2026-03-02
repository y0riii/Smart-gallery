package com.example.gallery.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4

@Fts4(contentEntity = MediaEntity::class)
@Entity(tableName = "media_items_fts")
data class FtsMediaEntity(
    @ColumnInfo(name = "ocrText")
    val ocrText: String?
)