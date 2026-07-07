package com.example.gallery.db.entities

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
Implement the following feature:
- Add a fifth tab that is called collections.
- Allow user to create a collection using a similar ui and way used in AI albums.
- Allow user to add any selected images and videos to a specific folder from the outside of the collection. The user must be able to add selected media from main page or other folders or add the selected from a collection to another, but can't have duplicates in the same collection.
- Make sure the search for folders, favcorites and all other folder features work with this one too.