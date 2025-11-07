package com.example.lamforgallery.tools

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.IntentSender
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * Contains the *real* implementations for all agent tools.
 * This class directly interacts with the Android MediaStore.
 */
class GalleryTools(private val resolver: ContentResolver) {

    private val TAG = "GalleryTools"

    /**
     * Tool 1: Searches MediaStore for photos by filename.
     */
    suspend fun searchPhotos(query: String): List<String> {
        Log.d(TAG, "AGENT REQUESTED SEARCH for: $query")
        val photoUris = mutableListOf<String>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        withContext(Dispatchers.IO) {
            resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val uri = ContentUris.withAppendedId(collection, id)
                    photoUris.add(uri.toString())
                }
            }
        }
        Log.d(TAG, "Found ${photoUris.size} photos matching query.")
        return photoUris
    }

    /**
     * Tool 2 (Step 1): Creates the permission request to delete files.
     */
    suspend fun createDeleteIntentSender(photoUris: List<String>): IntentSender? {
        Log.d(TAG, "AGENT REQUESTED DELETE for: $photoUris")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        return withContext(Dispatchers.IO) {
            val uris = photoUris.map { Uri.parse(it) }
            try {
                MediaStore.createDeleteRequest(resolver, uris).intentSender
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create delete request", e)
                null
            }
        }
    }

    /**
     * Tool 3 (Step 1): Creates the permission request to write/move files.
     */
    suspend fun createWriteIntentSender(photoUris: List<String>): IntentSender? {
        Log.d(TAG, "AGENT REQUESTED WRITE for: $photoUris")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        return withContext(Dispatchers.IO) {
            val uris = photoUris.map { Uri.parse(it) }
            try {
                MediaStore.createWriteRequest(resolver, uris).intentSender
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create write request", e)
                null
            }
        }
    }

    /**
     * Tool 3 (Step 2): Performs the *actual* move operation.
     */
    suspend fun performMoveOperation(photoUris: List<String>, albumName: String): Boolean {
        Log.d(TAG, "Performing MOVE to '$albumName' for: $photoUris")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        return withContext(Dispatchers.IO) {
            try {
                for (uriString in photoUris) {
                    val values = ContentValues().apply {
                        // We move files by changing their RELATIVE_PATH
                        // Assumes "Pictures" is the root.
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$albumName")
                    }
                    resolver.update(Uri.parse(uriString), values, null, null)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to perform move operation", e)
                false
            }
        }
    }

    /**
     * Tool 4: Creates a collage and saves it to MediaStore.
     */
    suspend fun createCollage(photoUris: List<String>, title: String): String? {
        Log.d(TAG, "AGENT REQUESTED COLLAGE '$title' for: $photoUris")
        if (photoUris.isEmpty()) {
            throw Exception("No photos provided for collage.")
        }

        return withContext(Dispatchers.IO) {
            try {
                // Load all bitmaps
                val bitmaps = photoUris.mapNotNull { loadBitmapFromUri(it, 1024) }
                if (bitmaps.isEmpty()) throw Exception("Could not load any bitmaps.")

                // Create a new, tall bitmap
                val width = bitmaps[0].width
                val totalHeight = bitmaps.sumOf { it.height }
                val newCollage = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(newCollage)

                var currentY = 0f
                for (bitmap in bitmaps) {
                    canvas.drawBitmap(bitmap, 0f, currentY, null)
                    currentY += bitmap.height
                    bitmap.recycle() // Clean up memory
                }

                // Save the new collage bitmap
                val newUri = saveBitmapToMediaStore(newCollage, title, "Pictures/Collages")
                newCollage.recycle() // Clean up memory

                Log.d(TAG, "Collage created successfully: $newUri")
                newUri.toString()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to create collage", e)
                null
            }
        }
    }

    /**
     * Tool 5: Applies a filter and saves as a new image.
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

                // Apply the filter
                val filteredBitmap = applyColorFilter(originalBitmap, filter)

                // Get original filename to create a new one
                val originalName = getFileName(uriString) ?: "filtered_image"
                val newTitle = "${originalName}_${filterName}"

                // Save the new bitmap to a "Filters" album
                try {
                    val newUri = saveBitmapToMediaStore(filteredBitmap, newTitle, "Pictures/Filters")
                    newImageUris.add(newUri.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save filtered bitmap", e)
                }

                // Cleanup
                originalBitmap.recycle()
                filteredBitmap.recycle()
            }

            Log.d(TAG, "Filter applied. New URIs: $newImageUris")
            newImageUris
        }
    }


    // --- HELPER FUNCTIONS ---

    private enum class FilterType { GRAYSCALE, SEPIA }

    /**
     * Helper to apply a ColorMatrixColorFilter to a bitmap.
     */
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
                    // Values for a standard sepia tone
                    setScale(1f, 0.95f, 0.82f, 1f)
                }
                matrix.postConcat(sepiaMatrix)
            }
        }

        paint.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return newBitmap
    }

    /**
     * Helper to get the display name from a URI.
     */
    private fun getFileName(uriString: String): String? {
        return try {
            val uri = Uri.parse(uriString)
            resolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    cursor.getString(nameIndex)
                        ?.substringBeforeLast(".") // Remove extension
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get filename for $uriString", e)
            null
        }
    }


    /**
     * Helper to load a bitmap from a URI, with optional resizing.
     */
    private fun loadBitmapFromUri(uriString: String, maxDimension: Int? = null): Bitmap? {
        return try {
            val uri = Uri.parse(uriString)
            if (maxDimension != null) {
                // Load a thumbnail to save memory
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.loadThumbnail(uri, Size(maxDimension, maxDimension), null)
                } else {
                    // Fallback for older Android (less efficient)
                    val pfd = resolver.openFileDescriptor(uri, "r")
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFileDescriptor(pfd?.fileDescriptor, null, options)

                    options.inSampleSize = calculateInSampleSize(options, maxDimension, maxDimension)
                    options.inJustDecodeBounds = false

                    pfd?.close() // Close and reopen to decode
                    val pfd2 = resolver.openFileDescriptor(uri, "r")
                    val bitmap = BitmapFactory.decodeFileDescriptor(pfd2?.fileDescriptor, null, options)
                    pfd2?.close()
                    bitmap
                }
            } else {
                // Load full-size image
                resolver.openInputStream(uri).use {
                    BitmapFactory.decodeStream(it)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap from $uriString", e)
            null
        }
    }

    // Helper for old Android thumbnail fallback
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }


    /**
     * --- MODIFIED HELPER ---
     * Helper to save a new bitmap to the MediaStore.
     * Now accepts a directory to be more flexible.
     */
    private fun saveBitmapToMediaStore(bitmap: Bitmap, title: String, directory: String = "Pictures/Collages"): Uri {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val filename = "${title.replace("[^a-zA-Z0-9_-]".toRegex(), "_")}_${System.currentTimeMillis()}.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            // Use the directory parameter here
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
            // If something fails, delete the pending entry
            newImageUri?.let { resolver.delete(it, null, null) }
            throw Exception("Failed to save bitmap: ${e.message}")
        } finally {
            outputStream?.close()
        }

        // Now that the file is written, un-pend it
        contentValues.clear()
        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(newImageUri, contentValues, null, null)

        return newImageUri
    }
}