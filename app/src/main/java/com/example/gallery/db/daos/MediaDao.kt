package com.example.gallery.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.gallery.db.entities.MediaEntity

@Dao
interface MediaDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(media: List<MediaEntity>)

    @Delete
    suspend fun deleteAll(list: List<MediaEntity>)

    @Delete
    suspend fun delete(entity: MediaEntity)

    @Query("UPDATE media_items SET ocrText = :text WHERE mediaId = :mediaId")
    suspend fun updateOcrText(mediaId: Long, text: String)

    @Query("SELECT * FROM media_items ORDER BY timestampMs DESC")
    suspend fun getAllMedia(): List<MediaEntity>

    @Query("SELECT mediaId FROM media_items")
    suspend fun getAllMediaIds(): List<Long>

    @Query("SELECT * FROM media_items WHERE mediaId = :id LIMIT 1")
    suspend fun getMediaById(id: Long): MediaEntity?

    // ===== REGULAR FTS SEARCH =====

    private fun formatForFts(query: String): String {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ""

        val escapedWords = trimmed.split("\\s+".toRegex())
            .map { it.replace("\"", "\"\"") + "*" } // add * for prefix search

        return escapedWords.joinToString(" OR ")
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