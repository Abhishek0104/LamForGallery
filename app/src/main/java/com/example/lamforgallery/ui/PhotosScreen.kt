package com.example.lamforgallery.ui

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.example.lamforgallery.tools.GalleryTools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// --- Constants ---
private const val PAGE_SIZE = 60

// --- State ---
data class PhotosScreenState(
    val photos: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val canLoadMore: Boolean = true,
    val page: Int = 0
)

// --- ViewModel ---
class PhotosViewModel(
    private val galleryTools: GalleryTools
) : ViewModel() {

    private val _uiState = MutableStateFlow(PhotosScreenState())
    val uiState: StateFlow<PhotosScreenState> = _uiState.asStateFlow()

    private val TAG = "PhotosViewModel"

    fun loadPhotos() {
        _uiState.value = PhotosScreenState()
        loadNextPage()
    }

    fun loadNextPage() {
        val currentState = _uiState.value
        if (currentState.isLoading || !currentState.canLoadMore) {
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val newPhotos = try {
                galleryTools.getPhotos(page = currentState.page, pageSize = PAGE_SIZE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load photos", e)
                emptyList<String>()
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    photos = (it.photos + newPhotos).distinct(),
                    page = it.page + 1,
                    canLoadMore = newPhotos.isNotEmpty()
                )
            }
        }
    }
}

// --- Composable UI ---
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen(
    viewModel: PhotosViewModel,
    onSendToAgent: (List<String>) -> Unit // <-- Callback to bridge to Agent
) {
    val uiState by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()

    // --- Local Selection State ---
    // We manage selection locally within this screen.
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedPhotos by remember { mutableStateOf(setOf<String>()) }

    // Reset selection if mode is turned off
    LaunchedEffect(isSelectionMode) {
        if (!isSelectionMode) {
            selectedPhotos = emptySet()
        }
    }

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo }
            .distinctUntilChanged()
            .collect { layoutInfo ->
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull() ?: return@collect
                val totalItems = layoutInfo.totalItemsCount
                if (lastVisibleItem.index >= totalItems - (PAGE_SIZE / 2) &&
                    !uiState.isLoading && uiState.canLoadMore
                ) {
                    viewModel.loadNextPage()
                }
            }
    }

    Scaffold(
        floatingActionButton = {
            if (isSelectionMode && selectedPhotos.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        // 1. Send data to callback
                        onSendToAgent(selectedPhotos.toList())
                        // 2. Turn off selection mode
                        isSelectionMode = false
                    },
                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = "Agent") },
                    text = { Text("Ask Agent (${selectedPhotos.size})") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            }
        },
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text("${selectedPhotos.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { isSelectionMode = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close Selection")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            if (uiState.photos.isEmpty() && uiState.isLoading) {
                CircularProgressIndicator()
            } else if (uiState.photos.isEmpty() && !uiState.isLoading) {
                Text("No photos found.")
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(uiState.photos, key = { it }) { photoUri ->
                        val isSelected = selectedPhotos.contains(photoUri)

                        Box(modifier = Modifier
                            .aspectRatio(1f)
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (isSelectionMode) {
                                        // Toggle selection
                                        selectedPhotos = if (isSelected) {
                                            selectedPhotos - photoUri
                                        } else {
                                            selectedPhotos + photoUri
                                        }
                                        // Auto-exit mode if empty? (Optional)
                                        if (selectedPhotos.isEmpty()) isSelectionMode = false
                                    } else {
                                        // Standard click (maybe open fullscreen in future)
                                    }
                                },
                                onLongClick = {
                                    if (!isSelectionMode) {
                                        isSelectionMode = true
                                        selectedPhotos = selectedPhotos + photoUri
                                    }
                                }
                            )
                        ) {
                            AsyncImage(
                                model = photoUri,
                                contentDescription = "Gallery Photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )

                            // Selection Overlay
                            if (isSelectionMode) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            if (isSelected) Color.Black.copy(alpha = 0.4f)
                                            else Color.Transparent
                                        )
                                        .border(
                                            width = if (isSelected) 4.dp else 0.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                                        )
                                )

                                // Checkmark (Top Right)
                                Box(
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .align(Alignment.TopEnd)
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else Color.White.copy(alpha = 0.5f)
                                        )
                                        .border(1.dp, Color.White, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // Loading footer...
                    if (uiState.isLoading && uiState.photos.isNotEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}