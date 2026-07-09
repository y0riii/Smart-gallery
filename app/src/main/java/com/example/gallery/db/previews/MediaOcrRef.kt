package com.example.gallery.db.previews

/**
 * Lightweight projection (id + OCR text only, no embedding BLOB) used by the Arabic OCR pass so it
 * doesn't load every image's embedding into memory when scanning a large library.
 */
data class MediaOcrRef(
    val mediaId: Long,
    val ocrText: String?
)
