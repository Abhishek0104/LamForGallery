package com.example.lamforgallery.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ImageEmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(embedding: ImageEmbedding)

    @Query("SELECT * FROM image_embeddings WHERE is_deleted = 0")
    suspend fun getAllEmbeddings(): List<ImageEmbedding>

    @Query("SELECT * FROM image_embeddings WHERE is_deleted = 1")
    suspend fun getTrashEmbeddings(): List<ImageEmbedding>

    @Query("UPDATE image_embeddings SET is_deleted = 1 WHERE uri IN (:uris)")
    suspend fun softDelete(uris: List<String>)

    @Query("UPDATE image_embeddings SET is_deleted = 0 WHERE uri IN (:uris)")
    suspend fun restore(uris: List<String>)

    // --- Hard Delete (Permanent) for Trash ---
    @Query("DELETE FROM image_embeddings WHERE uri IN (:uris)")
    suspend fun hardDelete(uris: List<String>)

    @Query("DELETE FROM image_embeddings WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    @Query("SELECT * FROM image_embeddings WHERE uri = :uri LIMIT 1")
    suspend fun getEmbeddingByUri(uri: String): ImageEmbedding?
}