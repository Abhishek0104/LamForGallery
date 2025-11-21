package com.example.lamforgallery.database

import androidx.room.Entity

@Entity(tableName = "image_people", primaryKeys = ["uri", "personId"])
data class ImagePersonCrossRef(
    val uri: String,
    val personId: String
)