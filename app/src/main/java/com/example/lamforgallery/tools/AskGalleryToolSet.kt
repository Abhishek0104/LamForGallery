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

    // --- TOOL: ASK GALLERY (Search + Vision) ---
    @Serializable
    data class AskGalleryArgs(
        @property:LLMDescription("The search query for semantic/AI-powered search (e.g., 'sunset', 'cat', 'receipts') using image and text embeddings. Don't include information which were used in other arguments like date/location/people.")
        val search_query: String = "",
        @property:LLMDescription("Optional question about the visual content of the images (e.g., 'What food is this?', 'Describe these images', 'What's in this photo?'). Leave empty if only searching for photos.")
        val vision_query: String? = null,
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
    @LLMDescription("Searches for photos using AI-powered semantic search with optional filters (date, location, people), and optionally analyzes the found images using AI vision. First performs the search, then if vision_query is provided and images are found, analyzes them to answer questions about their visual content.")
    suspend fun askGallery(args: AskGalleryArgs): String {
        return try {
            val argsJson = Json.encodeToString(args)
            Log.d(TAG, "🔍 askGallery called with args: $argsJson")

            // Step 1: Search for photos
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
                return """{"count": 0, "message": "No matching photos found"}"""
            }

            // Step 2: If vision_query is provided, analyze the images
            if (!args.vision_query.isNullOrBlank()) {
                Log.d(TAG, "👁️ Performing vision analysis on ${foundUris.size} images")
                
                val answer = galleryTools.analyzeImages(foundUris, args.vision_query)
                Log.d(TAG, "✅ Vision analysis complete: $answer")
                
                return """{"count": ${foundUris.size}, "answer": "${answer.replace("\"", "\\\"")}", "imageCount": ${foundUris.take(5).size}}"""
            } else {
                // Just return search results
                return """{"count": ${foundUris.size}, "message": "Found ${foundUris.size} photos"}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in askGallery", e)
            """{"error": "Failed to process request: ${e.message}"}"""
        }
    }
}
