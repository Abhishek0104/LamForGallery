package com.example.lamforgallery.ui

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
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
// --- CHANGE THESE IMPORTS ---
import androidx.compose.material.icons.filled.ChatBubble // Using a default icon is easier
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoAlbum
// --- END IMPORTS ---
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat

/**
 * This is now the main entry point, responsible for:
 * 1. Handling all permissions.
 * 2. Initializing all ViewModels.
 * 3. Hosting the Bottom Navigation Bar.
 * 4. Switching between the three main screens.
 */
class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    // --- Unified ViewModel Factory ---
    private val factory by lazy { ViewModelFactory(application) }

    // --- Initialize all 3 ViewModels ---
    private val agentViewModel: AgentViewModel by viewModels { factory }
    private val photosViewModel: PhotosViewModel by viewModels { factory }
    private val albumsViewModel: AlbumsViewModel by viewModels { factory }

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
            if (isGranted) {
                // Load content *after* permission is granted
                photosViewModel.loadPhotos()
                albumsViewModel.loadAlbums()
            }
        }
    // --- END READ PERMISSION ---


    // --- MODIFY PERMISSIONS (DELETE/MOVE) ---
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
            agentViewModel.onPermissionResult(wasSuccessful, type)
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
                        // --- Show the main App Shell ---
                        AppShell(
                            agentViewModel = agentViewModel,
                            photosViewModel = photosViewModel,
                            albumsViewModel = albumsViewModel,
                            onLaunchPermissionRequest = { intentSender, type ->
                                Log.d(TAG, "Launching permissionRequestLauncher for $type...")
                                currentPermissionType = type
                                permissionRequestLauncher.launch(
                                    IntentSenderRequest.Builder(intentSender).build()
                                )
                            }
                        )
                    } else {
                        // --- Show the permission rationale screen ---
                        // (This composable is now in AgentScreen.kt)
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

                // --- THIS IS THE FIX ---
                // We have permission, so load the content now.
                photosViewModel.loadPhotos()
                albumsViewModel.loadAlbums()
                // --- END FIX ---
            }
            // You can add a rationale check here if needed
            else -> {
                requestPermissionLauncher.launch(permissionToRequest)
            }
        }
    }
}

/**
 * The main app shell, containing the Bottom Navigation and the
 * content area that switches between screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    agentViewModel: AgentViewModel,
    photosViewModel: PhotosViewModel,
    albumsViewModel: AlbumsViewModel,
    onLaunchPermissionRequest: (IntentSender, PermissionType) -> Unit
) {
    // State to track the selected tab
    var selectedTab by remember { mutableStateOf("photos") }

    Scaffold(
        bottomBar = {
            NavigationBar {
                // --- Photos Tab ---
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Photo, contentDescription = "Photos") }, // This is in Default
                    label = { Text("Photos") },
                    selected = selectedTab == "photos",
                    onClick = { selectedTab = "photos" }
                )

                // --- Albums Tab ---
                NavigationBarItem(
                    icon = { Icon(Icons.Default.PhotoAlbum, contentDescription = "Albums") }, // This is in Default
                    label = { Text("Albums") },
                    selected = selectedTab == "albums",
                    onClick = { selectedTab = "albums" }
                )

                // --- Agent Tab ---
                NavigationBarItem(
                    // --- THIS IS THE FIX ---
                    // 'Chat' is not in Default. 'ChatBubble' is.
                    icon = { Icon(Icons.Default.ChatBubble, contentDescription = "Agent") },
                    // --- END FIX ---
                    label = { Text("Agent") },
                    selected = selectedTab == "agent",
                    onClick = { selectedTab = "agent" }
                )
            }
        }
    ) { paddingValues ->
        // This is the main content area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues) // Apply padding from the Scaffold
        ) {
            // Switch the screen based on the selected tab
            when (selectedTab) {
                "photos" -> PhotosScreen(viewModel = photosViewModel)
                "albums" -> AlbumsScreen(viewModel = albumsViewModel)
                "agent" -> AgentScreen(
                    viewModel = agentViewModel,
                    onLaunchPermissionRequest = onLaunchPermissionRequest
                )
            }
        }
    }
}