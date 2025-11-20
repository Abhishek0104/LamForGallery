// ... (Imports same as before) ...
package com.example.lamforgallery.tools

import android.content.ContentUris
import android.content.ContentValues
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.annotation.RequiresApi
import com.example.lamforgallery.ui.PermissionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import android.content.ContentResolver
import androidx.exifinterface.media.ExifInterface
import kotlin.math.ceil
import kotlin.math.sqrt

class GalleryTools(private val resolver: ContentResolver) {

    private val TAG = "GalleryTools"

    // ... (searchPhotos, getPhotosInDateRange, IntentSenders, Move, Filters, Metadata functions REMAIN THE SAME) ...
    // ... (Skipping them here to focus on the Collage update, assume they are present) ...

    suspend fun searchPhotos(query: String): List<String> {
        Log.d(TAG, "AGENT REQUESTED SEARCH for: $query")
        val photoUris = mutableListOf<String>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        return withContext(Dispatchers.IO) {
            resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    photoUris.add(contentUri.toString())
                }
            }
            photoUris
        }
    }

    // --- NEW DATE FILTER FUNCTION ---
    suspend fun getPhotosInDateRange(startMillis: Long, endMillis: Long): List<String> {
        val photoUris = mutableListOf<String>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} <= ?"
        val selectionArgs = arrayOf(startMillis.toString(), endMillis.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        return withContext(Dispatchers.IO) {
            try {
                resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        photoUris.add(ContentUris.withAppendedId(collection, id).toString())
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Error", e) }
            photoUris
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun createDeleteIntentSender(photoUris: List<String>): IntentSender? {
        val uris = photoUris.map { Uri.parse(it) }
        return MediaStore.createDeleteRequest(resolver, uris).intentSender
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun createWriteIntentSender(photoUris: List<String>): IntentSender? {
        val uris = photoUris.map { Uri.parse(it) }
        return MediaStore.createWriteRequest(resolver, uris).intentSender
    }

    suspend fun performMoveOperation(photoUris: List<String>, albumName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                for (uriString in photoUris) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$albumName")
                    }
                    resolver.update(Uri.parse(uriString), values, null, null)
                }
                true
            } catch (e: Exception) { false }
        }
    }

    // --- UPDATED COLLAGE LOGIC (SMART GRID) ---
    suspend fun createCollage(photoUris: List<String>, title: String): String? {
        if (photoUris.isEmpty()) throw Exception("No photos provided.")

        return withContext(Dispatchers.IO) {
            try {
                // 1. Load Bitmaps
                val bitmaps = photoUris.mapNotNull { loadBitmapFromUri(it) }
                if (bitmaps.isEmpty()) throw Exception("Could not load bitmaps.")

                // 2. Calculate Grid Dimensions
                val count = bitmaps.size
                val cols = if (count <= 1) 1 else 2
                val rows = ceil(count.toDouble() / cols).toInt()

                val cellWidth = 1080 / cols
                val cellHeight = 1080 / cols // Square cells look best for stories

                // 3. Create Canvas
                val finalWidth = 1080
                val finalHeight = rows * cellHeight

                val collageBitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(collageBitmap)
                canvas.drawColor(android.graphics.Color.WHITE) // White background

                // 4. Draw Images in Grid
                for (i in bitmaps.indices) {
                    val bitmap = bitmaps[i]
                    val col = i % cols
                    val row = i / cols

                    val left = col * cellWidth
                    val top = row * cellHeight
                    val right = left + cellWidth
                    val bottom = top + cellHeight

                    val destRect = Rect(left, top, right, bottom)

                    // Center-crop logic
                    val srcRect = getCenterCropRect(bitmap.width, bitmap.height)

                    canvas.drawBitmap(bitmap, srcRect, destRect, null)
                    bitmap.recycle()
                }

                // 5. Save
                val newUri = saveBitmapToMediaStore(collageBitmap, title, "Pictures/Stories")
                collageBitmap.recycle()
                newUri.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Collage failed", e)
                null
            }
        }
    }

    private fun getCenterCropRect(width: Int, height: Int): Rect {
        val size = width.coerceAtMost(height)
        val x = (width - size) / 2
        val y = (height - size) / 2
        return Rect(x, y, x + size, y + size)
    }
    // --- END UPDATED COLLAGE ---

    // ... (Rest of file: applyFilter, getPhotoMetadata, getAlbums, etc. remain unchanged) ...
    suspend fun applyFilter(photoUris: List<String>, filterName: String): List<String> {
        val filter = when (filterName.lowercase()) {
            "grayscale", "black and white", "b&w" -> FilterType.GRAYSCALE
            "sepia" -> FilterType.SEPIA
            else -> throw Exception("Unknown filter: $filterName.")
        }
        return withContext(Dispatchers.IO) {
            val newImageUris = mutableListOf<String>()
            for (uriString in photoUris) {
                val originalBitmap = loadBitmapFromUri(uriString) ?: continue
                val filteredBitmap = applyColorFilter(originalBitmap, filter)
                val originalName = getFileName(uriString) ?: "filtered_image"
                val newTitle = "${originalName}_${filterName}"
                try {
                    val newUri = saveBitmapToMediaStore(filteredBitmap, newTitle, "Pictures/Filters")
                    newImageUris.add(newUri.toString())
                } catch (e: Exception) { }
                originalBitmap.recycle()
                filteredBitmap.recycle()
            }
            newImageUris
        }
    }

    suspend fun getPhotoMetadata(photoUris: List<String>): String {
        if (photoUris.isEmpty()) return "No photos selected."
        return withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            photoUris.forEachIndexed { index, uriString ->
                try {
                    resolver.openInputStream(Uri.parse(uriString))?.use { inputStream ->
                        val exif = ExifInterface(inputStream)
                        val date = exif.getAttribute(ExifInterface.TAG_DATETIME) ?: "Unknown"
                        val make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: "Unknown"
                        val model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: ""
                        sb.append("Photo ${index + 1}: Date: $date, Camera: $make $model\n")
                    }
                } catch (e: Exception) { }
            }
            sb.toString()
        }
    }

    data class Album(val name: String, val coverUri: String, val photoCount: Int)

    suspend fun getAlbums(): List<Album> {
        val albums = mutableMapOf<String, Album>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media._ID, "COUNT(${MediaStore.Images.Media._ID}) AS photo_count")
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} IS NOT NULL")
            putStringArray(ContentResolver.QUERY_ARG_GROUP_COLUMNS, arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} ASC")
        }
        return withContext(Dispatchers.IO) {
            try {
                resolver.query(collection, projection, queryArgs, null)?.use { cursor ->
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val countCol = cursor.getColumnIndexOrThrow("photo_count")
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol)
                        val id = cursor.getLong(idCol)
                        val count = cursor.getInt(countCol)
                        val cover = ContentUris.withAppendedId(collection, id).toString()
                        albums[name] = Album(name, cover, count)
                    }
                }
            } catch (e: Exception) { return@withContext getAlbumsFallback() }
            albums.values.toList()
        }
    }

    private suspend fun getAlbumsFallback(): List<Album> {
        val albums = mutableMapOf<String, MutableList<String>>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        return withContext(Dispatchers.IO) {
            resolver.query(collection, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val bucket = cursor.getString(bucketCol) ?: "Unknown"
                    val uri = ContentUris.withAppendedId(collection, id).toString()
                    albums.getOrPut(bucket) { mutableListOf() }.add(uri)
                }
            }
            albums.map { (name, uris) -> Album(name, uris.first(), uris.size) }
        }
    }

    suspend fun getPhotos(page: Int, pageSize: Int): List<String> {
        val photoUris = mutableListOf<String>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Images.Media.DATE_TAKEN} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, pageSize)
            putInt(ContentResolver.QUERY_ARG_OFFSET, page * pageSize)
        }
        return withContext(Dispatchers.IO) {
            resolver.query(collection, projection, queryArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    photoUris.add(ContentUris.withAppendedId(collection, id).toString())
                }
            }
            photoUris
        }
    }

    suspend fun getPhotosForAlbum(albumName: String, page: Int, pageSize: Int): List<String> {
        val photoUris = mutableListOf<String>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(albumName))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Images.Media.DATE_TAKEN} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, pageSize)
            putInt(ContentResolver.QUERY_ARG_OFFSET, page * pageSize)
        }
        return withContext(Dispatchers.IO) {
            resolver.query(collection, projection, queryArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    photoUris.add(ContentUris.withAppendedId(collection, id).toString())
                }
            }
            photoUris
        }
    }

    private enum class FilterType { GRAYSCALE, SEPIA }

    private fun applyColorFilter(bitmap: Bitmap, filter: FilterType): Bitmap {
        val newBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(newBitmap)
        val paint = android.graphics.Paint()
        val matrix = android.graphics.ColorMatrix()
        when (filter) {
            FilterType.GRAYSCALE -> matrix.setSaturation(0f)
            FilterType.SEPIA -> {
                matrix.setSaturation(0f)
                val sepia = android.graphics.ColorMatrix().apply { setScale(1f, 0.95f, 0.82f, 1f) }
                matrix.postConcat(sepia)
            }
        }
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return newBitmap
    }

    private fun getFileName(uriString: String): String? {
        return try {
            val uri = Uri.parse(uriString)
            resolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)).substringBeforeLast(".") else null
            }
        } catch (e: Exception) { null }
    }

    private fun loadBitmapFromUri(uriString: String): Bitmap? {
        return try {
            val uri = Uri.parse(uriString)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.loadThumbnail(uri, Size(1080, 1080), null)
            } else {
                MediaStore.Images.Media.getBitmap(resolver, uri)
            }
        } catch (e: Exception) { null }
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap, title: String, directory: String): Uri {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val filename = "${title.replace(" ", "_")}_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, directory)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, contentValues) ?: throw Exception("Insert failed")
        val stream = resolver.openOutputStream(uri) ?: throw Exception("Stream failed")
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        stream.close()
        contentValues.clear()
        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, contentValues, null, null)
        return uri
    }
}