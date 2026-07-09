package com.example.gallery.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Marks that a media item has already been scanned by the Arabic OCR pass. The pass processes
 * exactly the media rows NOT present here (so nothing is scanned twice), and inserts a row here
 * immediately after each scan (so an interruption never re-scans / duplicates). A deleted image's
 * marker is removed alongside it in `deleteImageFromDb`.
 *
 * A separate table (rather than a column on MediaEntity) keeps the media_items / FTS schema — and
 * the existing indexing/search code — untouched.
 */
@Entity(tableName = "arabic_ocr_done")
data class ArabicOcrDoneEntity(
    @PrimaryKey val mediaId: Long
)
