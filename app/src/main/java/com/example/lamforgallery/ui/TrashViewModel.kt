package com.example.lamforgallery.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lamforgallery.database.ImageEmbeddingDao
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrashUiState(
    val trashPhotos: List<String> = emptyList(),
    val isLoading: Boolean = false
)

class TrashViewModel(
    private val dao: ImageEmbeddingDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrashUiState())
    val uiState: StateFlow<TrashUiState> = _uiState.asStateFlow()

    // --- NEW: Signal for gallery changes ---
    private val _galleryDidChange = MutableSharedFlow<Unit>()
    val galleryDidChange: SharedFlow<Unit> = _galleryDidChange.asSharedFlow()

    fun loadTrash() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val trashItems = dao.getTrashEmbeddings().map { it.uri }
            _uiState.update { it.copy(trashPhotos = trashItems, isLoading = false) }
        }
    }

    fun restorePhotos(uris: List<String>) {
        viewModelScope.launch {
            dao.restore(uris)
            loadTrash() // Reload Trash UI
            _galleryDidChange.emit(Unit) // Signal Global Refresh
        }
    }

    fun deletePermanently(uris: List<String>) {
        viewModelScope.launch {
            dao.hardDelete(uris)
            loadTrash() // Reload Trash UI
            // No need to emit galleryDidChange for permanent delete,
            // as they were already hidden from the main gallery.
        }
    }

    fun emptyTrash() {
        val currentUris = _uiState.value.trashPhotos
        if (currentUris.isNotEmpty()) {
            deletePermanently(currentUris)
        }
    }
}