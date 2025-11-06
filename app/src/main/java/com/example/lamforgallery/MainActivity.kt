package com.example.lamforgallery

import android.Manifest
import android.app.Activity.RESULT_OK
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    // Get the ViewModel using our custom factory
    private val viewModel: AgentViewModel by viewModels {
        AgentViewModelFactory(application)
    }

    // --- PERMISSION HANDLING (READ) ---
    private val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    private var isPermissionGranted by mutableStateOf(false)
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                Log.d(TAG, "READ permission granted!")
                isPermissionGranted = true
            } else {
                Log.d(TAG, "READ permission denied.")
                isPermissionGranted = false
            }
        }
    // --- END READ PERMISSION ---


    // --- PERMISSION HANDLING (DELETE) ---
    // This is the new launcher for the delete confirmation dialog
    private val deleteRequestLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { activityResult ->
            if (activityResult.resultCode == RESULT_OK) {
                // User approved the deletion
                Log.d(TAG, "Delete permission GRANTED by user.")
                viewModel.onDeletePermissionResult(wasSuccessful = true)
            } else {
                // User denied or cancelled the deletion
                Log.d(TAG, "Delete permission DENIED by user.")
                viewModel.onDeletePermissionResult(wasSuccessful = false)
            }
        }
    // --- END DELETE PERMISSION ---


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestPermission()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isPermissionGranted) {
                        // We now pass the delete launcher function
                        // to the AgentScreen
                        AgentScreen(
                            viewModel = viewModel,
                            onLaunchDeleteRequest = { intentSender ->
                                Log.d(TAG, "Launching deleteRequestLauncher...")
                                deleteRequestLauncher.launch(
                                    IntentSenderRequest.Builder(intentSender).build()
                                )
                            }
                        )
                    } else {
                        PermissionDeniedScreen {
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
                Log.d(TAG, "Permission already granted.")
                isPermissionGranted = true
            }
            shouldShowRequestPermissionRationale(permissionToRequest) -> {
                requestPermissionLauncher.launch(permissionToRequest)
            }
            else -> {
                requestPermissionLauncher.launch(permissionToRequest)
            }
        }
    }
}

// ... PermissionDeniedScreen composable remains unchanged ...
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
fun AgentScreen(
    viewModel: AgentViewModel,
    onLaunchDeleteRequest: (IntentSender) -> Unit // New parameter
) {
    // Collect the UI state from the ViewModel
    val uiState by viewModel.uiState.collectAsState()

    // --- NEW: Handle the delete permission request ---
    // We use a LaunchedEffect to handle the "one-shot" event of
    // needing to show a permission dialog. This effect will re-run
    // whenever the uiState changes.
    LaunchedEffect(uiState) {
        val state = uiState
        if (state is AgentUiState.RequiresPermission) {
            // The ViewModel is telling us to launch the delete request.
            Log.d("AgentScreen", "Detected RequiresPermission state, launching dialog...")
            onLaunchDeleteRequest(state.intentSender)
        }
    }
    // --- END NEW ---

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
                // --- NEW: Show a loading message while dialog is active ---
                is AgentUiState.RequiresPermission -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Waiting for user permission to delete...",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                // --- NEW: Disable button while loading OR waiting for permission ---
                enabled = uiState !is AgentUiState.Loading && uiState !is AgentUiState.RequiresPermission,
                modifier = Modifier.height(56.dp) // Match text field height
            ) {
                Text("Send")
            }
        }
    }
}