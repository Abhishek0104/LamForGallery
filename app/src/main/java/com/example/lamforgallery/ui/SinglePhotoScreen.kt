package com.example.lamforgallery.ui

import android.location.Geocoder
import android.net.Uri
import android.os.Build
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

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
    val scope = rememberCoroutineScope() // <--- Needed for Geocoder background work
    val photoUri = try {
        URLDecoder.decode(photoUriEncoded, StandardCharsets.UTF_8.name())
    } catch (e: Exception) { photoUriEncoded }

    // State for local metadata display
    var showInfoDialog by remember { mutableStateOf(false) }
    var metadataText by remember { mutableStateOf("") }
    var isLoadingMetadata by remember { mutableStateOf(false) } // <--- UX State

    // Function to read EXIF data locally
    fun readMetadata() {
        scope.launch { // <--- Launch in coroutine
            isLoadingMetadata = true
            showInfoDialog = true // Show dialog immediately with loading state
            metadataText = "Loading details..."

            val info = withContext(Dispatchers.IO) {
                try {
                    val uri = Uri.parse(photoUri)
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val exif = ExifInterface(inputStream)
                        val date = exif.getAttribute(ExifInterface.TAG_DATETIME) ?: "Unknown Date"
                        val make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: ""
                        val model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: "Unknown Camera"
                        val width = exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH) ?: "-"
                        val height = exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH) ?: "-"

                        // --- NEW: Get Lat/Long and Reverse Geocode ---
                        var locationString = "No Location Data"
                        val latLong = exif.latLong
                        if (latLong != null) {
                            val (lat, lon) = latLong
                            locationString = try {
                                val geocoder = Geocoder(context, Locale.getDefault())
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    // For simple synchronous blocking in this IO block, we use the deprecated one
                                    // or we could use the listener.
                                    // Since we are already in Dispatchers.IO, this blocking call is safe and easiest.
                                    @Suppress("DEPRECATION")
                                    val addresses = geocoder.getFromLocation(lat, lon, 1)
                                    val address = addresses?.firstOrNull()
                                    if (address != null) {
                                        val city = address.locality ?: address.subAdminArea
                                        val country = address.countryName
                                        "$city, $country"
                                    } else "$lat, $lon"
                                } else {
                                    @Suppress("DEPRECATION")
                                    val addresses = geocoder.getFromLocation(lat, lon, 1)
                                    val address = addresses?.firstOrNull()
                                    if (address != null) {
                                        val city = address.locality ?: address.subAdminArea
                                        val country = address.countryName
                                        "$city, $country"
                                    } else "$lat, $lon"
                                }
                            } catch (e: Exception) {
                                "$lat, $lon (Geocoding failed)"
                            }
                        }
                        // ---------------------------------------------

                        val sb = StringBuilder()
                        sb.append("Date: $date\n")
                        sb.append("Location: $locationString\n") // <--- Added Line
                        sb.append("Device: $make $model\n")
                        sb.append("Resolution: ${width}x${height}")
                        sb.toString()
                    } ?: "Could not access file."
                } catch (e: Exception) {
                    "Could not read metadata: ${e.message}"
                }
            }
            metadataText = info
            isLoadingMetadata = false
        }
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
        }

        // 3. The Action Overlay (Bottom)
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OverlayActionButton(
                        icon = Icons.Default.Info,
                        label = "Info",
                        onClick = { readMetadata() }
                    )
                    OverlayActionButton(
                        icon = Icons.Default.Edit,
                        label = "Edit",
                        onClick = { onAgentAction("I want to edit this photo") }
                    )
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
                text = {
                    if (isLoadingMetadata) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Text(metadataText)
                    }
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