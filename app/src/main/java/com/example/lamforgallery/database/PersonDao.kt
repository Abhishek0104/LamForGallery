package com.example.lamforgallery.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface PersonDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPerson(person: Person)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImagePersonLink(crossRef: ImagePersonCrossRef)

    @Query("SELECT * FROM people")
    suspend fun getAllPeople(): List<Person>

    @Query("""
        SELECT p.id, p.name, p.coverUri, p.faceLeft, p.faceTop, p.faceRight, p.faceBottom, p.relation,
        COUNT(DISTINCT ip.uri) as imageCount
        FROM people p
        LEFT JOIN image_people ip ON p.id = ip.personId
        GROUP BY p.id
    """)
    suspend fun getAllPeopleWithImageCount(): List<PersonUiModel>

    @Query("SELECT * FROM people WHERE id = :personId LIMIT 1")
    suspend fun getPersonById(personId: String): Person?

    @Query("SELECT * FROM people WHERE name LIKE '%' || :name || '%' LIMIT 1")
    suspend fun getPersonByName(name: String): Person?

    @Query("SELECT * FROM people WHERE relation LIKE '%' || :relation || '%' LIMIT 1")
    suspend fun getPersonByRelation(relation: String): Person?

    @Query("UPDATE people SET name = :newName, relation = :newRelation WHERE id = :personId")
    suspend fun updatePersonDetails(personId: String, newName: String, newRelation: String?)

    @Query("UPDATE people SET embedding = :newEmbeddingBytes, faceCount = :newCount WHERE id = :personId")
    suspend fun updateEmbedding(personId: String, newEmbeddingBytes: ByteArray, newCount: Int)

    @Query("SELECT uri FROM image_people WHERE personId IN (:personIds)")
    suspend fun getUrisForPeople(personIds: List<String>): List<String>

    // --- NEW: Remove a specific photo-person link ---
    @Query("DELETE FROM image_people WHERE uri = :uri AND personId = :personId")
    suspend fun removePhotoFromPerson(uri: String, personId: String)

    // --- NEW: Transaction to move a photo from one person to another ---
    @Transaction
    suspend fun movePhotoToPerson(uri: String, oldPersonId: String, newPersonId: String) {
        removePhotoFromPerson(uri, oldPersonId)
        insertImagePersonLink(ImagePersonCrossRef(uri, newPersonId))
    }

    @Transaction
    suspend fun addPhotoToPerson(uri: String, newPersonId: String) {
        insertImagePersonLink(ImagePersonCrossRef(uri, newPersonId))
    }
}