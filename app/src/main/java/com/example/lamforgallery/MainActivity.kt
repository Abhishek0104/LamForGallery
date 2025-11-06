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

    private val viewModel: AgentViewModel by viewModels {
        AgentViewModelFactory(application)
    }

    // --- READ PERMISSION ---
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
            isPermissionGranted = isGranted
        }
    // --- END READ PERMISSION ---


    // --- MODIFY PERMISSIONS (GENERALIZED) ---
    // We need to keep track of what *type* of permission we just asked for
    private var currentPermissionType: PermissionType? = null

    // We only need ONE launcher for all IntentSender requests
    private val permissionRequestLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { activityResult ->
            val type = currentPermissionType
            if (type == null) {
                Log.e(TAG, "permissionRequestLauncher result but currentPermissionType is null!")
                return@registerForActivityResult
            }

            val wasSuccessful = activityResult.resultCode == RESULT_OK
            Log.d(TAG, "Permission result for $type: ${if (wasSuccessful) "GRANTED" else "DENIED"}")
            viewModel.onPermissionResult(wasSuccessful, type)

            // Clear the type after use
            currentPermissionType = null
        }
    // --- END MODIFY PERMISSIONS ---


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
                        AgentScreen(
                            viewModel = viewModel,
                            onLaunchPermissionRequest = { intentSender, type ->
                                Log.d(TAG, "Launching permissionRequestLauncher for $type...")
                                // Save the type so our launcher's callback knows
                                // what it was for.
                                currentPermissionType = type
                                permissionRequestLauncher.launch(
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
        // ... (existing code, no changes) ...
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
    // ... (existing code, no changes) ...
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


@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
    // Generalized callback
    onLaunchPermissionRequest: (IntentSender, PermissionType) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState) {
        val state = uiState
        if (state is AgentUiState.RequiresPermission) {
            // The ViewModel is telling us to launch a permission request.
            // We pass *both* the intent and the type.
            Log.d("AgentScreen", "Detected RequiresPermission state: ${state.type}")
            onLaunchPermissionRequest(state.intentSender, state.type)
        }
    }

    var inputText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Bottom
    ) {

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
                is AgentUiState.RequiresPermission -> {
                    // This UI now uses the dynamic message from the state
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            state.message,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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
                    inputText = ""
                },
                enabled = uiState !is AgentUiState.Loading && uiState !is AgentUiState.RequiresPermission,
                modifier = Modifier.height(56.dp)
            ) {
                Text("Send")
            }
        }
    }
}