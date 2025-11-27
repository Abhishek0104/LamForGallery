package com.example.lamforgallery.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Defines the database table "image_embeddings".
 * Now serves as the Single Source of Truth for all image data.
 */
@Entity(tableName = "image_embeddings")
data class ImageEmbedding(
    @PrimaryKey
    val uri: String,

    @ColumnInfo(typeAffinity = ColumnInfo.Companion.BLOB)
    val embedding: FloatArray,

    @ColumnInfo(name = "location")
    val location: String? = null,

    @ColumnInfo(name = "date_taken")
    val dateTaken: Long = 0L,

    @ColumnInfo(name = "width")
    val width: Int = 0,

    @ColumnInfo(name = "height")
    val height: Int = 0,

    @ColumnInfo(name = "camera_model")
    val cameraModel: String? = null,

    // --- NEW: Soft Delete Flag ---
    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ImageEmbedding
        if (uri != other.uri) return false
        if (!embedding.contentEquals(other.embedding)) return false
        if (location != other.location) return false
        if (dateTaken != other.dateTaken) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (cameraModel != other.cameraModel) return false
        if (isDeleted != other.isDeleted) return false
        return true
    }

    override fun hashCode(): Int {
        var result = uri.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + (location?.hashCode() ?: 0)
        result = 31 * result + dateTaken.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + (cameraModel?.hashCode() ?: 0)
        result = 31 * result + isDeleted.hashCode()
        return result
    }
}