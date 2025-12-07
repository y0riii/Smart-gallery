package com.example.kotlin_test

import androidx.room.*

@Dao
interface MediaDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(media: List<MediaEntity>)

    @Query("UPDATE media_items SET ocrText = :text WHERE uriString = :uriString")
    suspend fun updateOcrText(uriString: String, text: String)

    @Query("SELECT * FROM media_items ORDER BY timestampMs DESC")
    suspend fun getAllMedia(): List<MediaEntity>

    // ===== REGULAR FTS SEARCH =====
    suspend fun searchMediaFts(query: String): List<MediaEntity> {
        // Format the query using FtsQueryHelper before passing to Room
        val formattedQuery = FtsQueryHelper.formatForFts(query)
        if (formattedQuery.isEmpty()) {
            return getAllMedia()
        }
        return searchMediaFtsFormatted(formattedQuery)
    }

    @Query("""
        SELECT m.*
        FROM media_items AS m
        JOIN media_items_fts AS f ON m.rowid = f.rowid
        WHERE f.ocrText MATCH :formattedQuery
        ORDER BY m.timestampMs DESC
    """)
    suspend fun searchMediaFtsFormatted(formattedQuery: String): List<MediaEntity>

    // ===== SIMPLE SEARCH (fallback) =====
    @Query("""
        SELECT *
        FROM media_items
        WHERE ocrText LIKE '%' || :query || '%'
        ORDER BY timestampMs DESC
    """)
    suspend fun searchMediaSimple(query: String): List<MediaEntity>
}