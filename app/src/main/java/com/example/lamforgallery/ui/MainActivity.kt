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
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {

    private val factory by lazy { ViewModelFactory(application) }

    private val agentViewModel: AgentViewModel by viewModels { factory }
    private val photosViewModel: PhotosViewModel by viewModels { factory }
    private val albumsViewModel: AlbumsViewModel by viewModels { factory }
    private val embeddingViewModel: EmbeddingViewModel by viewModels { factory }
    // --- NEW VM ---
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
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (isPermissionGranted) {
                        AppNavigationHost(
                            factory = factory,
                            agentViewModel = agentViewModel,
                            photosViewModel = photosViewModel,
                            albumsViewModel = albumsViewModel,
                            embeddingViewModel = embeddingViewModel,
                            photoViewerViewModel = photoViewerViewModel, // Pass it down
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
    photoViewerViewModel: PhotoViewerViewModel, // New parameter
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
                    // --- LOGIC CHANGE: Set the playlist before navigating ---
                    val currentList = photosViewModel.uiState.value.photos
                    val uri = try {
                        java.net.URLDecoder.decode(encodedUri, "UTF-8")
                    } catch(e:Exception) { encodedUri }

                    photoViewerViewModel.setPhotoList(currentList, uri)
                    // We still pass URI for route uniqueness/deep-linking logic,
                    // but SinglePhotoScreen will use the VM
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
                    // Also allow swiping from Album View
                    val currentList = albumDetailViewModel.uiState.value.photos
                    photoViewerViewModel.setPhotoList(currentList, uri)
                    navController.navigate("view_photo")
                }
            )
        }

        // --- CHANGED: Removed URI arg from route, data comes from VM ---
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
            NavigationBar {
                NavigationBarItem(selected = selectedTab == "photos", onClick = { onTabSelected("photos") }, icon = { Icon(Icons.Default.Photo, "Photos") }, label = { Text("Photos") })
                NavigationBarItem(selected = selectedTab == "albums", onClick = { onTabSelected("albums") }, icon = { Icon(Icons.Default.PhotoAlbum, "Albums") }, label = { Text("Albums") })
                NavigationBarItem(selected = selectedTab == "agent", onClick = { onTabSelected("agent") }, icon = { Icon(Icons.Default.ChatBubble, "Agent") }, label = { Text("Agent") })
                NavigationBarItem(selected = selectedTab == "indexing", onClick = { onTabSelected("indexing") }, icon = { Icon(Icons.Default.ImageSearch, "Indexing") }, label = { Text("Indexing") })
            }
        }
    ) { paddingValues ->
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