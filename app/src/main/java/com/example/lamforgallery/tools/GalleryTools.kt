package com.example.lamforgallery.tools

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

class GalleryTools(private val context: Context) {

    private val TAG = "GalleryTools"
    private val resolver = context.contentResolver

    // --- SEARCH (Unchanged) ---
    suspend fun searchPhotos(query: String): List<String> {
        // ... (existing code, no changes) ...
        return withContext(Dispatchers.IO) {
            val photoUris = mutableListOf<String>()
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME
            )
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$query%")
            val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

            Log.d(TAG, "Querying MediaStore with: $query")
            try {
                resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val contentUri: Uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        photoUris.add(contentUri.toString())
                    }
                    Log.d(TAG, "Found ${photoUris.size} photos matching query.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying MediaStore", e)
                return@withContext emptyList<String>()
            }
            photoUris
        }
    }

    // --- DELETE (Unchanged) ---
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun createDeleteIntentSender(photoUris: List<String>): IntentSender? {
        // ... (existing code, no changes) ...
        Log.d(TAG, "AGENT REQUESTED DELETE for: $photoUris")
        return withContext(Dispatchers.IO) {
            try {
                val urisToDelete = photoUris.map { Uri.parse(it) }
                MediaStore.createDeleteRequest(resolver, urisToDelete).intentSender
            } catch (e: Exception) {
                Log.e(TAG, "Error creating delete request", e)
                null
            }
        }
    }

    // --- MOVE (Unchanged) ---
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun createWriteIntentSender(photoUris: List<String>): IntentSender? {
        // ... (existing code, no changes) ...
        Log.d(TAG, "Creating WRITE request for: $photoUris")
        return withContext(Dispatchers.IO) {
            try {
                val urisToModify = photoUris.map { Uri.parse(it) }
                MediaStore.createWriteRequest(resolver, urisToModify).intentSender
            } catch (e: Exception) {
                Log.e(TAG, "Error creating write request", e)
                null
            }
        }
    }

    suspend fun performMoveOperation(photoUris: List<String>, albumName: String): Boolean {
        // ... (existing code, no changes) ...
        Log.d(TAG, "Performing MOVE to '$albumName' for: $photoUris")
        return withContext(Dispatchers.IO) {
            try {
                val newRelativePath = "Pictures/$albumName/"
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.RELATIVE_PATH, newRelativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                for (uriString in photoUris) {
                    val uri = Uri.parse(uriString)
                    val rowsUpdated = resolver.update(uri, contentValues, null, null)
                    if (rowsUpdated == 0) {
                        Log.w(TAG, "Failed to move file: $uriString")
                        return@withContext false
                    }
                }
                Log.d(TAG, "Successfully moved ${photoUris.size} photos.")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error performing move operation", e)
                false
            }
        }
    }


    // --- NEW: COLLAGE FUNCTIONS ---

    /**
     * Public tool function. Orchestrates loading, scaling, stitching, and saving.
     * Returns the new image's URI as a String.
     * Throws an exception if something goes wrong.
     */
    suspend fun createCollage(photoUris: List<String>, title: String): String {
        Log.d(TAG, "AGENT REQUESTED COLLAGE titled '$title' with: $photoUris")
        if (photoUris.isEmpty()) {
            throw Exception("No photos provided for collage.")
        }

        return withContext(Dispatchers.IO) {
            val originalBitmaps = photoUris.mapNotNull { loadBitmapFromUri(it) }
            if (originalBitmaps.isEmpty()) {
                throw Exception("Could not load any valid bitmaps from URIs.")
            }

            // --- Simple Vertical Stitch Logic ---
            // 1. Find the widest bitmap
            val maxWidth = originalBitmaps.maxOf { it.width }
            var totalHeight = 0

            // 2. Scale all bitmaps to the max width, preserving aspect ratio
            val scaledBitmaps = originalBitmaps.map {
                val scaleFactor = maxWidth.toFloat() / it.width
                val newHeight = (it.height * scaleFactor).toInt()
                totalHeight += newHeight
                Bitmap.createScaledBitmap(it, maxWidth, newHeight, true)
            }
            // --- End Logic ---

            // 3. Create a new canvas and draw all scaled bitmaps
            val collageBitmap = Bitmap.createBitmap(maxWidth, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(collageBitmap)
            canvas.drawColor(Color.WHITE) // Set a background

            var currentY = 0f
            scaledBitmaps.forEach {
                canvas.drawBitmap(it, 0f, currentY, null)
                currentY += it.height
            }

            // 4. Save the final bitmap to MediaStore
            val newImageUri = saveBitmapToMediaStore(collageBitmap, title)

            // 5. Cleanup! (Very important to avoid memory errors)
            originalBitmaps.forEach { it.recycle() }
            scaledBitmaps.forEach { it.recycle() }
            collageBitmap.recycle()

            Log.d(TAG, "Collage created successfully: $newImageUri")
            return@withContext newImageUri.toString()
        }
    }

    /**
     * Helper to load a bitmap from a "content://" URI.
     */
    private fun loadBitmapFromUri(uriString: String): Bitmap? {
        return try {
            val uri = Uri.parse(uriString)
            resolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap from $uriString", e)
            null
        }
    }

    /**
     * Helper to save a new bitmap to the MediaStore.
     */
    private fun saveBitmapToMediaStore(bitmap: Bitmap, title: String): Uri {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val directory = "Pictures/Collages" // Our new album!
        val filename = "${title.replace(" ", "_")}_${System.currentTimeMillis()}.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, directory)
            put(MediaStore.Images.Media.IS_PENDING, 1) // Mark as pending
        }

        var newImageUri: Uri? = null
        var outputStream: OutputStream? = null

        try {
            // 1. Insert the new image record
            newImageUri = resolver.insert(collection, contentValues)
                ?: throw Exception("MediaStore.insert failed")

            // 2. Get an OutputStream to the new file
            outputStream = resolver.openOutputStream(newImageUri)
                ?: throw Exception("resolver.openOutputStream failed")

            // 3. Write the bitmap data
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)) {
                throw Exception("Bitmap.compress failed")
            }
        } catch (e: Exception) {
            // If something fails, delete the partial entry
            newImageUri?.let { resolver.delete(it, null, null) }
            throw Exception("Failed to save bitmap: ${e.message}")
        } finally {
            outputStream?.close()
        }

        // 4. Mark the image as 'complete' (not pending)
        contentValues.clear()
        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(newImageUri, contentValues, null, null)

        return newImageUri
    }
}