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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.foundation.clickable
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// 1. --- The ViewModel for this screen ---

data class AlbumsUiState(
    val albums: List<GalleryTools.Album> = emptyList(),
    val isLoading: Boolean = false
)

class AlbumsViewModel(
    private val galleryTools: GalleryTools
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumsUiState())
    val uiState: StateFlow<AlbumsUiState> = _uiState.asStateFlow()

    init {
        loadAlbums()
    }

    fun loadAlbums() {
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch {
            try {
                val albums = galleryTools.getAlbums()
                _uiState.update {
                    it.copy(isLoading = false, albums = albums)
                }
            } catch (e: Exception) {
                Log.e("AlbumsViewModel", "Failed to load albums", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}


// 2. --- The Composable UI for this screen ---

@Composable
fun AlbumsScreen(
    viewModel: AlbumsViewModel,
    onAlbumClick: (String) -> Unit,
    onTrashClick: () -> Unit // <-- NEW: Trash navigation callback
) {
    val uiState by viewModel.uiState.collectAsState()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // --- Static Item for Trash ---
        item {
            TrashAlbumItem(onClick = onTrashClick)
        }

        // --- Regular Albums ---
        items(uiState.albums, key = { it.name }) { album ->
            AlbumItem(
                album = album,
                onClick = {
                    val encodedName = URLEncoder.encode(album.name, StandardCharsets.UTF_8.name())
                    onAlbumClick(encodedName)
                }
            )
        }
    }
}

@Composable
fun TrashAlbumItem(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant) // Distinct background
            .clickable(onClick = onClick)
    ) {
        Icon(
            imageVector = Icons.Default.DeleteOutline,
            contentDescription = "Trash",
            modifier = Modifier
                .align(Alignment.Center)
                .size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentAlignment = Alignment.BottomStart
        ) {
            Text(
                text = "Trash",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun AlbumItem(
    album: GalleryTools.Album,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        // Cover Image
        AsyncImage(
            model = album.coverUri,
            contentDescription = "Album Cover for ${album.name}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Scrim overlay at the bottom
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Color.Black.copy(alpha = 0.5f),
                )
                .padding(8.dp),
            contentAlignment = Alignment.BottomStart
        ) {
            Column {
                Text(
                    text = album.name,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${album.photoCount} photos",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}