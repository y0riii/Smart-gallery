package com.example.gallery.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 1. The Entity (The Table Schema)
 * Represents a single row in the 'media_items' SQLite table.
 * We store URI as a String because Room cannot store Uri objects directly.
 */
@Entity(tableName = "media_items")
data class MediaEntity(
    // The URI string from the MediaStore is used as the unique primary key.
    @PrimaryKey
    val mediaId: Long,

    // The date the item was added, used for sorting.
    val timestampMs: Long,

    val isVideo: Boolean,

    val embedding: FloatArray,

    // The text extracted via OCR. Nullable initially, populated after scanning.
    val ocrText: String? = null
)