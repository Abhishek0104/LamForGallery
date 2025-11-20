package com.example.lamforgallery.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * A cleaner fullscreen photo viewer.
 * - "Info" fetches metadata locally (no AI).
 * - "Edit" and "Similar" still prompt the Agent.
 * - Removed Microphone button.
 * - Fixed Back button layout (added statusBarsPadding).
 */
@Composable
fun SinglePhotoScreen(
    photoUriEncoded: String,
    onNavigateBack: () -> Unit,
    onAgentAction: (String) -> Unit
) {
    val context = LocalContext.current
    val photoUri = try {
        URLDecoder.decode(photoUriEncoded, StandardCharsets.UTF_8.name())
    } catch (e: Exception) { photoUriEncoded }

    // State for local metadata display
    var showInfoDialog by remember { mutableStateOf(false) }
    var metadataText by remember { mutableStateOf("") }

    // Function to read EXIF data locally
    fun readMetadata() {
        try {
            val uri = Uri.parse(photoUri)
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                val date = exif.getAttribute(ExifInterface.TAG_DATETIME) ?: "Unknown Date"
                val make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: ""
                val model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: "Unknown Camera"
                val lat = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
                val long = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
                val width = exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH) ?: "-"
                val height = exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH) ?: "-"

                val sb = StringBuilder()
                sb.append("Date: $date\n")
                sb.append("Device: $make $model\n")
                sb.append("Resolution: ${width}x${height}\n")
                if (lat != null && long != null) {
                    sb.append("Location: $lat, $long")
                }
                metadataText = sb.toString()
            }
        } catch (e: Exception) {
            metadataText = "Could not read metadata: ${e.message}"
        }
        showInfoDialog = true
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // 1. The Full Screen Image
        AsyncImage(
            model = photoUri,
            contentDescription = "Full photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().align(Alignment.Center)
        )

        // 2. Top Bar (Transparent) with Status Bar Padding
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding() // <--- FIX: Ensures button isn't covered by status bar
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
        }

        // 3. The Action Overlay (Bottom)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth()
        ) {
            // Just the card, no microphone button
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // INFO: Reads local EXIF
                    OverlayActionButton(
                        icon = Icons.Default.Info,
                        label = "Info",
                        onClick = { readMetadata() }
                    )
                    // EDIT: Calls Agent
                    OverlayActionButton(
                        icon = Icons.Default.Edit,
                        label = "Edit",
                        onClick = { onAgentAction("I want to edit this photo") }
                    )
                    // SIMILAR: Calls Agent
                    OverlayActionButton(
                        icon = Icons.Default.AutoAwesome,
                        label = "Similar",
                        onClick = { onAgentAction("Find similar photos to this") }
                    )
                }
            }
        }

        // 4. Local Metadata Dialog
        if (showInfoDialog) {
            AlertDialog(
                onDismissRequest = { showInfoDialog = false },
                confirmButton = {
                    TextButton(onClick = { showInfoDialog = false }) {
                        Text("Close")
                    }
                },
                title = { Text("Image Details") },
                text = { Text(metadataText) }
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
            modifier = Modifier
                .size(48.dp)
                .background(Color.White.copy(alpha = 0.15f), CircleShape)
        ) {
            Icon(icon, contentDescription = label, tint = Color.White)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}