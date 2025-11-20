package com.example.lamforgallery.utils

import android.util.Log
import com.example.lamforgallery.database.ImageEmbeddingDao
import com.example.lamforgallery.utils.SimilarityUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles the logic for scanning the gallery and finding unwanted photos
 * like duplicates or blurry images.
 */
class CleanupManager(
    private val dao: ImageEmbeddingDao
) {
    private val TAG = "CleanupManager"

    data class DuplicateGroup(
        val primaryUri: String,         // The one we suggest keeping
        val duplicateUris: List<String> // The ones we suggest deleting
    )

    /**
     * Scans all indexed images and groups them by visual similarity.
     * Threshold > 0.98 usually means they are visually identical.
     */
    suspend fun findDuplicates(): List<DuplicateGroup> {
        return withContext(Dispatchers.Default) {
            Log.d(TAG, "Starting duplicate scan...")
            val allEmbeddings = dao.getAllEmbeddings()
            val groups = mutableListOf<DuplicateGroup>()
            val visitedUris = mutableSetOf<String>()

            // Naive O(N^2) clustering.
            // For production with >5000 images, we would use a more efficient clustering index.
            for (i in allEmbeddings.indices) {
                val current = allEmbeddings[i]
                if (visitedUris.contains(current.uri)) continue

                val currentDuplicates = mutableListOf<String>()

                for (j in i + 1 until allEmbeddings.size) {
                    val candidate = allEmbeddings[j]
                    if (visitedUris.contains(candidate.uri)) continue

                    val similarity = SimilarityUtil.cosineSimilarity(current.embedding, candidate.embedding)

                    // 0.985 is a very high threshold -> effectively identical
                    if (similarity > 0.985f) {
                        currentDuplicates.add(candidate.uri)
                        visitedUris.add(candidate.uri)
                    }
                }

                if (currentDuplicates.isNotEmpty()) {
                    // We found a group!
                    // Logic: Keep the first one (current), mark others as duplicates.
                    groups.add(DuplicateGroup(
                        primaryUri = current.uri,
                        duplicateUris = currentDuplicates
                    ))
                    visitedUris.add(current.uri)
                }
            }
            Log.d(TAG, "Scan complete. Found ${groups.size} duplicate sets.")
            groups
        }
    }
}