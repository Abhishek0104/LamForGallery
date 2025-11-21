package com.example.lamforgallery.ui

import android.Manifest
import android.app.Activity.RESULT_OK
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {

    private val factory by lazy { ViewModelFactory(application) }

    private val agentViewModel: AgentViewModel by viewModels { factory }
    private val photosViewModel: PhotosViewModel by viewModels { factory }
    private val albumsViewModel: AlbumsViewModel by viewModels { factory }
    private val embeddingViewModel: EmbeddingViewModel by viewModels { factory }
    private val photoViewerViewModel: PhotoViewerViewModel by viewModels { factory }

    private val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    private var isPermissionGranted by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        isPermissionGranted = isGranted
        if (isGranted) loadAllViewModels()
    }

    private var currentPermissionType: PermissionType? = null
    private val permissionRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { activityResult ->
        val type = currentPermissionType
        if (type != null) {
            val wasSuccessful = activityResult.resultCode == RESULT_OK
            agentViewModel.onPermissionResult(wasSuccessful, type)
            currentPermissionType = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermission()
        lifecycleScope.launch {
            agentViewModel.galleryDidChange.collect {
                photosViewModel.loadPhotos()
                albumsViewModel.loadAlbums()
            }
        }
        setContent {
            // Apply Custom Theme Here (assuming standard MaterialTheme for now)
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (isPermissionGranted) {
                        AppNavigationHost(
                            factory = factory,
                            agentViewModel = agentViewModel,
                            photosViewModel = photosViewModel,
                            albumsViewModel = albumsViewModel,
                            embeddingViewModel = embeddingViewModel,
                            photoViewerViewModel = photoViewerViewModel,
                            onLaunchPermissionRequest = { intentSender, type ->
                                currentPermissionType = type
                                permissionRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                            }
                        )
                    } else {
                        PermissionDeniedScreen { requestPermissionLauncher.launch(permissionToRequest) }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermission() {
        if (ContextCompat.checkSelfPermission(this, permissionToRequest) == PackageManager.PERMISSION_GRANTED) {
            isPermissionGranted = true
            loadAllViewModels()
        } else {
            requestPermissionLauncher.launch(permissionToRequest)
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
    photoViewerViewModel: PhotoViewerViewModel,
    onLaunchPermissionRequest: (IntentSender, PermissionType) -> Unit
) {
    val navController = rememberNavController()
    var selectedTab by rememberSaveable { mutableStateOf("photos") }

    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            AppShell(
                agentViewModel = agentViewModel,
                photosViewModel = photosViewModel,
                albumsViewModel = albumsViewModel,
                embeddingViewModel = embeddingViewModel,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                onAlbumClick = { encodedName -> navController.navigate("album_detail/$encodedName") },
                onLaunchPermissionRequest = onLaunchPermissionRequest,
                onPhotoClick = { encodedUri ->
                    val currentList = photosViewModel.uiState.value.photos
                    val uri = try {
                        URLDecoder.decode(encodedUri, StandardCharsets.UTF_8.name())
                    } catch(e:Exception) { encodedUri }

                    photoViewerViewModel.setPhotoList(currentList, uri)
                    navController.navigate("view_photo")
                },
                onNavigateToCleanup = { navController.navigate("cleanup_review") }
            )
        }

        composable(
            route = "album_detail/{albumName}",
            arguments = listOf(navArgument("albumName") { type = NavType.StringType })
        ) { backStackEntry ->
            val albumName = backStackEntry.arguments?.getString("albumName") ?: "Unknown"
            val albumDetailViewModel: AlbumDetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
            AlbumDetailScreen(
                albumName = albumName,
                viewModel = albumDetailViewModel,
                onNavigateBack = { navController.popBackStack() },
                onPhotoClick = { uri ->
                    val currentList = albumDetailViewModel.uiState.value.photos
                    photoViewerViewModel.setPhotoList(currentList, uri)
                    navController.navigate("view_photo")
                }
            )
        }

        composable("view_photo") {
            SinglePhotoScreen(
                viewModel = photoViewerViewModel,
                onNavigateBack = { navController.popBackStack() },
                onAgentAction = { prompt, uri ->
                    agentViewModel.setExternalSelection(listOf(uri))
                    navController.popBackStack("main", inclusive = false)
                    selectedTab = "agent"
                    agentViewModel.sendUserInput(prompt)
                }
            )
        }

        composable("cleanup_review") {
            val uiState by agentViewModel.uiState.collectAsState()
            CleanupScreen(
                duplicateGroups = uiState.cleanupGroups,
                onNavigateBack = { navController.popBackStack() },
                onConfirmDelete = { urisToDelete ->
                    navController.popBackStack()
                    agentViewModel.setExternalSelection(urisToDelete)
                    agentViewModel.sendUserInput("Delete these duplicates")
                }
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
    onPhotoClick: (String) -> Unit,
    onLaunchPermissionRequest: (IntentSender, PermissionType) -> Unit,
    onNavigateToCleanup: () -> Unit
) {
    Scaffold(
        bottomBar = {
            // --- NEW: Glassmorphism Navigation Bar ---
            NavigationBar(
                // Use a slightly transparent surface color for the glass effect
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                // Optional: Add blur if supported (requires Android 12+ render effect modifier, skipping for simplicity)
            ) {
                NavigationBarItem(
                    selected = selectedTab == "photos",
                    onClick = { onTabSelected("photos") },
                    icon = { Icon(Icons.Default.Photo, "Photos") },
                    label = { Text("Photos") },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == "albums",
                    onClick = { onTabSelected("albums") },
                    icon = { Icon(Icons.Default.PhotoAlbum, "Albums") },
                    label = { Text("Albums") }
                )
                NavigationBarItem(
                    selected = selectedTab == "agent",
                    onClick = { onTabSelected("agent") },
                    icon = { Icon(Icons.Default.ChatBubble, "Agent") },
                    label = { Text("Agent") }
                )
                NavigationBarItem(
                    selected = selectedTab == "indexing",
                    onClick = { onTabSelected("indexing") },
                    icon = { Icon(Icons.Default.ImageSearch, "Indexing") },
                    label = { Text("Indexing") }
                )
            }
        }
    ) { paddingValues ->
        // We pass paddingValues down, but for a true "behind the nav bar" effect
        // on the photos screen, we might want to ignore the bottom padding
        // and handle it inside the list content padding.
        // For now, standard behavior is safer to avoid overlap issues.
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                "photos" -> PhotosScreen(
                    viewModel = photosViewModel,
                    onSendToAgent = { uris -> agentViewModel.setExternalSelection(uris); onTabSelected("agent") },
                    onPhotoClick = onPhotoClick
                )
                "albums" -> AlbumsScreen(viewModel = albumsViewModel, onAlbumClick = onAlbumClick)
                "agent" -> AgentScreen(
                    viewModel = agentViewModel,
                    onLaunchPermissionRequest = onLaunchPermissionRequest,
                    onNavigateToCleanup = onNavigateToCleanup
                )
                "indexing" -> EmbeddingScreen(viewModel = embeddingViewModel)
            }
        }
    }
}

@Composable
fun PermissionDeniedScreen(onRequestPermission: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Permission Required")
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRequestPermission) {
                Text("Grant Access")
            }
        }
    }
}