package com.example.lamforgallery.tools

import android.content.ContentUris
import android.content.ContentValues
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.Canvas
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
import android.content.ContentResolver // Import ContentResolver
import androidx.exifinterface.media.ExifInterface // --- NEW IMPORT ---

/**
 * This class contains the *real* Kotlin implementations for all
 * agent tools and gallery-reading functions.
 */
class GalleryTools(private val resolver: ContentResolver) {

    private val TAG = "GalleryTools"

    // --- AGENT TOOL IMPLEMENTATIONS ---

    /**
     * Searches MediaStore by filename.
     */
    suspend fun searchPhotos(query: String): List<String> {
        // ... existing code ...
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
                    // Use ContentUris.withAppendedId to build the correct URI
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    photoUris.add(contentUri.toString())
                }
            }
            Log.d(TAG, "Found ${photoUris.size} photos matching query.")
            photoUris
        }
    }

    /**
     * Creates an IntentSender for a delete request.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun createDeleteIntentSender(photoUris: List<String>): IntentSender? {
        Log.d(TAG, "AGENT REQUESTED DELETE for: $photoUris")
        val uris = photoUris.map { Uri.parse(it) }
        return MediaStore.createDeleteRequest(resolver, uris).intentSender
    }

    /**
     * Creates an IntentSender for a write (move) request.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun createWriteIntentSender(photoUris: List<String>): IntentSender? {
        Log.d(TAG, "AGENT REQUESTED WRITE for: $photoUris")
        val uris = photoUris.map { Uri.parse(it) }
        return MediaStore.createWriteRequest(resolver, uris).intentSender
    }

    /**
     * Performs the *actual* move operation *after* permission is granted.
     */
    suspend fun performMoveOperation(photoUris: List<String>, albumName: String): Boolean {
        Log.d(TAG, "Performing MOVE to '$albumName' for: $photoUris")
        return withContext(Dispatchers.IO) {
            try {
                for (uriString in photoUris) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$albumName")
                    }
                    resolver.update(Uri.parse(uriString), values, null, null)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to move files", e)
                false
            }
        }
    }

    /**
     * Creates a new collage bitmap and saves it to MediaStore.
     */
    suspend fun createCollage(photoUris: List<String>, title: String): String? {
        Log.d(TAG, "AGENT REQUESTED COLLAGE '$title' for: $photoUris")
        if (photoUris.isEmpty()) {
            throw Exception("No photos provided for collage.")
        }

        return withContext(Dispatchers.IO) {
            try {
                val bitmaps = photoUris.mapNotNull { loadBitmapFromUri(it) }
                if (bitmaps.isEmpty()) throw Exception("Could not load any bitmaps.")

                val totalHeight = bitmaps.sumOf { it.height }
                val maxWidth = bitmaps.maxOf { it.width }
                val collageBitmap = Bitmap.createBitmap(maxWidth, totalHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(collageBitmap)

                var currentY = 0f
                for (bitmap in bitmaps) {
                    canvas.drawBitmap(bitmap, 0f, currentY, null)
                    currentY += bitmap.height
                    bitmap.recycle()
                }

                val newUri = saveBitmapToMediaStore(collageBitmap, title, "Pictures/Collages")
                collageBitmap.recycle()

                Log.d(TAG, "Collage created successfully: $newUri")
                newUri.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create collage", e)
                null
            }
        }
    }

    /**
     * Applies a filter and saves new images.
     */
    suspend fun applyFilter(photoUris: List<String>, filterName: String): List<String> {
        Log.d(TAG, "AGENT REQUESTED FILTER '$filterName' for: $photoUris")
        if (photoUris.isEmpty()) {
            throw Exception("No photos provided to apply filter.")
        }

        val filter = when (filterName.lowercase()) {
            "grayscale", "black and white", "b&w" -> FilterType.GRAYSCALE
            "sepia" -> FilterType.SEPIA
            else -> throw Exception("Unknown filter: $filterName. Supported filters are 'grayscale' and 'sepia'.")
        }

        return withContext(Dispatchers.IO) {
            val newImageUris = mutableListOf<String>()

            for (uriString in photoUris) {
                val originalBitmap = loadBitmapFromUri(uriString)
                if (originalBitmap == null) {
                    Log.w(TAG, "Failed to load bitmap for filter: $uriString")
                    continue
                }

                val filteredBitmap = applyColorFilter(originalBitmap, filter)
                val originalName = getFileName(uriString) ?: "filtered_image"
                val newTitle = "${originalName}_${filterName}"

                try {
                    val newUri = saveBitmapToMediaStore(filteredBitmap, newTitle, "Pictures/Filters")
                    newImageUris.add(newUri.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save filtered bitmap", e)
                }

                originalBitmap.recycle()
                filteredBitmap.recycle()
            }

            Log.d(TAG, "Filter applied. New URIs: $newImageUris")
            newImageUris
        }
    }

    // --- NEW METADATA TOOL ---
    /**
     * Reads EXIF data from a list of URIs and returns a formatted summary string.
     */
    suspend fun getPhotoMetadata(photoUris: List<String>): String {
        Log.d(TAG, "AGENT REQUESTED METADATA for: $photoUris")
        if (photoUris.isEmpty()) return "No photos selected."

        return withContext(Dispatchers.IO) {
            val sb = StringBuilder()

            photoUris.forEachIndexed { index, uriString ->
                try {
                    val uri = Uri.parse(uriString)
                    resolver.openInputStream(uri)?.use { inputStream ->
                        val exif = ExifInterface(inputStream)
                        val date = exif.getAttribute(ExifInterface.TAG_DATETIME) ?: "Unknown Date"
                        val make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: "Unknown Make"
                        val model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: "Unknown Model"
                        val lat = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
                        val long = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)

                        val location = if (lat != null && long != null) "$lat, $long" else "Unknown Location"

                        sb.append("Photo ${index + 1}:\n")
                        sb.append("- Date: $date\n")
                        sb.append("- Camera: $make $model\n")
                        sb.append("- Location: $location\n\n")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read EXIF for $uriString", e)
                    sb.append("Photo ${index + 1}: Could not read metadata.\n\n")
                }
            }
            sb.toString()
        }
    }
    // --- END NEW TOOL ---

    // --- GALLERY/ALBUM TAB FUNCTIONS ---

    // ... existing code (Albums and Photos fetching logic remains unchanged) ...

    data class Album(
        val name: String,
        val coverUri: String,
        val photoCount: Int
    )

    suspend fun getAlbums(): List<Album> {
        Log.d(TAG, "Fetching all albums")
        val albums = mutableMapOf<String, Album>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media._ID,
            "COUNT(${MediaStore.Images.Media._ID}) AS photo_count"
        )

        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} IS NOT NULL")
            putStringArray(ContentResolver.QUERY_ARG_GROUP_COLUMNS, arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} ASC")
        }

        return withContext(Dispatchers.IO) {
            try {
                resolver.query(collection, projection, queryArgs, null)?.use { cursor ->
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val countColumn = cursor.getColumnIndexOrThrow("photo_count")

                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameColumn)
                        val id = cursor.getLong(idColumn)
                        val count = cursor.getInt(countColumn)

                        val coverUri = ContentUris.withAppendedId(collection, id).toString()

                        albums[name] = Album(name = name, coverUri = coverUri, photoCount = count)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to use GROUP BY query for albums, using fallback. ${e.message}")
                return@withContext getAlbumsFallback()
            }
            Log.d(TAG, "Found ${albums.size} albums.")
            albums.values.toList()
        }
    }

    private suspend fun getAlbumsFallback(): List<Album> {
        val albums = mutableMapOf<String, MutableList<String>>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        val sortOrder = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} ASC, ${MediaStore.Images.Media.DATE_TAKEN} DESC"

        return withContext(Dispatchers.IO) {
            resolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val bucketName = cursor.getString(bucketColumn) ?: "Unknown"
                    val uri = ContentUris.withAppendedId(collection, id).toString()

                    if (!albums.containsKey(bucketName)) {
                        albums[bucketName] = mutableListOf()
                    }
                    albums[bucketName]?.add(uri)
                }
            }

            albums.map { (name, uris) ->
                Album(name = name, coverUri = uris.first(), photoCount = uris.size)
            }
        }
    }

    suspend fun getPhotos(page: Int, pageSize: Int): List<String> {
        Log.d(TAG, "Fetching photos page: $page, size: $pageSize")
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
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val uri = ContentUris.withAppendedId(collection, id)
                    photoUris.add(uri.toString())
                }
            }
            Log.d(TAG, "Found ${photoUris.size} photos for page $page.")
            photoUris
        }
    }

    suspend fun getPhotosForAlbum(albumName: String, page: Int, pageSize: Int): List<String> {
        Log.d(TAG, "Fetching photos for album: $albumName, page: $page")
        val photoUris = mutableListOf<String>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)

        val selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(albumName)

        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Images.Media.DATE_TAKEN} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, pageSize)
            putInt(ContentResolver.QUERY_ARG_OFFSET, page * pageSize)
        }

        return withContext(Dispatchers.IO) {
            resolver.query(collection, projection, queryArgs, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val uri = ContentUris.withAppendedId(collection, id)
                    photoUris.add(uri.toString())
                }
            }
            Log.d(TAG, "Found ${photoUris.size} photos for $albumName")
            photoUris
        }
    }

    // --- PRIVATE HELPER FUNCTIONS ---

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
                val sepiaMatrix = android.graphics.ColorMatrix().apply {
                    setScale(1f, 0.95f, 0.82f, 1f)
                }
                matrix.postConcat(sepiaMatrix)
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
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                        ?.substringBeforeLast(".") // Remove extension
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get filename for $uriString", e)
            null
        }
    }

    private fun loadBitmapFromUri(uriString: String): Bitmap? {
        return try {
            val uri = Uri.parse(uriString)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // More efficient method for Android 10+
                resolver.loadThumbnail(uri, Size(1080, 1080), null)
            } else {
                // Legacy method
                MediaStore.Images.Media.getBitmap(resolver, uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap from $uriString", e)
            null
        }
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap, title: String, directory: String = "Pictures/Collages"): Uri {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val filename = "${title.replace(" ", "_")}_${System.currentTimeMillis()}.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, directory)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        var newImageUri: Uri? = null
        var outputStream: OutputStream? = null

        try {
            newImageUri = resolver.insert(collection, contentValues)
                ?: throw Exception("MediaStore.insert failed")

            outputStream = resolver.openOutputStream(newImageUri)
                ?: throw Exception("resolver.openOutputStream failed")

            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)) {
                throw Exception("Bitmap.compress failed")
            }
        } catch (e: Exception) {
            newImageUri?.let { resolver.delete(it, null, null) }
            throw Exception("Failed to save bitmap: ${e.message}")
        } finally {
            outputStream?.close()
        }

        contentValues.clear()
        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(newImageUri, contentValues, null, null)

        return newImageUri
    }
}