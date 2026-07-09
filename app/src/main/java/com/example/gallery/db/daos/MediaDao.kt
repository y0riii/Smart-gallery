package com.example.gallery.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.gallery.db.entities.ArabicOcrDoneEntity
import com.example.gallery.db.entities.MediaEntity
import com.example.gallery.db.previews.MediaDateInfo
import com.example.gallery.db.previews.MediaOcrRef

@Dao
interface MediaDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(media: List<MediaEntity>)

    @Delete
    suspend fun deleteAll(list: List<MediaEntity>)

    @Delete
    suspend fun delete(entity: MediaEntity)

    @Query("DELETE FROM media_items WHERE mediaId IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE media_items SET ocrText = :text WHERE mediaId = :mediaId")
    suspend fun updateOcrText(mediaId: Long, text: String)

    @Query("SELECT * FROM media_items ORDER BY timestampMs DESC")
    suspend fun getAllMedia(): List<MediaEntity>

    @Query("SELECT mediaId FROM media_items")
    suspend fun getAllMediaIds(): List<Long>

    @Query("SELECT * FROM media_items WHERE mediaId = :id LIMIT 1")
    suspend fun getMediaById(id: Long): MediaEntity?

    @Query("SELECT * FROM media_items WHERE mediaId IN (:ids)")
    suspend fun getMediaByIds(ids: List<Long>): List<MediaEntity>

    // ===== ARABIC OCR TRACKING =====

    /** Media rows not yet scanned by the Arabic OCR pass (the exact "pending" set), id + text only. */
    @Query("SELECT mediaId, ocrText FROM media_items WHERE mediaId NOT IN (SELECT mediaId FROM arabic_ocr_done)")
    suspend fun getMediaPendingArabic(): List<MediaOcrRef>

    /** Count of media rows not yet Arabic-scanned (cheap check before enqueuing the pass). */
    @Query("SELECT COUNT(*) FROM media_items WHERE mediaId NOT IN (SELECT mediaId FROM arabic_ocr_done)")
    suspend fun countMediaPendingArabic(): Int

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