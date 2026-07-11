package com.example.gallery.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.gallery.db.entities.CollectionEntity
import com.example.gallery.db.entities.CollectionMediaCrossRef
import com.example.gallery.db.previews.CollectionPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

@Dao
interface CollectionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollection(collection: CollectionEntity): Long

    @Query("SELECT * FROM collection WHERE name = :name LIMIT 1")
    suspend fun getCollectionByName(name: String): CollectionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(ref: CollectionMediaCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRefs(refs: List<CollectionMediaCrossRef>)

    @Delete
    suspend fun deleteCrossRef(ref: CollectionMediaCrossRef)

    @Query("DELETE FROM collection WHERE id = :collectionId")
    suspend fun deleteCollection(collectionId: Long)

    @Query("UPDATE collection SET name = :name WHERE id = :collectionId")
    suspend fun updateCollectionName(collectionId: Long, name: String)

    /** Removes ALL members of a collection (used to rebuild the auto "Detected Duplicates" album). */
    @Query("DELETE FROM collection_media_join WHERE collectionId = :collectionId")
    suspend fun clearCollectionMembers(collectionId: Long)

    /** All member cross-refs of a collection (used to re-home members when merging albums). */
    @Query("SELECT * FROM collection_media_join WHERE collectionId = :collectionId")
    suspend fun getCrossRefsForCollection(collectionId: Long): List<CollectionMediaCrossRef>

    /** Names of the user albums a given media item currently belongs to (for the single-item Info panel). */
    @Query(
        """
        SELECT c.name FROM collection AS c
        JOIN collection_media_join AS j ON c.id = j.collectionId
        WHERE j.mediaId = :mediaId
        ORDER BY c.name
        """
    )
    suspend fun getCollectionNamesForMedia(mediaId: Long): List<String>

    @Query(
        """
        SELECT
            id,
            name
        FROM collection
        """
    )
    suspend fun getCollectionPreviews(): List<CollectionPreview>

    @Query(
        """
        SELECT
            id,
            name
        FROM collection
        """
    )
    fun getCollectionPreviewsFlow(): Flow<List<CollectionPreview>>

    @Query(
        """
        SELECT
            *
        FROM collection_media_join
        ORDER BY dateAddedMs DESC
        """
    )
    fun getCollectionMediaRefsFlow(): Flow<List<CollectionMediaCrossRef>>

    @Query(
        """
        SELECT mediaId FROM collection_media_join
        WHERE collectionId = :collectionId
        ORDER BY dateAddedMs DESC
        """
    )
    fun getImagesIdsByCollectionFlow(collectionId: Long): Flow<List<Long>>

    fun getCollectionsWithMediaIdsFlow(): Flow<List<Pair<CollectionPreview, List<Long>>>> {
        return combine(
            getCollectionPreviewsFlow(),
            getCollectionMediaRefsFlow()
        ) { collections, refs ->
            associateMediaIds(collections, refs, { it.id }, { it.collectionId }, { it.mediaId })
        }
    }
}
