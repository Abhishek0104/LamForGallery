package com.example.lamforgallery.database

import androidx.room.TypeConverter
import java.nio.ByteBuffer

/**
 * Room TypeConverters to allow storing complex types (like FloatArray) in the database.
 * SQLite does not have a "FloatArray" type, so we convert it to/from a
 * ByteArray (which is stored as a BLOB).
 */
class Converters {

    /**
     * Converts a FloatArray into a ByteArray.
     */
    @TypeConverter
    fun fromFloatArray(array: FloatArray): ByteArray {
        val byteBuffer = ByteBuffer.allocate(array.size * 4) // 4 bytes per float
        val floatBuffer = byteBuffer.asFloatBuffer()
        floatBuffer.put(array)
        return byteBuffer.array()
    }

    /**
     * Converts a ByteArray back into a FloatArray.
     */
    @TypeConverter
    fun toFloatArray(bytes: ByteArray): FloatArray {
        val byteBuffer = ByteBuffer.wrap(bytes)
        val floatBuffer = byteBuffer.asFloatBuffer()
        val floatArray = FloatArray(floatBuffer.remaining())
        floatBuffer.get(floatArray)
        return floatArray
    }
}