package com.example.lamforgallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.lamforgallery.utils.CleanupManager

/**
 * Screen to review found duplicates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupScreen(
    duplicateGroups: List<CleanupManager.DuplicateGroup>,
    onNavigateBack: () -> Unit,
    onConfirmDelete: (List<String>) -> Unit
) {
    // We track which groups the user has "Approved" for deletion.
    // By default, all groups are selected.
    val selectedGroups = remember { mutableStateListOf<CleanupManager.DuplicateGroup>().apply { addAll(duplicateGroups) } }

    val totalSavings = selectedGroups.sumOf { it.duplicateUris.size }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cleanup Review") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            if (selectedGroups.isNotEmpty()) {
                Button(
                    onClick = {
                        // Flatten the list of URIs to delete
                        val allUrisToDelete = selectedGroups.flatMap { it.duplicateUris }
                        onConfirmDelete(allUrisToDelete)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(56.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Delete $totalSavings Duplicates")
                }
            }
        }
    ) { paddingValues ->
        if (duplicateGroups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No duplicates found! Your gallery is clean.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        "Found ${duplicateGroups.size} sets of similar photos.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                items(duplicateGroups) { group ->
                    DuplicateGroupCard(
                        group = group,
                        isSelected = selectedGroups.contains(group),
                        onToggle = {
                            if (selectedGroups.contains(group)) selectedGroups.remove(group)
                            else selectedGroups.add(group)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DuplicateGroupCard(
    group: CleanupManager.DuplicateGroup,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f)
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
                Text(
                    "Duplicate Set (${group.duplicateUris.size + 1} photos)",
                    style = MaterialTheme.typography.titleSmall
                )
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // The "Keeper"
                Column(modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))) {
                        AsyncImage(
                            model = group.primaryUri,
                            contentDescription = "Keep",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier.align(Alignment.BottomCenter).background(Color.Green.copy(alpha = 0.7f)).fillMaxWidth().padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("KEEP", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // The "Duplicates" (Show up to 2)
                group.duplicateUris.take(2).forEach { uri ->
                    Column(modifier = Modifier.weight(1f)) {
                        Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))) {
                            AsyncImage(
                                model = uri,
                                contentDescription = "Delete",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier.align(Alignment.BottomCenter).background(MaterialTheme.colorScheme.error.copy(alpha = 0.7f)).fillMaxWidth().padding(4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("DELETE", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}