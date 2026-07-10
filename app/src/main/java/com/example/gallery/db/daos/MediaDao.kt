package com.example.gallery.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.example.gallery.db.entities.ArabicOcrDoneEntity
import com.example.gallery.db.entities.MediaEntity
import com.example.gallery.db.previews.MediaDateInfo
import com.example.gallery.db.previews.MediaOcrRef

@Dao
interface MediaDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(media: List<MediaEntity>)

    @Delete
    suspend fun delete(entity: MediaEntity)

    @Query("UPDATE media_items SET ocrText = :text WHERE mediaId = :mediaId")
    suspend fun updateOcrText(mediaId: Long, text: String)

    @Query("SELECT * FROM media_items ORDER BY timestampMs DESC")
    suspend fun getAllMedia(): List<MediaEntity>

    // ===== IMAGE / VIDEO SPLIT (media_items holds both; isVideo=0 image, isVideo=1 video) =====

    /** All IMAGE row ids — the image indexer diffs against these so it never touches video rows. */
    @Query("SELECT mediaId FROM media_items WHERE isVideo = 0")
    suspend fun getAllImageIds(): List<Long>

    /** All VIDEO row ids (indexed or placeholder) — used by the video pass to detect deletions. */
    @Query("SELECT mediaId FROM media_items WHERE isVideo = 1")
    suspend fun getAllVideoIds(): List<Long>

    /** Video ids that are FULLY indexed (have an embedding) — the rest are pending/placeholders. */
    @Query("SELECT mediaId FROM media_items WHERE isVideo = 1 AND LENGTH(embedding) > 0")
    suspend fun getIndexedVideoIds(): List<Long>

    /** Which of these ids already have a media_items row (used before inserting album placeholders). */
    @Query("SELECT mediaId FROM media_items WHERE mediaId IN (:ids)")
    suspend fun getExistingMediaIds(ids: List<Long>): List<Long>

    /** Of these ids, the ones that are VIDEOS — lets a member list (AI album, etc.) pick the video
     *  content-URI scheme for its video members. DB-only (no MediaStore round-trip). */
    @Query("SELECT mediaId FROM media_items WHERE isVideo = 1 AND mediaId IN (:ids)")
    suspend fun getVideoIdsAmong(ids: List<Long>): List<Long>

    /** Insert-or-update a media row (used by the video indexer to store a video's computed features).
     *  @Upsert updates in place on conflict — no delete/re-insert — so it never cascade-deletes the
     *  video's album membership (unlike REPLACE), and it goes through the entity's FloatArray↔BLOB
     *  converter reliably. */
    @Upsert
    suspend fun upsertMedia(media: MediaEntity)

    @Query("SELECT * FROM media_items WHERE mediaId = :id LIMIT 1")
    suspend fun getMediaById(id: Long): MediaEntity?

    @Query("SELECT * FROM media_items WHERE mediaId IN (:ids)")
    suspend fun getMediaByIds(ids: List<Long>): List<MediaEntity>

    // ===== ARABIC OCR TRACKING =====

    // IMAGES only (isVideo = 0): the Arabic pass decodes an image per row, which can't work on a
    // video URI — including videos here would leave them permanently "pending" and retried forever.
    /** Image rows not yet scanned by the Arabic OCR pass (the exact "pending" set), id + text only. */
    @Query("SELECT mediaId, ocrText FROM media_items WHERE isVideo = 0 AND mediaId NOT IN (SELECT mediaId FROM arabic_ocr_done)")
    suspend fun getMediaPendingArabic(): List<MediaOcrRef>

    /** Count of image rows not yet Arabic-scanned (cheap check before enqueuing the pass). */
    @Query("SELECT COUNT(*) FROM media_items WHERE isVideo = 0 AND mediaId NOT IN (SELECT mediaId FROM arabic_ocr_done)")
    suspend fun countMediaPendingArabic(): Int

    // Videos get their Arabic scan in the SAME Arabic OCR pass, as a second phase after images. Only
    // fully-indexed videos (LENGTH(embedding) > 0) qualify — a placeholder has no frames to sample yet.
    /** Indexed-video rows not yet Arabic-scanned (the video half of the Arabic pass), id + text only. */
    @Query("SELECT mediaId, ocrText FROM media_items WHERE isVideo = 1 AND LENGTH(embedding) > 0 AND mediaId NOT IN (SELECT mediaId FROM arabic_ocr_done)")
    suspend fun getVideosPendingArabic(): List<MediaOcrRef>

    /** Count of indexed-video rows not yet Arabic-scanned (cheap check before enqueuing the pass). */
    @Query("SELECT COUNT(*) FROM media_items WHERE isVideo = 1 AND LENGTH(embedding) > 0 AND mediaId NOT IN (SELECT mediaId FROM arabic_ocr_done)")
    suspend fun countVideosPendingArabic(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markArabicOcrDone(entry: ArabicOcrDoneEntity)

    @Query("DELETE FROM arabic_ocr_done WHERE mediaId = :mediaId")
    suspend fun deleteArabicOcrDone(mediaId: Long)

    @Query("SELECT mediaId, timestampMs FROM media_items WHERE mediaId IN (:ids)")
    suspend fun getMediaDatesByIds(ids: List<Long>): List<MediaDateInfo>

    // ===== REGULAR FTS SEARCH =====

    private fun formatForFts(query: String): String {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ""

        val escapedWords = trimmed.split("\\s+".toRegex())
            .map { it.replace("\"", "\"\"") } // add * for prefix search

        return escapedWords.joinToString(" AND ")
    }

    suspend fun searchMediaFts(query: String): List<MediaEntity> {
        // Format the query using FtsQueryHelper before passing to Room
        val formattedQuery = formatForFts(query)
        if (formattedQuery.isEmpty()) {
            return getAllMedia()
        }
        return searchMediaFtsFormatted(formattedQuery)
    }

    @Query(
        """
        SELECT m.*
        FROM media_items AS m
        JOIN media_items_fts AS f ON m.rowid = f.rowid
        WHERE f.ocrText MATCH :formattedQuery
        ORDER BY m.timestampMs DESC
    """
    )
    suspend fun searchMediaFtsFormatted(formattedQuery: String): List<MediaEntity>

    // ===== SIMPLE SEARCH (fallback) =====
    @Query(
        """
        SELECT *
        FROM media_items
        WHERE ocrText LIKE '%' || :query || '%'
        ORDER BY timestampMs DESC
    """
    )
    suspend fun searchMediaSimple(query: String): List<MediaEntity>

    @Query("SELECT COUNT(*) FROM media_items")
    suspend fun count(): Int
}