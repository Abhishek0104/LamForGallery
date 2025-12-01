package com.example.lamforgallery.ui

import android.location.Geocoder
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A swipable full-screen photo viewer.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SinglePhotoScreen(
    viewModel: PhotoViewerViewModel,
    onNavigateBack: () -> Unit,
    onAgentAction: (String, String) -> Unit // pass prompt AND uri
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // If no photos, back out (shouldn't happen)
    if (uiState.photos.isEmpty()) {
        LaunchedEffect(Unit) { onNavigateBack() }
        return
    }

    // Pager State (Starts at the clicked photo)
    val pagerState = rememberPagerState(
        initialPage = uiState.initialIndex,
        pageCount = { uiState.photos.size }
    )

    // State for metadata dialog
    var showInfoDialog by remember { mutableStateOf(false) }
    var metadataText by remember { mutableStateOf("") }
    var isLoadingMetadata by remember { mutableStateOf(false) }

    // Helper to read metadata for the CURRENT page
    fun readMetadata(currentUriString: String) {
        scope.launch {
            isLoadingMetadata = true
            showInfoDialog = true
            metadataText = "Loading details..."

            val info = withContext(Dispatchers.IO) {
                val embedding = viewModel.getMetadataForUri(currentUriString)
                if (embedding != null) {
                    val date = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(embedding.dateTaken))
                    val locationString = embedding.location ?: "No Location Data"
                    val cameraModel = embedding.cameraModel ?: "Unknown Camera"
                    val resolution = "${embedding.width}x${embedding.height}"

                    val sb = StringBuilder()
                    sb.append("Date: $date\n")
                    sb.append("Location: $locationString\n")
                    sb.append("Device: $cameraModel\n")
                    sb.append("Resolution: $resolution")
                    sb.toString()
                } else {
                    "Could not find metadata in database."
                }
            }
            metadataText = info
            isLoadingMetadata = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 1. Horizontal Pager for Swiping
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val photoUri = uiState.photos[page]
            AsyncImage(
                model = photoUri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp)
                .align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            // Page Indicator (e.g., "5 / 100")
            Text(
                text = "${pagerState.currentPage + 1} / ${uiState.photos.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        // 3. Bottom Actions
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth()
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val currentUri = uiState.photos.getOrNull(pagerState.currentPage) ?: ""

                    OverlayActionButton(Icons.Default.Info, "Info") {
                        readMetadata(currentUri)
                    }
                    OverlayActionButton(Icons.Default.Edit, "Edit") {
                        onAgentAction("I want to edit this photo", currentUri)
                    }
                    OverlayActionButton(Icons.Default.AutoAwesome, "Similar") {
                        onAgentAction("Find similar photos to this", currentUri)
                    }
                }
            }
        }

        // 4. Metadata Dialog
        if (showInfoDialog) {
            AlertDialog(
                onDismissRequest = { showInfoDialog = false },
                confirmButton = { TextButton(onClick = { showInfoDialog = false }) { Text("Close") } },
                title = { Text("Image Details") },
                text = {
                    if (isLoadingMetadata) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    else Text(metadataText)
                }
            )
        }
    }
}

@Composable
fun OverlayActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp).background(Color.White.copy(alpha = 0.15f), CircleShape)
        ) {
            Icon(icon, contentDescription = label, tint = Color.White)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelSmall)
    }
}