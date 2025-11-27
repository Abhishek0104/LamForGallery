package com.example.lamforgallery.ui

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
        if (_uiState.value.photos.isNotEmpty()) return // Don't reload if already loaded
        _uiState.value = PhotosScreenState()
        loadNextPage()
    }

    fun clearPhotos() {
        _uiState.value = PhotosScreenState()
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
    photosViewModel: PhotosViewModel,
    agentViewModel: AgentViewModel,
    onSendToAgent: (List<String>) -> Unit,
    onPhotoClick: (String) -> Unit
) {
    val photosUiState by photosViewModel.uiState.collectAsState()
    val agentUiState by agentViewModel.uiState.collectAsState()
    val searchResults by agentViewModel.photoSearchResults.collectAsState()
    val searchDescription by agentViewModel.searchDescription.collectAsState()

    var isSearchActive by remember { mutableStateOf(false) }
    var searchAttempted by remember { mutableStateOf(false) }
    val photosToShow = if (isSearchActive) searchResults else photosUiState.photos

    val gridState = rememberLazyStaggeredGridState()
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedPhotos by remember { mutableStateOf(setOf<String>()) }


    LaunchedEffect(isSelectionMode) {
        if (!isSelectionMode) selectedPhotos = emptySet()
    }

    // When search is dismissed, reload the original photos
    LaunchedEffect(isSearchActive) {
        if (!isSearchActive) {
            agentViewModel.clearSearch()
            searchAttempted = false
            if (photosUiState.photos.isEmpty()) {
                photosViewModel.loadPhotos()
            }
        }
    }

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo }
            .distinctUntilChanged()
            .collect { layoutInfo ->
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull() ?: return@collect
                val totalItems = layoutInfo.totalItemsCount
                if (lastVisibleItem.index >= totalItems - (PAGE_SIZE / 2) &&
                    !photosUiState.isLoading && photosUiState.canLoadMore && !isSearchActive
                ) {
                    photosViewModel.loadNextPage()
                }
            }
    }

    Scaffold(
        topBar = {
            PhotosSearchBar(
                isSearchActive = isSearchActive,
                onSearchActiveChange = { isSearchActive = it },
                onSearchSubmit = { query ->
                    searchAttempted = true
                    agentViewModel.submitSearchQuery(query)
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        },
        floatingActionButton = {
            if (isSelectionMode && selectedPhotos.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        onSendToAgent(selectedPhotos.toList())
                        isSelectionMode = false
                    },
                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = "Agent") },
                    text = { Text("Ask Agent (${selectedPhotos.size})") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            val isLoading = photosUiState.isLoading || agentUiState.currentStatus is AgentStatus.Loading
            if (photosToShow.isEmpty() && isLoading && isSearchActive) {
                CircularProgressIndicator()
            } else if (photosToShow.isEmpty() && !isLoading && isSearchActive && searchAttempted) {
                Text("No matching photos found.")
            } else if (photosUiState.photos.isEmpty() && !isLoading && !isSearchActive) {
                Text("No photos found in gallery.")
            } else {
                LazyVerticalStaggeredGrid(
                    state = gridState,
                    columns = StaggeredGridCells.Adaptive(minSize = 150.dp),
                    modifier = Modifier.fillMaxSize(),
                    verticalItemSpacing = 4.dp,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(4.dp)
                ) {
                    items(photosToShow, key = { it }) { photoUri ->
                        val isSelected = selectedPhotos.contains(photoUri)
                        Box(modifier = Modifier
                            .wrapContentHeight()
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .combinedClickable(
                                onClick = {
                                    if (isSelectionMode) {
                                        selectedPhotos = if (isSelected) selectedPhotos - photoUri else selectedPhotos + photoUri
                                        if (selectedPhotos.isEmpty()) isSelectionMode = false
                                    } else {
                                        val encodedUri = URLEncoder.encode(photoUri, StandardCharsets.UTF_8.name())
                                        onPhotoClick(encodedUri)
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
                                modifier = Modifier.fillMaxWidth().wrapContentHeight()
                            )

                            if (isSelectionMode) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(if (isSelected) Color.Black.copy(alpha = 0.4f) else Color.Transparent)
                                        .border(width = if (isSelected) 4.dp else 0.dp, color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, shape = RoundedCornerShape(8.dp))
                                )
                                Box(
                                    modifier = Modifier.padding(8.dp).align(Alignment.TopEnd).size(24.dp).clip(CircleShape).background(
                                        if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f)
                                    ).border(1.dp, Color.White, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                    if (photosUiState.isLoading && photosToShow.isNotEmpty()) {
                        item { Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(modifier = Modifier.size(32.dp)) } }
                    }
                }
            }
            AnimatedVisibility(
                visible = searchDescription.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Text(
                        text = searchDescription,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotosSearchBar(
    isSearchActive: Boolean,
    onSearchActiveChange: (Boolean) -> Unit,
    onSearchSubmit: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            focusRequester.requestFocus()
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!isSearchActive) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clickable { onSearchActiveChange(true) },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 4.dp,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Search photos...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Ask about your photos...") },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                singleLine = true,
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    onSearchSubmit(searchQuery)
                    keyboardController?.hide()
                }),
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear Search")
                        }
                    }
                }
            )
            AnimatedVisibility(
                visible = isSearchActive,
                enter = fadeIn(animationSpec = spring(stiffness = 300f)) + expandHorizontally(expandFrom = Alignment.Start),
                exit = fadeOut(animationSpec = spring(stiffness = 300f)) + shrinkHorizontally(shrinkTowards = Alignment.Start)
            ) {
                TextButton(
                    onClick = {
                        onSearchActiveChange(false)
                        searchQuery = ""
                        keyboardController?.hide()
                    },
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}