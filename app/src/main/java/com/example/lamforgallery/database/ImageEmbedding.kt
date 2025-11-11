package com.example.lamforgallery.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Defines the database table "image_embeddings".
 * Each row represents one indexed image.
 */
@Entity(tableName = "image_embeddings")
data class ImageEmbedding(
    /**
     * The unique Content URI of the image (e.g., "content://media/external/images/media/123").
     * This is our primary key.
     */
    @PrimaryKey
    val uri: String,

    /**
     * The 512-dimension embedding.
     * Stored as a BLOB in the database, automatically converted
     * by the Converters class.
     */
    @ColumnInfo(typeAffinity = ColumnInfo.Companion.BLOB)
    val embedding: FloatArray
) {
    /**
     * Custom equals/hashCode are required for data classes that contain arrays
     * to ensure comparisons work correctly (e.g., in LiveData or DiffUtil).
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ImageEmbedding
        if (uri != other.uri) return false
        if (!embedding.contentEquals(other.embedding)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = uri.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}