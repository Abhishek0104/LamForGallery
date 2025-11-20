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
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import com.example.lamforgallery.ml.ClipTokenizer
import com.example.lamforgallery.ml.ImageEncoder
import com.example.lamforgallery.ml.TextEncoder
import android.graphics.Bitmap
import android.graphics.Color
import com.example.lamforgallery.database.AppDatabase
import com.example.lamforgallery.database.ImageEmbedding
import com.example.lamforgallery.ui.EmbeddingScreen
import com.example.lamforgallery.ui.EmbeddingViewModel

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    private val factory by lazy { ViewModelFactory(application) }

    // --- ViewModels (Activity-Scoped) ---
    private val agentViewModel: AgentViewModel by viewModels { factory }
    private val photosViewModel: PhotosViewModel by viewModels { factory }
    private val albumsViewModel: AlbumsViewModel by viewModels { factory }
    private val embeddingViewModel: EmbeddingViewModel by viewModels { factory }
    // --- End ViewModels ---

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
                Log.d(TAG, "Permission granted. Loading ViewModels.")
                loadAllViewModels()
            }
        }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestPermission()

        lifecycleScope.launch {
            agentViewModel.galleryDidChange.collect {
                Log.d(TAG, "Agent reported gallery change. Refreshing Photos and Albums.")
                photosViewModel.loadPhotos()
                albumsViewModel.loadAlbums()
            }
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isPermissionGranted) {
                        AppNavigationHost(
                            factory = factory,
                            agentViewModel = agentViewModel,
                            photosViewModel = photosViewModel,
                            albumsViewModel = albumsViewModel,
                            embeddingViewModel = embeddingViewModel,
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
                loadAllViewModels()
            }
            shouldShowRequestPermissionRationale(permissionToRequest) -> {
                requestPermissionLauncher.launch(permissionToRequest)
            }
            else -> {
                requestPermissionLauncher.launch(permissionToRequest)
            }
        }
    }

    private fun loadAllViewModels() {
        photosViewModel.loadPhotos()
        albumsViewModel.loadAlbums()
    }
}


@Composable
fun AppNavigationHost(
    factory: ViewModelProvider.Factory,
    agentViewModel: AgentViewModel,
    photosViewModel: PhotosViewModel,
    albumsViewModel: AlbumsViewModel,
    embeddingViewModel: EmbeddingViewModel,
    onLaunchPermissionRequest: (IntentSender, PermissionType) -> Unit
) {
    val navController = rememberNavController()
    var selectedTab by rememberSaveable { mutableStateOf("photos") }

    NavHost(navController = navController, startDestination = "main") {

        // Route 1: The main 3-tab screen
        composable("main") {
            AppShell(
                agentViewModel = agentViewModel,
                photosViewModel = photosViewModel,
                albumsViewModel = albumsViewModel,
                embeddingViewModel = embeddingViewModel,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                onAlbumClick = { encodedAlbumName ->
                    navController.navigate("album_detail/$encodedAlbumName")
                },
                onLaunchPermissionRequest = onLaunchPermissionRequest
            )
        }

        // Route 2: The Album Detail screen
        composable(
            route = "album_detail/{albumName}",
            arguments = listOf(navArgument("albumName") { type = NavType.StringType })
        ) { backStackEntry ->
            val albumName = backStackEntry.arguments?.getString("albumName") ?: "Unknown"
            val albumDetailViewModel: AlbumDetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)

            AlbumDetailScreen(
                albumName = albumName,
                viewModel = albumDetailViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    agentViewModel: AgentViewModel,
    photosViewModel: PhotosViewModel,
    albumsViewModel: AlbumsViewModel,
    embeddingViewModel: EmbeddingViewModel,
    selectedTab: String,
    onTabSelected: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onLaunchPermissionRequest: (IntentSender, PermissionType) -> Unit
) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == "photos",
                    onClick = { onTabSelected("photos") },
                    icon = { Icon(Icons.Default.Photo, contentDescription = "Photos") },
                    label = { Text("Photos") }
                )
                NavigationBarItem(
                    selected = selectedTab == "albums",
                    onClick = { onTabSelected("albums") },
                    icon = { Icon(Icons.Default.PhotoAlbum, contentDescription = "Albums") },
                    label = { Text("Albums") }
                )
                NavigationBarItem(
                    selected = selectedTab == "agent",
                    onClick = { onTabSelected("agent") },
                    icon = { Icon(Icons.Default.ChatBubble, contentDescription = "Agent") },
                    label = { Text("Agent") }
                )
                NavigationBarItem(
                    selected = selectedTab == "indexing",
                    onClick = { onTabSelected("indexing") },
                    icon = { Icon(Icons.Default.ImageSearch, contentDescription = "Indexing") },
                    label = { Text("Indexing") }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                "photos" -> PhotosScreen(
                    viewModel = photosViewModel,
                    // --- NEW: Handle the "Ask Agent" callback
                    onSendToAgent = { uris ->
                        // 1. Push the selected URIs to the agent's state
                        agentViewModel.setExternalSelection(uris)
                        // 2. Switch the tab to "agent"
                        onTabSelected("agent")
                    }
                )
                "albums" -> AlbumsScreen(
                    viewModel = albumsViewModel,
                    onAlbumClick = onAlbumClick
                )
                "agent" -> AgentScreen(
                    viewModel = agentViewModel,
                    onLaunchPermissionRequest = onLaunchPermissionRequest
                )
                "indexing" -> EmbeddingScreen(viewModel = embeddingViewModel)
            }
        }
    }
}