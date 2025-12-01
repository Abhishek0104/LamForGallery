package com.example.lamforgallery.ui

import androidx.lifecycle.ViewModel
import com.example.lamforgallery.database.ImageEmbedding
import com.example.lamforgallery.database.ImageEmbeddingDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Shared ViewModel to hold the list of photos for the full-screen viewer.
 * This avoids passing large lists via Navigation Arguments (which causes crashes).
 */
class PhotoViewerViewModel(private val dao: ImageEmbeddingDao) : ViewModel() {

    private val _uiState = MutableStateFlow(PhotoViewerUiState())
    val uiState: StateFlow<PhotoViewerUiState> = _uiState.asStateFlow()

    fun setPhotoList(photos: List<String>, initialUri: String) {
        val index = photos.indexOf(initialUri).coerceAtLeast(0)
        _uiState.update {
            it.copy(
                photos = photos,
                initialIndex = index
            )
        }
    }

    suspend fun getMetadataForUri(uri: String): ImageEmbedding? {
        return dao.getEmbeddingByUri(uri)
    }
}

data class PhotoViewerUiState(
    val photos: List<String> = emptyList(),
    val initialIndex: Int = 0
)