package com.example.kotlin_test

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
    val uriString: String,

    // Flag to differentiate images (false) from videos (true)
    val isVideo: Boolean,

    // The date the item was added, used for sorting.
    val timestampMs: Long,

    // The text extracted via OCR. Nullable initially, populated after scanning.
    val ocrText: String? = null
)