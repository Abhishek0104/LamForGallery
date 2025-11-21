package com.example.lamforgallery.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [ImageEmbedding::class, Person::class, ImagePersonCrossRef::class],
    version = 5, // --- BUMPED TO 5 ---
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    // ... rest is same ...
    abstract fun imageEmbeddingDao(): ImageEmbeddingDao
    abstract fun personDao(): PersonDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "picquery_database"
                )
                    .fallbackToDestructiveMigration() // Wipes DB to handle schema change
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}