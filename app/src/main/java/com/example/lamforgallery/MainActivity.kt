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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage // <-- NEW IMPORT
import kotlinx.coroutines.launch
import java.util.UUID

// Your existing MainActivity class ...
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
    private var currentPermissionType: PermissionType? = null
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
                ChatMessageItem(message = message)
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
 * --- THIS COMPOSABLE IS NOW UPDATED ---
 */
@Composable
fun ChatMessageItem(message: ChatMessage) {
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
            // Wrap in Column to stack text and images
            Column(modifier = Modifier.padding(12.dp)) {
                // Render the text message
                Text(
                    text = message.text,
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge,
                )

                // --- NEW IMAGE DISPLAY LOGIC ---
                message.imageUris?.let { uris ->
                    if (uris.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))

                        // Use a horizontal row for multiple images
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            uris.take(3).forEach { uri -> // Show up to 3 images as a preview
                                AsyncImage(
                                    model = uri,
                                    contentDescription = "Image from agent",
                                    contentScale = ContentScale.Crop, // Crop to fit
                                    modifier = Modifier
                                        .size(90.dp) // Fixed size for thumbnails
                                        .clip(RoundedCornerShape(8.dp)) // Rounded corners
                                )
                            }
                            if (uris.size > 3) {
                                // Add a simple indicator if there are more images
                                Box(
                                    modifier = Modifier
                                        .size(90.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "+${uris.size - 3}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            }
                        }
                    }
                }
                // --- END NEW IMAGE DISPLAY LOGIC ---
            }
        }
    }
}

/**
 * The bottom bar with the text field and send button.
 * It also displays the agent's current status.
 */
@Composable
fun ChatInputBar(
    status: AgentStatus,
    onSend: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val isEnabled = status is AgentStatus.Idle

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface) // Use surface color
            .padding(16.dp)
    ) {
        // --- STATUS INDICATOR ---
        // This box shows the "Thinking..." or "Waiting..." message
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp), // Give it a fixed height
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
                    // Show nothing when idle
                }
            }
        }
        // --- END STATUS ---

        Spacer(modifier = Modifier.height(8.dp)) // Space between status and input

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
                enabled = isEnabled && inputText.isNotBlank(),
                modifier = Modifier.height(56.dp) // Match text field height
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