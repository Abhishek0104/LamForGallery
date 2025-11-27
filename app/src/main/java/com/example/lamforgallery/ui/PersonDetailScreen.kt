package com.example.lamforgallery.ui


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.example.lamforgallery.database.Person
import com.example.lamforgallery.database.PersonDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// --- ViewModel ---

data class PersonDetailUiState(
    val person: Person? = null,
    val photos: List<String> = emptyList(),
    val isLoading: Boolean = false
)

class PersonDetailViewModel(
    private val personDao: PersonDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(PersonDetailUiState())
    val uiState: StateFlow<PersonDetailUiState> = _uiState.asStateFlow()

    fun loadPerson(personId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val person = personDao.getPersonById(personId)
            val photos = if (person != null) {
                personDao.getUrisForPeople(listOf(personId))
            } else emptyList()

            _uiState.update {
                it.copy(
                    isLoading = false,
                    person = person,
                    photos = photos
                )
            }
        }
    }

    fun renamePerson(newName: String) {
        val currentPerson = _uiState.value.person ?: return
        viewModelScope.launch {
            personDao.updateName(currentPerson.id, newName)
            // Reload to reflect changes
            val updatedPerson = personDao.getPersonById(currentPerson.id)
            _uiState.update { it.copy(person = updatedPerson) }
        }
    }
}

// --- Screen ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonDetailScreen(
    personId: String,
    viewModel: PersonDetailViewModel,
    onNavigateBack: () -> Unit,
    onPhotoClick: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showRenameDialog by remember { mutableStateOf(false) }

    LaunchedEffect(personId) {
        viewModel.loadPerson(personId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.person?.name ?: "Loading...") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showRenameDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename Person")
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
                                .clickable { onPhotoClick(uri) }
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

        if (showRenameDialog && uiState.person != null) {
            RenameDialog(
                currentName = uiState.person!!.name,
                onDismiss = { showRenameDialog = false },
                onConfirm = { newName ->
                    viewModel.renamePerson(newName)
                    showRenameDialog = false
                }
            )
        }
    }
}

@Composable
fun RenameDialog(currentName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Person") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Name") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
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