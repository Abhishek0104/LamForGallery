package com.example.lamforgallery.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.lamforgallery.tools.GalleryTools

// 1. --- The ViewModel for this screen ---

data class PhotosUiState(
    val photos: List<String> = emptyList(),
    val isLoading: Boolean = false
)

class PhotosViewModel(
    private val galleryTools: GalleryTools
) : ViewModel() {

    private val _uiState = MutableStateFlow(PhotosUiState())
    val uiState: StateFlow<PhotosUiState> = _uiState.asStateFlow()

    init {
        loadPhotos()
    }

    fun loadPhotos() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            try {
                // Load the first page (e.g., 60 photos)
                val photos = galleryTools.getPhotos(page = 0, pageSize = 60)
                _uiState.update {
                    it.copy(isLoading = false, photos = photos)
                }
            } catch (e: Exception) {
                Log.e("PhotosViewModel", "Failed to load photos", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}


// 2. --- The Composable UI for this screen ---

@Composable
fun PhotosScreen(
    viewModel: PhotosViewModel
) {
    val uiState by viewModel.uiState.collectAsState()

    // A simple grid of photos
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp), // Responsive grid
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(uiState.photos, key = { it }) { photoUri ->
            AsyncImage(
                model = photoUri,
                contentDescription = "Gallery Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .aspectRatio(1f) // Make it a square
                    .fillMaxWidth()
            )
        }
    }
}