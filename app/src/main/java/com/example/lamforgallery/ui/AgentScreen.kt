package com.example.lamforgallery.ui

import android.content.IntentSender
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lightbulb
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
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
    onLaunchPermissionRequest: (IntentSender, PermissionType) -> Unit,
    onNavigateToCleanup: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()

    if (uiState.isSelectionSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeSelectionSheet() },
            sheetState = sheetState
        ) {
            SelectionBottomSheet(
                uris = uiState.selectionSheetUris,
                onCancel = { coroutineScope.launch { sheetState.hide() }.invokeOnCompletion { viewModel.closeSelectionSheet() } },
                onConfirm = { selection -> coroutineScope.launch { sheetState.hide() }.invokeOnCompletion { viewModel.confirmSelection(selection) } }
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
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "AI Assistant",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(onClick = { viewModel.clearChat() }) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear Chat",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                )
            )
        },
        bottomBar = {
            ChatInputBar(
                status = uiState.currentStatus,
                selectionCount = uiState.selectedImageUris.size,
                onSend = { viewModel.sendUserInput(it) },
                onClearSelection = { viewModel.clearSelection() }
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
            if (uiState.messages.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillParentMaxSize()
                            .padding(bottom = 100.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "How can I help?",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                        Spacer(Modifier.height(24.dp))

                        val suggestions = listOf(
                            "Show photos of food",
                            "Find receipts from last week",
                            "Scan for duplicates",
                            "Create a collage"
                        )

                        suggestions.forEach { text ->
                            SuggestionChip(
                                onClick = { viewModel.sendUserInput(text) },
                                label = { Text(text) },
                                icon = { Icon(Icons.Default.Lightbulb, null, Modifier.size(14.dp)) },
                                modifier = Modifier.padding(vertical = 4.dp),
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
            items(uiState.messages, key = { it.id }) { message ->
                ChatMessageItem(
                    message = message,
                    onOpenSelectionSheet = { uris -> viewModel.openSelectionSheet(uris) },
                    onReviewCleanup = onNavigateToCleanup,
                    onSuggestionClick = { prompt -> viewModel.sendUserInput(prompt) }
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
    onReviewCleanup: () -> Unit,
    onSuggestionClick: (String) -> Unit
) {
    val isUser = message.sender == Sender.USER
    val horizontalAlignment = if (isUser) Alignment.End else Alignment.Start

    val backgroundColor = when (message.sender) {
        Sender.USER -> MaterialTheme.colorScheme.primary
        Sender.AGENT -> MaterialTheme.colorScheme.surfaceVariant
        Sender.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val textColor = when (message.sender) {
        Sender.USER -> MaterialTheme.colorScheme.onPrimary
        Sender.AGENT -> MaterialTheme.colorScheme.onSurfaceVariant
        Sender.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = horizontalAlignment) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ))
                .background(backgroundColor)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (message.text.isNotEmpty()) {
                    Text(text = message.text, color = textColor, style = MaterialTheme.typography.bodyLarge)
                }

                if ((!message.imageUris.isNullOrEmpty() && !message.isCleanupPrompt) && (message.hasSelectionPrompt || isUser)) {
                    if (message.text.isNotEmpty()) Spacer(modifier = Modifier.height(8.dp))
                    MediaGridPreview(uris = message.imageUris!!, onClick = { onOpenSelectionSheet(message.imageUris) })
                }

                if (message.isCleanupPrompt) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onReviewCleanup,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Review Duplicates")
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        if (!message.suggestions.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isUser) { item { Spacer(modifier = Modifier.weight(1f)) } }

                items(message.suggestions) { suggestion ->
                    SuggestionChip(
                        onClick = { onSuggestionClick(suggestion.prompt) },
                        label = { Text(suggestion.label) },
                        icon = { Icon(Icons.Default.Lightbulb, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.primary
                        ),
                        border = null
                    )
                }
            }
        }
    }
}

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
        Row(modifier = Modifier.height(100.dp)) {
            MediaGridItem(uri = uris[0], modifier = Modifier.weight(1f))
            if (displayCount >= 2) {
                Spacer(modifier = Modifier.width(2.dp))
                MediaGridItem(uri = uris[1], modifier = Modifier.weight(1f))
            }
        }

        if (displayCount >= 3) {
            Spacer(modifier = Modifier.height(2.dp))
            Row(modifier = Modifier.height(100.dp)) {
                MediaGridItem(uri = uris[2], modifier = Modifier.weight(1f))
                if (displayCount >= 4) {
                    Spacer(modifier = Modifier.width(2.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        MediaGridItem(uri = uris[3], modifier = Modifier.fillMaxSize())
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionBottomSheet(uris: List<String>, onCancel: () -> Unit, onConfirm: (Set<String>) -> Unit) {
    var localSelection by remember { mutableStateOf(emptySet<String>()) }
    val selectionCount = localSelection.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Photos") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Close") } }
            )
        },
        floatingActionButton = {
            if (selectionCount > 0) {
                ExtendedFloatingActionButton(
                    onClick = { onConfirm(localSelection) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Confirm ($selectionCount)")
                }
            }
        },
        floatingActionButtonPosition = FabPosition.End
    ) { paddingValues ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(uris, key = { it }) { uri ->
                SelectablePhotoItem(
                    uri = uri,
                    isSelected = localSelection.contains(uri),
                    onToggle = { localSelection = localSelection.toMutableSet().apply { if (contains(uri)) remove(uri) else add(uri) } }
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
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.TopEnd)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun ChatInputBar(status: AgentStatus, selectionCount: Int, onSend: (String) -> Unit, onClearSelection: () -> Unit) {
    var inputText by remember { mutableStateOf("") }
    val isEnabled = status is AgentStatus.Idle

    Surface(
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)) {

            if (selectionCount > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
                        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "$selectionCount images attached",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // --- NEW: Clear Selection Button ---
                    IconButton(onClick = onClearSelection, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Remove attachment", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }

            if (status !is AgentStatus.Idle) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                ) {
                    if (status is AgentStatus.Loading) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(status.message, style = MaterialTheme.typography.labelSmall)
                    } else if (status is AgentStatus.RequiresPermission) {
                        Text("Waiting for permission...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Ask your gallery...", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    ),
                    maxLines = 3,
                    enabled = isEnabled
                )

                IconButton(
                    onClick = { onSend(inputText); inputText = "" },
                    enabled = isEnabled && (inputText.isNotBlank() || selectionCount > 0),
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            if (isEnabled && (inputText.isNotBlank() || selectionCount > 0)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        )
                ) {
                    Icon(
                        Icons.Default.ArrowUpward,
                        contentDescription = "Send",
                        tint = if (isEnabled && (inputText.isNotBlank() || selectionCount > 0)) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

@Composable
fun AgentPermissionDeniedScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Permission Required",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "This app needs permission to read your photos to work.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRequestPermission) {
                Text("Grant Permission")
            }
        }
    }
}