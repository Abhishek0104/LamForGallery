package com.example.lamforgallery.ui

import android.content.IntentSender
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * Updated Chat UI with "WhatsApp-style" inline media grids.
 */

// ... (AgentScreen function signature update) ...
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
    onLaunchPermissionRequest: (IntentSender, PermissionType) -> Unit,
    onNavigateToCleanup: () -> Unit = {} // --- NEW PARAM ---
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()

    // ... (Bottom Sheet Logic same as before) ...
    if (uiState.isSelectionSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeSelectionSheet() },
            sheetState = sheetState
        ) {
            SelectionBottomSheet(
                uris = uiState.selectionSheetUris,
                onCancel = {
                    coroutineScope.launch { sheetState.hide() }.invokeOnCompletion {
                        viewModel.closeSelectionSheet()
                    }
                },
                onConfirm = { selection ->
                    coroutineScope.launch { sheetState.hide() }.invokeOnCompletion {
                        viewModel.confirmSelection(selection)
                    }
                }
            )
        }
    }

    LaunchedEffect(uiState.currentStatus) {
        val status = uiState.currentStatus
        if (status is AgentStatus.RequiresPermission) {
            onLaunchPermissionRequest(status.intentSender, status.type)
        }
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(uiState.messages.size - 1)
            }
        }
    }

    Scaffold(
        bottomBar = {
            ChatInputBar(
                status = uiState.currentStatus,
                selectionCount = uiState.selectedImageUris.size,
                onSend = { viewModel.sendUserInput(it) }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            item { Spacer(modifier = Modifier.height(16.dp)) }
            items(uiState.messages, key = { it.id }) { message ->
                ChatMessageItem(
                    message = message,
                    onOpenSelectionSheet = { uris -> viewModel.openSelectionSheet(uris) },
                    // --- NEW: Pass cleanup callback ---
                    onReviewCleanup = onNavigateToCleanup
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun ChatMessageItem(
    message: ChatMessage,
    onOpenSelectionSheet: (List<String>) -> Unit,
    onReviewCleanup: () -> Unit
) {
    val isUser = message.sender == Sender.USER
    val horizontalAlignment = if (isUser) Alignment.End else Alignment.Start

    val backgroundColor = when (message.sender) {
        Sender.USER -> MaterialTheme.colorScheme.primaryContainer
        Sender.AGENT -> MaterialTheme.colorScheme.secondaryContainer
        Sender.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val textColor = when (message.sender) {
        Sender.USER -> MaterialTheme.colorScheme.onPrimaryContainer
        Sender.AGENT -> MaterialTheme.colorScheme.onSecondaryContainer
        Sender.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = horizontalAlignment) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(backgroundColor)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (message.text.isNotEmpty()) {
                    Text(
                        text = message.text,
                        color = textColor,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                // --- EXISTING MEDIA GRID ---
                if (!message.imageUris.isNullOrEmpty() && !message.isCleanupPrompt) {
                    if (message.text.isNotEmpty()) Spacer(modifier = Modifier.height(8.dp))
                    MediaGridPreview(
                        uris = message.imageUris,
                        onClick = { onOpenSelectionSheet(message.imageUris) }
                    )
                }

                // --- NEW CLEANUP BUTTON ---
                if (message.isCleanupPrompt) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onReviewCleanup,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Review Duplicates")
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/**
 * Renders a neat 2x2 grid (or fewer) of images with a "+N more" overlay.
 * Designed to look like a messaging app preview.
 */
@Composable
fun MediaGridPreview(
    uris: List<String>,
    onClick: () -> Unit
) {
    val displayCount = uris.size.coerceAtMost(4)
    val extraCount = uris.size - 4
    val cornerRadius = 8.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .clickable(onClick = onClick)
    ) {
        // Row 1
        Row(modifier = Modifier.height(100.dp)) {
            MediaGridItem(uri = uris[0], modifier = Modifier.weight(1f))
            if (displayCount >= 2) {
                Spacer(modifier = Modifier.width(2.dp))
                MediaGridItem(uri = uris[1], modifier = Modifier.weight(1f))
            }
        }

        // Row 2 (if needed)
        if (displayCount >= 3) {
            Spacer(modifier = Modifier.height(2.dp))
            Row(modifier = Modifier.height(100.dp)) {
                MediaGridItem(uri = uris[2], modifier = Modifier.weight(1f))
                if (displayCount >= 4) {
                    Spacer(modifier = Modifier.width(2.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        MediaGridItem(uri = uris[3], modifier = Modifier.fillMaxSize())

                        // Overlay for "+X more"
                        if (extraCount > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "+$extraCount",
                                    color = Color.White,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                } else {
                    // Empty space filler to keep alignment
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun MediaGridItem(uri: String, modifier: Modifier = Modifier) {
    AsyncImage(
        model = uri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize()
    )
}

// ... (SelectionBottomSheet and ChatInputBar remain mostly the same) ...

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionBottomSheet(
    uris: List<String>,
    onCancel: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var localSelection by remember { mutableStateOf(emptySet<String>()) }
    val selectionCount = localSelection.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Photos") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(containerColor = MaterialTheme.colorScheme.surface) {
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = { onConfirm(localSelection) }, enabled = selectionCount > 0) {
                    Text("Confirm ($selectionCount)")
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    ) { paddingValues ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(uris, key = { it }) { uri ->
                SelectablePhotoItem(
                    uri = uri,
                    isSelected = localSelection.contains(uri),
                    onToggle = {
                        localSelection = localSelection.toMutableSet().apply {
                            if (contains(uri)) remove(uri) else add(uri)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun SelectablePhotoItem(uri: String, isSelected: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggle() }
            .then(if (isSelected) Modifier.border(BorderStroke(4.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(8.dp)) else Modifier)
    ) {
        AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (isSelected) {
            Box(
                modifier = Modifier.padding(8.dp).align(Alignment.TopEnd).size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun ChatInputBar(status: AgentStatus, selectionCount: Int, onSend: (String) -> Unit) {
    var inputText by remember { mutableStateOf("") }
    val isEnabled = status is AgentStatus.Idle
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
        if (status !is AgentStatus.Idle || selectionCount > 0) {
            Box(modifier = Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterStart) {
                when (status) {
                    is AgentStatus.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(status.message, style = MaterialTheme.typography.bodySmall)
                    }
                    is AgentStatus.RequiresPermission -> Text("Waiting for permission...", style = MaterialTheme.typography.bodySmall)
                    is AgentStatus.Idle -> Text("$selectionCount image(s) selected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = inputText, onValueChange = { inputText = it }, label = { Text("Your command...") }, modifier = Modifier.weight(1f), maxLines = 1, singleLine = true, enabled = isEnabled)
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { onSend(inputText); inputText = "" }, enabled = isEnabled && (inputText.isNotBlank() || selectionCount > 0), modifier = Modifier.height(56.dp)) { Text("Send") }
        }
    }
}

@Composable
fun PermissionDeniedScreen(onRequestPermission: () -> Unit) { /* Same as before */ }