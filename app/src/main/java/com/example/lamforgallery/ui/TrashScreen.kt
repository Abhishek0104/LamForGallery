package com.example.lamforgallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    viewModel: TrashViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    // Selection state
    var selectedUris by remember { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedUris.isNotEmpty()

    LaunchedEffect(Unit) {
        viewModel.loadTrash()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionMode) Text("${selectedUris.size} selected")
                    else Text("Trash")
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSelectionMode) selectedUris = emptySet()
                        else onNavigateBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (!isSelectionMode && uiState.trashPhotos.isNotEmpty()) {
                        TextButton(onClick = { viewModel.emptyTrash() }) {
                            Text("Empty Trash", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (isSelectionMode) {
                BottomAppBar {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = {
                                viewModel.restorePhotos(selectedUris.toList())
                                selectedUris = emptySet()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Icon(Icons.Default.Restore, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Restore")
                        }

                        Button(
                            onClick = {
                                viewModel.deletePermanently(selectedUris.toList())
                                selectedUris = emptySet()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Icon(Icons.Default.DeleteForever, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Delete")
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        if (uiState.trashPhotos.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text("Trash is empty.")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                contentPadding = PaddingValues(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(paddingValues)
            ) {
                items(uiState.trashPhotos, key = { it }) { uri ->
                    TrashItem(
                        uri = uri,
                        isSelected = selectedUris.contains(uri),
                        onToggle = {
                            selectedUris = if (selectedUris.contains(uri)) selectedUris - uri
                            else selectedUris + uri
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun TrashItem(uri: String, isSelected: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggle() }
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Selection Indicator
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
            )
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .padding(2.dp)
                    .size(16.dp)
            )
        }
    }
}