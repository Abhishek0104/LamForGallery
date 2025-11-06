package com.example.lamforgallery

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    // Get the ViewModel using our custom factory
    private val viewModel: AgentViewModel by viewModels {
        AgentViewModelFactory(application)
    }

    // --- PERMISSION HANDLING ---
    // 1. Define the permission we need.
    //    On Android 13+ (API 33+), we must ask for a specific media type.
    private val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    // 2. Create a state variable to track if permission is granted
    private var isPermissionGranted by mutableStateOf(false)

    // 3. Create an ActivityResultLauncher to request the permission
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                // Permission is granted.
                Log.d("MainActivity", "Permission granted!")
                isPermissionGranted = true
            } else {
                // Permission is denied.
                Log.d("MainActivity", "Permission denied.")
                isPermissionGranted = false
            }
        }
    // --- END PERMISSION HANDLING ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 4. Check and request permission on create
        checkAndRequestPermission()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {

                    if (isPermissionGranted) {
                        AgentScreen(viewModel = viewModel)
                    } else {
                        // Show a screen telling the user we need permission
                        PermissionDeniedScreen {
                            // Relaunch the permission dialog
                            requestPermissionLauncher.launch(permissionToRequest)
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                permissionToRequest
            ) == PackageManager.PERMISSION_GRANTED -> {
                // You can use the API that requires the permission.
                Log.d("MainActivity", "Permission already granted.")
                isPermissionGranted = true
            }
            shouldShowRequestPermissionRationale(permissionToRequest) -> {
                // In an educational UI, explain to the user why you need the
                // permission and then launch the request.
                // For now, we'll just launch it directly.
                requestPermissionLauncher.launch(permissionToRequest)
            }
            else -> {
                // Directly ask for the permission.
                requestPermissionLauncher.launch(permissionToRequest)
            }
        }
    }
}

@Composable
fun PermissionDeniedScreen(onRequestPermission: () -> Unit) {
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


@Composable
fun AgentScreen(viewModel: AgentViewModel) {
    // ... (This Composable function remains unchanged from before)
    // Collect the UI state from the ViewModel
    val uiState by viewModel.uiState.collectAsState()

    // State for the text input field
    var inputText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Bottom
    ) {

        // Display area for agent messages
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            when (val state = uiState) {
                is AgentUiState.Idle -> {
                    Text("Ask your gallery agent to do something...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is AgentUiState.Loading -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(state.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is AgentUiState.AgentMessage -> {
                    Text(state.message,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is AgentUiState.Error -> {
                    Text(state.error,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Input row
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
                singleLine = true
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    viewModel.sendUserInput(inputText)
                    inputText = "" // Clear input after sending
                },
                // Disable button while loading
                enabled = uiState !is AgentUiState.Loading,
                modifier = Modifier.height(56.dp) // Match text field height
            ) {
                Text("Send")
            }
        }
    }
}