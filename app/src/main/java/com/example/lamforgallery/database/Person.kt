package com.example.lamforgallery.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "people")
data class Person(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    val name: String,

    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val embedding: FloatArray,

    val faceCount: Int = 1,

    val coverUri: String? = null,

    val faceLeft: Float = 0f,
    val faceTop: Float = 0f,
    val faceRight: Float = 0f,
    val faceBottom: Float = 0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Person
        return id == other.id && name == other.name && embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int = id.hashCode() + name.hashCode() + embedding.contentHashCode()
}

// --- NEW: UI Model for People List (Lightweight & Correct Count) ---
data class PersonUiModel(
    val id: String,
    val name: String,
    val coverUri: String?,
    val faceLeft: Float,
    val faceTop: Float,
    val faceRight: Float,
    val faceBottom: Float,
    val imageCount: Int
)