package com.example.lamforgallery.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The main Room database class for the application.
 */
@Database(
    entities = [ImageEmbedding::class], // List all tables
    version = 1,                         // Database version
    exportSchema = false                 // Don't export schema (simplifies build)
)
@TypeConverters(Converters::class) // <-- Tell Room to use our converter
abstract class AppDatabase : RoomDatabase() {

    /**
     * Connects the database to the DAO.
     */
    abstract fun imageEmbeddingDao(): ImageEmbeddingDao

    /**
     * A companion object to provide a singleton instance of the database.
     * This prevents multiple instances of the database from being opened
     * at the same time.
     */
    companion object {
        // Volatile ensures that the value of INSTANCE is always up-to-date
        // and visible to all execution threads.
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            // Return the existing instance if it's not null,
            // otherwise, create a new database instance.
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "picquery_database" // Name of the database file
                )
                    .fallbackToDestructiveMigration() // Strategy for version changes
                    .build()
                INSTANCE = instance
                // Return the new instance
                instance
            }
        }
    }
}