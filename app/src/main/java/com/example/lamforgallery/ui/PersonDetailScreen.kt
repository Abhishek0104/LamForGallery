package com.example.lamforgallery.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.lamforgallery.database.PersonUiModel
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PersonDetailScreen(
    personId: String,
    viewModel: PersonDetailViewModel,
    onNavigateBack: () -> Unit,
    onPhotoClick: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showEditDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var selectedPhotoUri by remember { mutableStateOf<String?>(null) }
    var showPhotoMenu by remember { mutableStateOf(false) }

    LaunchedEffect(personId) {
        viewModel.loadPerson(personId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(uiState.person?.name ?: "Loading...")
                        if (!uiState.person?.relation.isNullOrEmpty()) {
                            Text(
                                text = "Relation: ${uiState.person?.relation}",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showEditDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Person")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (uiState.photos.isEmpty()) {
                Text(
                    "No photos found for this person.",
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(4.dp)
                ) {
                    items(uiState.photos, key = { it }) { uri ->
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .combinedClickable(
                                    onClick = {
                                        val encodedUri = URLEncoder.encode(uri, StandardCharsets.UTF_8.name())
                                        onPhotoClick(encodedUri)
                                    },
                                    onLongClick = {
                                        selectedPhotoUri = uri
                                        showPhotoMenu = true
                                    }
                                )
                        ) {
                            AsyncImage(
                                model = uri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }

        if (showEditDialog && uiState.person != null) {
            EditPersonDialog(
                currentName = uiState.person!!.name,
                currentRelation = uiState.person!!.relation,
                onDismiss = { showEditDialog = false },
                onConfirm = { name, relation ->
                    viewModel.updatePersonDetails(name, relation)
                    showEditDialog = false
                }
            )
        }

        if (showPhotoMenu && selectedPhotoUri != null) {
            PhotoActionMenu(
                onDismiss = { showPhotoMenu = false },
                onRemove = {
                    viewModel.removePhotoFromPerson(selectedPhotoUri!!)
                    showPhotoMenu = false
                },
                onMove = {
                    showMoveDialog = true
                    showPhotoMenu = false
                }
            )
        }

        if (showMoveDialog && selectedPhotoUri != null) {
            MovePersonDialog(
                allPeople = uiState.allPeople.filter { it.id != uiState.person?.id },
                onDismiss = { showMoveDialog = false },
                onConfirm = { newPersonId ->
                    viewModel.movePhotoToPerson(selectedPhotoUri!!, newPersonId)
                    showMoveDialog = false
                }
            )
        }
    }
}

@Composable
fun PhotoActionMenu(
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onMove: () -> Unit
) {
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text("Remove from this person") },
            onClick = onRemove
        )
        DropdownMenuItem(
            text = { Text("Move to another person") },
            onClick = onMove
        )
    }
}

@Composable
fun MovePersonDialog(
    allPeople: List<PersonUiModel>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move Photo To...") },
        text = {
            LazyColumn {
                items(allPeople) { person ->
                    ListItem(
                        headlineContent = { Text(person.name) },
                        modifier = Modifier.clickable { onConfirm(person.id) },
                        leadingContent = {
                            AsyncImage(
                                model = person.coverUri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(40.dp).clip(CircleShape)
                            )
                        }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun EditPersonDialog(
    currentName: String,
    currentRelation: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    var relation by remember { mutableStateOf(currentRelation ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = relation,
                    onValueChange = { relation = it },
                    label = { Text("Relation (e.g. Daughter)") },
                    singleLine = true,
                    placeholder = { Text("Optional") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, relation) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}