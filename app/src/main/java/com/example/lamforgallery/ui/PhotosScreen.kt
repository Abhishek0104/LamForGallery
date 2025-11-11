package com.example.lamforgallery.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lamforgallery.tools.GalleryTools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged

// --- Constants ---
private const val PAGE_SIZE = 60 // Load 60 photos at a time

// --- State ---
data class PhotosScreenState(
    val photos: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val canLoadMore: Boolean = true, // Becomes false when we've loaded all photos
    val page: Int = 0
)

// --- ViewModel ---
class PhotosViewModel(
    private val galleryTools: GalleryTools
) : ViewModel() {

    private val _uiState = MutableStateFlow(PhotosScreenState())
    val uiState: StateFlow<PhotosScreenState> = _uiState.asStateFlow()

    private val TAG = "PhotosViewModel"

    init {
        // We will load photos from MainActivity *after*
        // permission is confirmed.
        // loadPhotos() // <-- DELETE OR COMMENT OUT THIS LINE
    }

    /**
     * Resets and loads the first page of photos.
     */
    fun loadPhotos() {
        // Reset state for a fresh load
        _uiState.value = PhotosScreenState()
        loadNextPage()
    }

    /**
     * Loads the next page of photos.
     */
    fun loadNextPage() {
        val currentState = _uiState.value
        // Prevent multiple loads at once, or loading if we're at the end
        if (currentState.isLoading || !currentState.canLoadMore) {
            return
        }

        Log.d(TAG, "Loading next page, current page: ${currentState.page}")

        viewModelScope.launch {
            // 1. Set loading state
            _uiState.update { it.copy(isLoading = true) }

            // 2. Fetch photos for the current page
            val newPhotos = try {
                galleryTools.getPhotos(page = currentState.page, pageSize = PAGE_SIZE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load photos", e)
                emptyList<String>()
            }

            // 3. Update state with new photos
            _uiState.update {
                it.copy(
                    isLoading = false,
                    photos = (it.photos + newPhotos).distinct(),  // Append new photos
                    page = it.page + 1,
                    canLoadMore = newPhotos.isNotEmpty() // If we got no photos, we're at the end
                )
            }
            Log.d(TAG, "Page ${currentState.page} loaded, new total: ${uiState.value.photos.size}")
        }
    }
}

// --- Composable UI ---
@Composable
fun PhotosScreen(viewModel: PhotosViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()

    // --- This is the "Infinite Scroll" logic ---
    // It's a side effect that triggers when the scroll state changes.
    LaunchedEffect(gridState) {
        // snapshotFlow converts the LazyGridState's layoutInfo into a Flow
        snapshotFlow { gridState.layoutInfo }
            .distinctUntilChanged() // Only react when the layout *actually* changes
            .collect { layoutInfo ->
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull() ?: return@collect
                val totalItems = layoutInfo.totalItemsCount

                // If the last visible item is close to the end of the list,
                // and we're not already loading, and there's more to load...
                if (lastVisibleItem.index >= totalItems - (PAGE_SIZE / 2) && // Load when we're halfway through the last page
                    !uiState.isLoading &&
                    uiState.canLoadMore
                ) {
                    // ...then load the next page.
                    viewModel.loadNextPage()
                }
            }
    }
    // --- End Infinite Scroll Logic ---

    Scaffold(
        topBar = {
            // (You can add a TopAppBar here if you want)
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            if (uiState.photos.isEmpty() && uiState.isLoading) {
                // Show a loading spinner only on the *initial* load
                CircularProgressIndicator()
            } else if (uiState.photos.isEmpty() && !uiState.isLoading) {
                Text("No photos found.")
            } else {
                LazyVerticalGrid(
                    state = gridState, // Attach the grid state
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(uiState.photos, key = { it }) { photoUri ->
                        AsyncImage(
                            model = photoUri,
                            contentDescription = "Gallery Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .fillMaxWidth()
                        )
                    }

                    if (uiState.isLoading && uiState.photos.isNotEmpty()) {
                        // Show a small spinner at the *bottom* when loading more pages
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}