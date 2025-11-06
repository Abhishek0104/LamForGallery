package com.example.lamforgallery.tools

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Conceptual class. This is where you write the *actual* Android logic
 * to interact with the device's MediaStore.
 *
 * This class requires Context and ContentResolver.
 * You MUST handle permissions (READ_MEDIA_IMAGES, ACCESS_MEDIA_LOCATION, etc.)
 * separately in your Activity/Fragment before calling these.
// */

class GalleryTools(private val context: Context) {

    private val TAG = "GalleryTools"
    private val resolver = context.contentResolver

    /**
     * This is no longer a stub!
     * This method now queries the MediaStore for photos where
     * the display name (filename) matches the query.
     */
    suspend fun searchPhotos(query: String): List<String> {
        // We are doing I/O (Input/Output) by querying the
        // ContentResolver, so we switch to the IO dispatcher.
        return withContext(Dispatchers.IO) {
            val photoUris = mutableListOf<String>()

            // 1. Define what columns we want to get back
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME
            )

            // 2. Define the "WHERE" clause of our query
            // We search for any filename that CONTAINS the query string
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"

            // 3. Define the value for the "?" in our "WHERE" clause
            val selectionArgs = arrayOf("%$query%")

            // 4. Define how to sort the results
            val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

            // 5. Run the query!
            Log.d(TAG, "Querying MediaStore with: $query")
            try {
                resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    // Get the column indices
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)

                    // 6. Loop through all the results (rows)
                    while (cursor.moveToNext()) {
                        // Get the ID of the photo
                        val id = cursor.getLong(idColumn)

                        // 7. Build the full "content URI" for this photo
                        val contentUri: Uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )

                        // Add the URI (as a string) to our list
                        photoUris.add(contentUri.toString())
                    }
                    Log.d(TAG, "Found ${photoUris.size} photos matching query.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying MediaStore", e)
                // Return an empty list if something goes wrong
                return@withContext emptyList<String>()
            }

            // 8. Return the list of real URIs
            photoUris
        }
    }

    suspend fun deletePhotos(photoUris: List<String>): Boolean {
        Log.d(TAG, "AGENT REQUESTED DELETE for: $photoUris")
        // In Step 4, this would launch an Intent for user permission.
        return true // Assume success
    }

    suspend fun createCollage(photoUris: List<String>, title: String): String {
        Log.d(TAG, "AGENT REQUESTED COLLAGE titled '$title' with: $photoUris")
        // Return a fake URI for the new collage
        return "content://media/external/images/media/999"
    }

    suspend fun movePhotosToAlbum(photoUris: List<String>, albumName: String): Boolean {
        Log.d(TAG, "AGENT REQUESTED MOVE to $albumName for: $photoUris")
        return true // Assume success
    }
}