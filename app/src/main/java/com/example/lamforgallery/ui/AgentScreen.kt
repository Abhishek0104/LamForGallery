package com.example.lamforgallery.ui

import android.content.IntentSender
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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

/**
 * This file now contains our entire Chat UI, decoupled from MainActivity.
 */

@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
    onLaunchPermissionRequest: (IntentSender, PermissionType) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // This effect will watch for a new RequiresPermission state
    LaunchedEffect(uiState.currentStatus) {
        val status = uiState.currentStatus
        if (status is AgentStatus.RequiresPermission) {
            Log.d("AgentScreen", "Detected RequiresPermission state: ${status.type}")
            onLaunchPermissionRequest(status.intentSender, status.type)
        }
    }

    // This effect will auto-scroll to the bottom when a new message is added
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
                onSend = { inputText ->
                    viewModel.sendUserInput(inputText)
                }
            )
        }
    ) { paddingValues ->
        // This is our main chat content area
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues) // Apply padding from the Scaffold
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Bottom // Stick to bottom
        ) {
            // Add a spacer to push content up when list is short
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Render each chat message
            items(uiState.messages, key = { it.id }) { message ->
                ChatMessageItem(
                    message = message,
                    isSelected = { uri -> uiState.selectedImageUris.contains(uri) },
                    onToggleSelection = { uri -> viewModel.toggleImageSelection(uri) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Add a final spacer for padding at the bottom
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Renders a single chat message with different styling
 * for USER, AGENT, and ERROR.
 * NEW: Now handles selection.
 */
@Composable
fun ChatMessageItem(
    message: ChatMessage,
    isSelected: (String) -> Boolean,
    onToggleSelection: (String) -> Unit
) {
    val horizontalAlignment = when (message.sender) {
        Sender.USER -> Alignment.End
        Sender.AGENT, Sender.ERROR -> Alignment.Start
    }

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

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = horizontalAlignment
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f) // Max 80% width
                .background(backgroundColor, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(12.dp)) { // Wrap in Column to stack text and images
                Text(
                    text = message.text,
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge,
                )

                // --- IMAGE DISPLAY LOGIC ---
                message.imageUris?.let { uris ->
                    if (uris.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))

                        // Use a horizontal row for multiple images, or a single image view
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            uris.take(4).forEach { uri -> // Show up to 4 images
                                val isSelected = isSelected(uri)
                                Box(
                                    modifier = Modifier
                                        .size(90.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onToggleSelection(uri) } // <-- MAKE IT CLICKABLE
                                        .then(
                                            if (isSelected) Modifier.border( // <-- SHOW BORDER
                                                BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
                                                RoundedCornerShape(8.dp)
                                            ) else Modifier
                                        )
                                ) {
                                    AsyncImage(
                                        model = uri,
                                        contentDescription = "Image from agent",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (isSelected) {
                                        // --- SHOW CHECKMARK ---
                                        Box(
                                            modifier = Modifier
                                                .padding(4.dp)
                                                .align(Alignment.TopEnd)
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            if (uris.size > 4) {
                                // Add a simple indicator if there are more images
                                Box(
                                    modifier = Modifier
                                        .size(90.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "+${uris.size - 4}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            }
                        }
                    }
                }
                // --- END IMAGE DISPLAY LOGIC ---
            }
        }
    }
}

/**
 * The bottom bar with the text field and send button.
 * NEW: Shows selection count.
 */
@Composable
fun ChatInputBar(
    status: AgentStatus,
    selectionCount: Int,
    onSend: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val isEnabled = status is AgentStatus.Idle

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        // --- STATUS INDICATOR ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            when (status) {
                is AgentStatus.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(status.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is AgentStatus.RequiresPermission -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(status.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is AgentStatus.Idle -> {
                    if (selectionCount > 0) {
                        // --- SHOW SELECTION COUNT ---
                        Text(
                            "$selectionCount image(s) selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        // --- END STATUS ---

        Spacer(modifier = Modifier.height(8.dp))

        // --- INPUT ROW ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = { Text("Your command...") },
                modifier = Modifier.weight(1f),
                maxLines = 1,
                singleLine = true,
                enabled = isEnabled
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    onSend(inputText)
                    inputText = ""
                },
                // --- UPDATE ENABLED LOGIC ---
                // Can send if text OR selection is present
                enabled = isEnabled && (inputText.isNotBlank() || selectionCount > 0),
                modifier = Modifier.height(56.dp)
            ) {
                Text("Send")
            }
        }
    }
}


@Composable
fun PermissionDeniedScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
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