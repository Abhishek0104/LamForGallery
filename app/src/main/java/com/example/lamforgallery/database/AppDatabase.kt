package com.example.lamforgallery.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [ImageEmbedding::class, Person::class, ImagePersonCrossRef::class],
    version = 6,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun imageEmbeddingDao(): ImageEmbeddingDao
    abstract fun personDao(): PersonDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        private const val DATABASE_NAME = "picquery_database"

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    // STEP 1: createFromAsset is temporarily removed to generate a clean DB.
                    // .createFromAsset("database/$DATABASE_NAME.db") 
                    .fallbackToDestructiveMigration() // Temporarily add this back to ensure it creates a new DB
                    .build()
                
                INSTANCE = instance
                instance
            }
        }
    }
}