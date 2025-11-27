package com.example.lamforgallery.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PersonDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPerson(person: Person)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImagePersonLink(crossRef: ImagePersonCrossRef)

    @Query("SELECT * FROM people")
    suspend fun getAllPeople(): List<Person>

    // --- NEW: Get single person by ID ---
    @Query("SELECT * FROM people WHERE id = :personId LIMIT 1")
    suspend fun getPersonById(personId: String): Person?

    @Query("SELECT * FROM people WHERE name LIKE '%' || :name || '%' LIMIT 1")
    suspend fun getPersonByName(name: String): Person?

    @Query("UPDATE people SET name = :newName WHERE id = :personId")
    suspend fun updateName(personId: String, newName: String)

    @Query("UPDATE people SET embedding = :newEmbeddingBytes, faceCount = :newCount WHERE id = :personId")
    suspend fun updateEmbedding(personId: String, newEmbeddingBytes: ByteArray, newCount: Int)

    @Query("SELECT uri FROM image_people WHERE personId IN (:personIds)")
    suspend fun getUrisForPeople(personIds: List<String>): List<String>
}