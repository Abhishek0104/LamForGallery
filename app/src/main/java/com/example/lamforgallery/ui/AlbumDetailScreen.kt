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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.foundation.clickable // --- IMPORT
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private const val ALBUM_PAGE_SIZE = 60

data class AlbumDetailState(
    val photos: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val canLoadMore: Boolean = true,
    val page: Int = 0,
    val albumName: String = ""
)

class AlbumDetailViewModel(
    private val galleryTools: GalleryTools
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumDetailState())
    val uiState: StateFlow<AlbumDetailState> = _uiState.asStateFlow()
    private val TAG = "AlbumDetailViewModel"

    fun loadAlbum(name: String) {
        val decodedName = try { URLDecoder.decode(name, StandardCharsets.UTF_8.name()) } catch (e: Exception) { name }
        _uiState.value = AlbumDetailState(albumName = decodedName)
        loadNextPage()
    }

    fun loadNextPage() {
        val currentState = _uiState.value
        if (currentState.isLoading || !currentState.canLoadMore || currentState.albumName.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val newPhotos = try {
                galleryTools.getPhotosForAlbum(currentState.albumName, currentState.page, ALBUM_PAGE_SIZE)
            } catch (e: Exception) { emptyList() }

            _uiState.update {
                it.copy(isLoading = false, photos = it.photos + newPhotos, page = it.page + 1, canLoadMore = newPhotos.isNotEmpty())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumName: String,
    viewModel: AlbumDetailViewModel,
    onNavigateBack: () -> Unit,
    onPhotoClick: (String) -> Unit // --- NEW CALLBACK
) {
    LaunchedEffect(albumName) { viewModel.loadAlbum(albumName) }
    val uiState by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo }
            .distinctUntilChanged()
            .collect { layoutInfo ->
                val last = layoutInfo.visibleItemsInfo.lastOrNull() ?: return@collect
                if (last.index >= layoutInfo.totalItemsCount - (ALBUM_PAGE_SIZE / 2) && !uiState.isLoading && uiState.canLoadMore) {
                    viewModel.loadNextPage()
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.albumName) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
            if (uiState.photos.isEmpty() && uiState.isLoading) CircularProgressIndicator()
            else if (uiState.photos.isEmpty()) Text("No photos found.")
            else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(uiState.photos, key = { it }) { photoUri ->
                        Box(modifier = Modifier.aspectRatio(1f).clickable { onPhotoClick(photoUri) }) {
                            AsyncImage(
                                model = photoUri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    if (uiState.isLoading) item { CircularProgressIndicator() }
                }
            }
        }
    }
}