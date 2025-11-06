package com.example.lamforgallery.tools

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    // --- DELETE (Renamed) ---
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun createDeleteIntentSender(photoUris: List<String>): IntentSender? {
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

    // --- MOVE (NEW FUNCTIONS) ---

    /**
     * Step 1: Get an IntentSender to ask the user for *write* permission
     * for the given URIs.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun createWriteIntentSender(photoUris: List<String>): IntentSender? {
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

    /**
     * Step 2: After permission is granted, perform the *actual* move
     * by updating the file's MediaStore entry.
     */
    suspend fun performMoveOperation(photoUris: List<String>, albumName: String): Boolean {
        Log.d(TAG, "Performing MOVE to '$albumName' for: $photoUris")
        return withContext(Dispatchers.IO) {
            try {
                // The "album" is just a directory. We update the file's path.
                // We assume the album is inside the standard "Pictures" directory.
                val newRelativePath = "Pictures/$albumName/"

                val contentValues = ContentValues().apply {
                    // This is the column that defines the album/folder
                    put(MediaStore.Images.Media.RELATIVE_PATH, newRelativePath)
                    // We also set IS_PENDING to 0 to mark the change as complete.
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }

                for (uriString in photoUris) {
                    val uri = Uri.parse(uriString)
                    // We have permission now, so we can run the update.
                    val rowsUpdated = resolver.update(uri, contentValues, null, null)
                    if (rowsUpdated == 0) {
                        Log.w(TAG, "Failed to move file: $uriString")
                        // If any file fails, we can report a partial or full failure.
                        // For simplicity, we'll report full failure.
                        return@withContext false
                    }
                }
                Log.d(TAG, "Successfully moved ${photoUris.size} photos.")
                true // All files moved successfully
            } catch (e: Exception) {
                Log.e(TAG, "Error performing move operation", e)
                false
            }
        }
    }

    // --- STUBS for our next steps ---
    suspend fun createCollage(photoUris: List<String>, title: String): String {
        Log.d(TAG, "AGENT REQUESTED COLLAGE titled '$title' with: $photoUris")
        return "content://media/external/images/media/999"
    }
}