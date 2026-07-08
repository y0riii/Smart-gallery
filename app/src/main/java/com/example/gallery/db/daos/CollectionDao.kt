package com.example.gallery.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.gallery.db.entities.CollectionEntity
import com.example.gallery.db.entities.CollectionMediaCrossRef
import com.example.gallery.db.entities.MediaEntity
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

    @Query("DELETE FROM collection_media_join WHERE mediaId = :mediaId AND collectionId = :collectionId")
    suspend fun removeImageFromCollection(mediaId: Long, collectionId: Long)

    @Transaction
    @Query(
        """
        SELECT c.* FROM collection AS c
        JOIN collection_media_join AS j ON c.id = j.collectionId
        WHERE j.mediaId = :mediaId
    """
    )
    suspend fun getCollectionsForImage(mediaId: Long): List<CollectionEntity>

    @Transaction
    @Query(
        """
        SELECT m.* FROM media_items AS m
        JOIN collection_media_join AS j ON m.mediaId = j.mediaId
        WHERE j.collectionId = :collectionId
        ORDER BY j.dateAddedMs DESC
    """
    )
    suspend fun getImagesByCollection(collectionId: Long): List<MediaEntity>

    @Transaction
    @Query(
        """
        SELECT m.* FROM media_items AS m
        JOIN collection_media_join AS j ON m.mediaId = j.mediaId
        WHERE j.collectionId = :collectionId
        ORDER BY m.timestampMs DESC
    """
    )
    suspend fun getImagesByCollectionSortedByDate(collectionId: Long): List<MediaEntity>

    @Query(
        """
        SELECT mediaId FROM collection_media_join
        WHERE collectionId = :collectionId
        ORDER BY dateAddedMs DESC
    """
    )
    suspend fun getImagesIdsByCollection(collectionId: Long): List<Long>

    @Query("DELETE FROM collection WHERE id = :collectionId")
    suspend fun deleteCollection(collectionId: Long)

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
            *
        FROM collection_media_join
        ORDER BY dateAddedMs DESC
        """
    )
    suspend fun getCollectionMediaRefs(): List<CollectionMediaCrossRef>

    @Transaction
    suspend fun getCollectionsWithMediaIds():
            List<Pair<CollectionPreview, List<Long>>> {
        return associateMediaIds(
            previews = getCollectionPreviews(),
            refs = getCollectionMediaRefs(),
            previewId = { it.id },
            refOwnerId = { it.collectionId },
            refMediaId = { it.mediaId }
        )
    }

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
