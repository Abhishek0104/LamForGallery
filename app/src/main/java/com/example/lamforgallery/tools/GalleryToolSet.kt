package com.example.lamforgallery.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import android.content.IntentSender
import android.os.Build
import android.util.Log
import android.content.Context
import com.example.lamforgallery.database.ImageEmbeddingDao
import com.example.lamforgallery.database.PersonDao
import com.example.lamforgallery.ml.ClipTokenizer
import com.example.lamforgallery.ml.TextEncoder
import com.example.lamforgallery.ui.PermissionType
import com.example.lamforgallery.utils.CleanupManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Koog-compatible ToolSet for gallery operations.
 * Wraps GalleryTools functionality with Koog's annotation-based tool system.
 */
@LLMDescription("Tools for searching, organizing, and editing photos in the device gallery")
class GalleryToolSet(
    private val context: Context,
    private val galleryTools: GalleryTools,
    private val imageEmbeddingDao: ImageEmbeddingDao,
    private val personDao: PersonDao,
    private val clipTokenizer: ClipTokenizer,
    private val textEncoder: TextEncoder,
    private val cleanupManager: CleanupManager,
    private val onSearchResults: (List<String>) -> Unit,
    private val getLastSearchResults: () -> List<String>,
    private val getLastManualSelection: () -> List<String>,
    private val onPermissionRequired: (IntentSender, PermissionType, String, Map<String, Any>) -> Unit,
    private val onMessage: (String, List<String>?, Boolean, Boolean) -> Unit,
    private val onCleanupGroups: (List<CleanupManager.DuplicateGroup>) -> Unit,
    private val onGalleryChanged: suspend () -> Unit
) : ToolSet {

    private val TAG = "GalleryToolSet"

    private data class SearchResult(val uri: String, val similarity: Float)

    // --- TOOL 1: SEARCH PHOTOS ---
    @Serializable
    data class SearchPhotosArgs(
        @property:LLMDescription("The search query for semantic/AI-powered search (e.g., 'sunset', 'cat', 'receipts') using image and text embeddings. Don't include information which were used in other arguments like date/location/people.")
        val query: String = "",
        @property:LLMDescription("Start date in YYYY-MM-DD format for date range filtering")
        val start_date: String? = null,
        @property:LLMDescription("End date in YYYY-MM-DD format for date range filtering")
        val end_date: String? = null,
        @property:LLMDescription("Location filter (city, country, or place name)")
        val location: String? = null,
        @property:LLMDescription("List of person names to filter by. ALWAYS use this for names like 'Modi', 'Alice', 'Me'.")
        val people: List<String> = emptyList()
    )

    @Tool
    @LLMDescription("Searches for photos using AI-powered semantic search with optional filters for date, location, and people. Returns a list of image URIs that match the search criteria.")
    suspend fun searchPhotos(args: SearchPhotosArgs): String {
        return try {
            val argsJson = Json.encodeToString(args)
            Log.d(TAG, "🔍 searchPhotos called with args: $argsJson")

            val params = GalleryTools.SearchPhotosParams(
                query = args.query,
                startDate = args.start_date,
                endDate = args.end_date,
                location = args.location,
                people = args.people
            )

            val foundUris = galleryTools.searchPhotos(params)

            // Update search results cache
            onSearchResults(foundUris)

            if (foundUris.isEmpty()) {
                // onMessage("I couldn't find any matching photos.", null, false, false)
                """{"count": 0, "imageUris": [], "message": "No matching photos found"}"""
            } else {
                onMessage("", foundUris, true, false)
                val urisJson = foundUris.joinToString(",") { "\"$it\"" }
                """{"count": ${foundUris.size}, "imageUris": [$urisJson], "message": "Found ${foundUris.size} photos"}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in searchPhotos", e)
            """{"error": "Failed to search photos: ${e.message}"}"""
        }
    }

    // --- TOOL 2: DELETE PHOTOS ---
    @Serializable
    data class DeletePhotosArgs(
        @property:LLMDescription("List of image URIs to delete")
        val imageUris: List<String>
    )

    @Tool
    @LLMDescription("Deletes photos (moves to trash). Requires Android 11+ and user permission. Returns the URIs of images that were deleted.")
    suspend fun deletePhotos(args: DeletePhotosArgs): String {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return """{"error": "Delete operations require Android 11+"}"""
            }

            val argsJson = Json.encodeToString(args)
            Log.d(TAG, "🗑️ deletePhotos called with args: $argsJson")

            val uris = args.imageUris

            if (uris.isEmpty()) {
                return """{"error": "No photos provided for deletion"}"""
            }

            val intentSender = galleryTools.createDeleteIntentSender(uris)
            if (intentSender != null) {
                // Trigger permission flow via callback
                onPermissionRequired(
                    intentSender,
                    PermissionType.DELETE,
                    "Waiting for permission to delete ${uris.size} photos...",
                    mapOf("photo_uris" to uris)
                )
                val urisJson = uris.joinToString(",") { "\"$it\"" }
                """{"requiresPermission": true, "count": ${uris.size}, "imageUris": [$urisJson], "message": "Permission required to delete ${uris.size} photos"}"""
            } else {
                """{"error": "Failed to create delete request"}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in deletePhotos", e)
            """{"error": "Failed to delete photos: ${e.message}"}"""
        }
    }

    // --- TOOL 3: MOVE PHOTOS TO ALBUM ---
    @Serializable
    data class MovePhotosToAlbumArgs(
        @property:LLMDescription("List of image URIs to move")
        val imageUris: List<String>,
        @property:LLMDescription("Name of the album/folder to move photos to")
        val albumName: String
    )

    @Tool
    @LLMDescription("Moves photos to a specified album/folder. Creates the album if it doesn't exist. Requires Android 11+ and user permission. Returns the URIs of images that were moved.")
    suspend fun movePhotosToAlbum(args: MovePhotosToAlbumArgs): String {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return """{{"error": "Move operations require Android 11+"}}"""
            }

            val argsJson = Json.encodeToString(args)
            Log.d(TAG, "📁 movePhotosToAlbum called with args: $argsJson")

            val uris = args.imageUris

            if (uris.isEmpty()) {
                return """{"error": "No photos provided for moving"}"""
            }

            val intentSender = galleryTools.createWriteIntentSender(uris)
            if (intentSender != null) {
                // Trigger permission flow via callback
                onPermissionRequired(
                    intentSender,
                    PermissionType.WRITE,
                    "Waiting for permission to move ${uris.size} photos...",
                    mapOf("photo_uris" to uris, "album_name" to args.albumName)
                )
                val urisJson = uris.joinToString(",") { "\"$it\"" }
                """{"requiresPermission": true, "count": ${uris.size}, "imageUris": [$urisJson], "message": "Permission required to move ${uris.size} photos to album ${args.albumName}"}"""
            } else {
                """{"error": "Failed to create write request"}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in movePhotosToAlbum", e)
            """{"error": "Failed to move photos: ${e.message}"}"""
        }
    }

    // --- TOOL 4: CREATE COLLAGE ---
    @Serializable
    data class CreateCollageArgs(
        @property:LLMDescription("List of image URIs to include in the collage (up to 4 images)")
        val imageUris: List<String>,
        @property:LLMDescription("Title/name for the collage image")
        val title: String = "My Collage"
    )

    @Tool
    @LLMDescription("Creates a collage from multiple photos by stitching them together. Takes up to 4 photos and saves the result as a new image. Returns the URI of the created collage.")
    suspend fun createCollage(args: CreateCollageArgs): String {
        return try {
            val argsJson = Json.encodeToString(args)
            Log.d(TAG, "🖼️ createCollage called with args: $argsJson")

            val uris = args.imageUris

            if (uris.isEmpty()) {
                return """{"error": "No photos provided for collage creation"}"""
            }

            val collageUris = uris.take(4)
            val newCollageUri = galleryTools.createCollage(collageUris, args.title)
            
            if (newCollageUri != null) {
                val message = "I've created the collage '${args.title}'."
                onMessage(message, listOf(newCollageUri), true, false)
                onGalleryChanged()
                """{"success": true, "title": "${args.title}", "imageUris": ["$newCollageUri"], "message": "Collage created successfully"}"""
            } else {
                """{"error": "Failed to create collage"}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in createCollage", e)
            """{"error": "Failed to create collage: ${e.message}"}"""
        }
    }

    // --- TOOL 5: APPLY FILTER ---
    @Serializable
    data class ApplyFilterArgs(
        @property:LLMDescription("List of image URIs to apply filter to")
        val imageUris: List<String>,
        @property:LLMDescription("Name of the filter to apply. Supported: 'grayscale', 'black and white', 'b&w', 'sepia'")
        val filterName: String
    )

    @Tool
    @LLMDescription("Applies a visual filter to photos and saves them as new images. Supported filters: grayscale (black and white, b&w) and sepia. Returns the URIs of the newly created filtered images.")
    suspend fun applyFilter(args: ApplyFilterArgs): String {
        return try {
            val argsJson = Json.encodeToString(args)
            Log.d(TAG, "🎨 applyFilter called with args: $argsJson")

            val uris = args.imageUris

            if (uris.isEmpty()) {
                return """{"error": "No photos provided for filter application"}"""
            }

            val newImageUris = galleryTools.applyFilter(uris, args.filterName)
            
            if (newImageUris.isNotEmpty()) {
                val message = "I've applied the '${args.filterName}' filter."
                onMessage(message, newImageUris, true, false)
                onGalleryChanged()
                val urisJson = newImageUris.joinToString(",") { "\"$it\"" }
                """{"success": true, "count": ${newImageUris.size}, "imageUris": [$urisJson], "message": "Applied ${args.filterName} filter to ${newImageUris.size} photos"}"""
            } else {
                """{"error": "Failed to apply filter"}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in applyFilter", e)
            """{"error": "Failed to apply filter: ${e.message}"}"""
        }
    }

    // --- TOOL 6: GET PHOTO METADATA ---
    @Serializable
    data class GetPhotoMetadataArgs(
        @property:LLMDescription("List of image URIs to get metadata from")
        val imageUris: List<String>
    )

    @Tool
    @LLMDescription("Reads and returns EXIF metadata from photos including date taken, camera model, and location information. Returns metadata along with the image URIs.")
    suspend fun getPhotoMetadata(args: GetPhotoMetadataArgs): String {
        return try {
            val argsJson = Json.encodeToString(args)
            Log.d(TAG, "ℹ️ getPhotoMetadata called with args: $argsJson")

            val uris = args.imageUris

            if (uris.isEmpty()) {
                return """{"error": "No photos provided for metadata extraction"}"""
            }

            val metadataSummary = galleryTools.getPhotoMetadata(uris)
            val urisJson = uris.joinToString(",") { "\"$it\"" }
            """{"success": true, "imageUris": [$urisJson], "metadata": "$metadataSummary"}"""
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in getPhotoMetadata", e)
            """{"error": "Failed to get metadata: ${e.message}"}"""
        }
    }

    // --- TOOL 7: SCAN FOR CLEANUP ---
    @Serializable
    data class ScanForCleanupArgs(
        @property:LLMDescription("Type of cleanup scan. Currently only 'duplicates' is supported.")
        val scanType: String = "duplicates"
    )

    @Tool
    @LLMDescription("Scans the gallery for duplicate photos and returns groups of duplicates that can be reviewed and cleaned up.")
    suspend fun scanForCleanup(args: ScanForCleanupArgs): String {
        return try {
            val argsJson = Json.encodeToString(args)
            Log.d(TAG, "🔍 scanForCleanup called with args: $argsJson")

            val duplicates = cleanupManager.findDuplicates()
            
            if (duplicates.isEmpty()) {
                onMessage("No duplicates found.", null, false, false)
                """{"result": "No duplicates found", "found_sets": 0}"""
            } else {
                onCleanupGroups(duplicates)
                onMessage("Found duplicates. Tap to review.", null, false, true)
                val allDuplicateUris = duplicates.flatMap { it.duplicateUris }.take(5)
                val urisJson = allDuplicateUris.joinToString(",") { "\"$it\"" }
                """{"found_sets": ${duplicates.size}, "uris": [$urisJson], "message": "Found ${duplicates.size} duplicate groups"}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in scanForCleanup", e)
            """{"error": "Failed to scan for duplicates: ${e.message}"}"""
        }
    }

    // --- TOOL 8: ASK GALLERY (Vision) ---
    @Serializable
    data class AskGalleryArgs(
        @property:LLMDescription("List of image URIs to analyze")
        val imageUris: List<String>,
        @property:LLMDescription("The question or query about the images (e.g., 'What food is this?', 'Describe these images', 'What's in this photo?')")
        val query: String
    )

    @Tool
    @LLMDescription("Analyzes and answers questions about images using AI vision capabilities. Returns the analysis answer along with the image URIs that were analyzed. Use this for 'What is this?', 'Describe', or any visual questions about photo content.")
    suspend fun askGallery(args: AskGalleryArgs): String {
        return try {
            val argsJson = Json.encodeToString(args)
            Log.d(TAG, "👁️ askGallery called with args: $argsJson")

            val uris = args.imageUris

            if (uris.isEmpty()) {
                return """{"error": "No images provided for analysis"}"""
            }

            val answer = galleryTools.analyzeImages(uris, args.query)
            Log.d(TAG, "✅ Vision analysis complete: $answer")
            
            val analyzedUris = uris.take(5)
            val urisJson = analyzedUris.joinToString(",") { "\"$it\"" }
            // Return the analysis result
            """{"success": true, "answer": "${answer.replace("\"", "\\\"")}", "imageUris": [$urisJson], "imageCount": ${analyzedUris.size}}"""
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in askGallery", e)
            """{"error": "Failed to analyze images: ${e.message}"}"""
        }
    }
}


// class UserTools(private val showUserMessage: (String) -> String) : ToolSet {
//     @Tool
//     @LLMDescription("Show user the message from the agent and wait for a response. Use this tool to wait for response from user.")
//     fun processMessage(
//         @LLMDescription("The message to show to the user.")
//         message: String
//     ): String {
//         return showUserMessage(message)
//     }
// }