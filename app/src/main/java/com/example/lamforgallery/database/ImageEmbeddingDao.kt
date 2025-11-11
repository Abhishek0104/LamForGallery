package com.example.lamforgallery.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object (DAO) for the ImageEmbedding entity.
 * This is where all SQL queries are defined.
 */
@Dao
interface ImageEmbeddingDao {

    /**
     * Inserts a new image embedding. If an entry with the same URI
     * already exists, it will be replaced.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(embedding: ImageEmbedding)

    /**
     * Retrieves all image embeddings from the database.
     * This is the list we will search against.
     */
    @Query("SELECT * FROM image_embeddings")
    suspend fun getAllEmbeddings(): List<ImageEmbedding>

    /**
     * Deletes an embedding from the database using its URI.
     */
    @Query("DELETE FROM image_embeddings WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    /**
     * Retrieves a single embedding by its URI.
     * Useful for checking if an image is already indexed.
     */
    @Query("SELECT * FROM image_embeddings WHERE uri = :uri LIMIT 1")
    suspend fun getEmbeddingByUri(uri: String): ImageEmbedding?
}