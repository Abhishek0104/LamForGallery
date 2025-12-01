package com.example.lamforgallery.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import android.util.Log
import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Simplified ToolSet for photo search and AI vision analysis.
 * Used in PhotosScreen for semantic search and image understanding.
 */
@LLMDescription("Tools for searching photos and analyzing images with AI vision")
class AskGalleryToolSet(
    private val context: Context,
    private val galleryTools: GalleryTools,
    private val onSearchResults: (List<String>) -> Unit,
    private val getLastSearchResults: () -> List<String>
) : ToolSet {

    private val TAG = "AskGalleryToolSet"

    // --- TOOL 1: SEARCH PHOTOS ---
    @Serializable
    data class SearchPhotosArgs(
        @property:LLMDescription("The search query for semantic/AI-powered search (e.g., 'sunset', 'cat', 'receipts') using image and text embeddings. Don't include information which were used in other arguments like date/location/people.")
        val search_query: String = "",
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

            // Search for photos
            val params = GalleryTools.SearchPhotosParams(
                query = args.search_query,
                startDate = args.start_date,
                endDate = args.end_date,
                location = args.location,
                people = args.people
            )

            val foundUris = galleryTools.searchPhotos(params)

            // Update search results cache
            onSearchResults(foundUris)

            if (foundUris.isEmpty()) {
                """{"count": 0, "imageUris": [], "message": "No matching photos found"}"""
            } else {
                val urisJson = foundUris.joinToString(",") { "\"$it\"" }
                """{"count": ${foundUris.size}, "imageUris": [$urisJson], "message": "Found ${foundUris.size} photos"}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in searchPhotos", e)
            """{"error": "Failed to search photos: ${e.message}"}"""
        }
    }

    // --- TOOL 2: ASK GALLERY (Vision Analysis) ---
    @Serializable
    data class AskGalleryArgs(
        @property:LLMDescription("List of image URIs to analyze")
        val imageUris: List<String>,
        @property:LLMDescription("Question about the visual content of the images (e.g., 'What food is this?', 'Describe these images', 'What's in this photo?')")
        val vision_query: String
    )

    @Tool
    @LLMDescription("Analyzes images using AI vision to answer questions about their visual content. Takes a list of image URIs and a question, returns an answer based on the visual analysis.")
    suspend fun askGallery(args: AskGalleryArgs): String {
        return try {
            val argsJson = Json.encodeToString(args)
            Log.d(TAG, "👁️ askGallery called with args: $argsJson")

            if (args.imageUris.isEmpty()) {
                return """{"error": "No images provided for analysis"}"""
            }

            if (args.vision_query.isBlank()) {
                return """{"error": "No question provided for vision analysis"}"""
            }

            Log.d(TAG, "👁️ Performing vision analysis on ${args.imageUris.size} images")
            
            val answer = galleryTools.analyzeImages(args.imageUris, args.vision_query)
            Log.d(TAG, "✅ Vision analysis complete: $answer")
            
            val analyzedUris = args.imageUris.take(5)
            val urisJson = analyzedUris.joinToString(",") { "\"$it\"" }
            """{"success": true, "answer": "${answer.replace("\"", "\\\"")}", "imageUris": [$urisJson], "imageCount": ${analyzedUris.size}}"""
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in askGallery", e)
            """{"error": "Failed to analyze images: ${e.message}"}"""
        }
    }
}
