package com.example.lamforgallery.ui

import android.app.Application
import android.content.IntentSender
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.lamforgallery.data.AgentRequest
import com.example.lamforgallery.data.AgentResponse
import com.example.lamforgallery.data.ToolCall
import com.example.lamforgallery.data.ToolResult
import com.example.lamforgallery.network.AgentApiService
import com.example.lamforgallery.network.NetworkModule
import com.example.lamforgallery.tools.GalleryTools
import com.example.lamforgallery.database.ImageEmbeddingDao
import com.example.lamforgallery.ml.ClipTokenizer
import com.example.lamforgallery.ml.TextEncoder
import com.example.lamforgallery.utils.SimilarityUtil
import com.example.lamforgallery.database.AppDatabase
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

// --- STATE DEFINITIONS ---

/**
 * Represents one message in the chat.
 * Includes optional image URIs for display.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val sender: Sender,
    val imageUris: List<String>? = null,
    val hasSelectionPrompt: Boolean = false // <-- Marks a message as "Tap to select"
)

enum class Sender {
    USER, AGENT, ERROR
}

/**
 * Represents the current *status* of the agent
 * (Loading, Waiting for Permission, or Idle)
 */
sealed class AgentStatus {
    data class Loading(val message: String) : AgentStatus()
    data class RequiresPermission(
        val intentSender: IntentSender,
        val type: PermissionType,
        val message: String
    ) : AgentStatus()
    object Idle : AgentStatus()
}

/**
 * The single, combined UI state, including the user's selection.
 */
data class AgentUiState(
    val messages: List<ChatMessage> = emptyList(),
    val currentStatus: AgentStatus = AgentStatus.Idle,
    val selectedImageUris: Set<String> = emptySet(), // Holds the *final* selection

    // --- STATE FOR THE BOTTOM SHEET ---
    val isSelectionSheetOpen: Boolean = false,
    val selectionSheetUris: List<String> = emptyList()
    // --- END STATE ---
)

enum class PermissionType { DELETE, WRITE }

// --- END STATE ---


class AgentViewModel(
    application: Application,
    private val agentApi: AgentApiService,
    private val galleryTools: GalleryTools,
    private val gson: Gson,
    private val imageEmbeddingDao: ImageEmbeddingDao,
    private val clipTokenizer: ClipTokenizer,
    private val textEncoder: TextEncoder
) : ViewModel() {

    private val TAG = "AgentViewModel"
    private var currentSessionId: String = UUID.randomUUID().toString()

    // Used to pause the loop for permission
    private var pendingToolCallId: String? = null
    private var pendingToolArgs: Map<String, Any>? = null

    private val _uiState = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    private val _galleryDidChange = MutableSharedFlow<Unit>()
    val galleryDidChange: SharedFlow<Unit> = _galleryDidChange.asSharedFlow()

    private data class SearchResult(val uri: String, val similarity: Float)

    /**
     * Called by the UI when a user taps an image *in the chat bubble*.
     */
    fun toggleImageSelection(uri: String) {
        _uiState.update { currentState ->
            val newSelection = currentState.selectedImageUris.toMutableSet()
            if (newSelection.contains(uri)) {
                newSelection.remove(uri)
            } else {
                newSelection.add(uri)
            }
            currentState.copy(selectedImageUris = newSelection)
        }
    }



    // --- FUNCTIONS FOR BOTTOM SHEET ---

    /**
     * Called by the UI (the chat bubble) to open the sheet.
     */
    fun openSelectionSheet(uris: List<String>) {
        _uiState.update {
            it.copy(
                isSelectionSheetOpen = true,
                selectionSheetUris = uris
            )
        }
    }

    /**
     * Called by the UI (the sheet's "Confirm" button).
     */
    fun confirmSelection(newSelection: Set<String>) {
        _uiState.update {
            it.copy(
                isSelectionSheetOpen = false,
                selectedImageUris = newSelection,
                selectionSheetUris = emptyList() // Clear sheet data
            )
        }
    }

    /**
     * Called by the UI (when the sheet is dismissed).
     */
    fun closeSelectionSheet() {
        _uiState.update {
            it.copy(
                isSelectionSheetOpen = false,
                selectionSheetUris = emptyList() // Clear sheet data
            )
        }
    }

    // --- END BOTTOM SHEET FUNCTIONS ---


    /**
     * Called by the UI when the user hits "Send".
     */
    fun sendUserInput(input: String) {
        val currentState = _uiState.value
        if (currentState.currentStatus !is AgentStatus.Idle) {
            Log.w(TAG, "Agent is busy, ignoring input.")
            return
        }

        // Get the current selection and clear it
        val selectedUris = currentState.selectedImageUris.toList()
        _uiState.update { it.copy(selectedImageUris = emptySet()) } // Clear selection

        viewModelScope.launch {
            addMessage(ChatMessage(text = input, sender = Sender.USER))
            setStatus(AgentStatus.Loading("Thinking..."))

            val request = AgentRequest(
                sessionId = currentSessionId,
                userInput = input,
                toolResult = null,
                selectedUris = selectedUris.ifEmpty { null }
            )
            Log.d(TAG, "Sending user input: $input, selection: $selectedUris")
            handleAgentRequest(request)
        }
    }

    /**
     * Called by the Activity when a permission dialog (delete/move) returns.
     */
    fun onPermissionResult(wasSuccessful: Boolean, type: PermissionType) {
        val toolCallId = pendingToolCallId
        val args = pendingToolArgs

        if (toolCallId == null) {
            Log.e(TAG, "onPermissionResult called but no pending tool call!")
            return
        }



        viewModelScope.launch {
            pendingToolCallId = null
            pendingToolArgs = null
            if (!wasSuccessful) {
                Log.w(TAG, "User denied permission for $type")
                addMessage(ChatMessage(text = "User denied permission.", sender = Sender.ERROR))
                sendToolResult(gson.toJson(false), toolCallId)
                return@launch
            }

            when (type) {
                PermissionType.DELETE -> {
                    // --- START FIX ---
                    if (args == null) {
                        Log.e(TAG, "Delete permission granted but no pending args!")
                        addMessage(ChatMessage(text = "Error: Missing context for delete operation.", sender = Sender.ERROR))
                        sendToolResult(gson.toJson(false), toolCallId)
                        return@launch
                    }

                    val urisToDelete = args["photo_uris"] as? List<String> ?: emptyList()

                    // Delete from database in the background
                    withContext(Dispatchers.IO) {
                        try {
                            urisToDelete.forEach { uri ->
                                imageEmbeddingDao.deleteByUri(uri)
                            }
                            Log.d(TAG, "Deleted ${urisToDelete.size} embeddings from database.")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to delete embeddings from DB", e)
                            // Don't block the agent, just log the error
                        }
                    }
                    // 2. Clean up local UI state (prevents black box on re-search)
                    _uiState.update { currentState ->
                        currentState.copy(
                            selectedImageUris = currentState.selectedImageUris - urisToDelete.toSet(),
                            selectionSheetUris = currentState.selectionSheetUris - urisToDelete.toSet()
                        )
                    }

                    // 3. Signal to other ViewModels (like EmbeddingViewModel) to refresh their count
                    _galleryDidChange.emit(Unit)
                    // --- END FIX/UPDATE ---

                    sendToolResult(gson.toJson(true), toolCallId)
                    _galleryDidChange.emit(Unit)
                }
                PermissionType.WRITE -> {
                    if (args == null) {
                        Log.e(TAG, "Write permission granted but no pending args!")
                        addMessage(ChatMessage(text = "Error: Missing context for move operation.", sender = Sender.ERROR))
                        sendToolResult(gson.toJson(false), toolCallId)
                        return@launch
                    }
                    setStatus(AgentStatus.Loading("Moving files..."))
                    val uris = args["photo_uris"] as? List<String> ?: emptyList()
                    val album = args["album_name"] as? String ?: "New Album"
                    val moveResult = galleryTools.performMoveOperation(uris, album)
                    sendToolResult(gson.toJson(moveResult), toolCallId)
                    _galleryDidChange.emit(Unit)
                }
            }
        }
    }

    private suspend fun handleAgentRequest(request: AgentRequest) {
        try {
            val response = agentApi.invokeAgent(request)
            currentSessionId = response.sessionId

            when (response.status) {
                "complete" -> {
                    val message = response.agentMessage ?: "Done."
                    addMessage(ChatMessage(text = message, sender= Sender.AGENT))
                    setStatus(AgentStatus.Idle)
                }
                "requires_action" -> {
                    val action = response.nextActions?.firstOrNull()
                    if (action == null) {
                        Log.e(TAG, "Agent required action but sent none.")
                        addMessage(ChatMessage(text = "Agent error: No action provided.", sender= Sender.ERROR))
                        setStatus(AgentStatus.Idle)
                        return
                    }

                    setStatus(AgentStatus.Loading("Working on it: ${action.name}..."))

                    val toolResultObject = executeLocalTool(action)

                    if (toolResultObject != null) {
                        val resultJson = gson.toJson(toolResultObject)
                        sendToolResult(resultJson, action.id)
                    } else {
                        Log.d(TAG, "Loop paused, waiting for permission result.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in agent loop", e)
            addMessage(ChatMessage(text = e.message ?: "Unknown network error", sender= Sender.ERROR))
            setStatus(AgentStatus.Idle)
        }
    }

    private suspend fun executeLocalTool(toolCall: ToolCall): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            (toolCall.name == "delete_photos" || toolCall.name == "move_photos_to_album")) {
            Log.w(TAG, "Skipping ${toolCall.name}, requires Android 11+")
            return mapOf("error" to "Modify/Delete operations require Android 11+")
        }

        val result: Any? = try {
            when (toolCall.name) {
                "search_photos" -> {
                    val query = toolCall.args["query"] as? String ?: ""
                    Log.d(TAG, "Performing SEMANTIC search for: $query")

                    // Run search logic on a background thread
                    val foundUris = withContext(Dispatchers.IO) {
                        // 1. Tokenize the text query
                        val tokens = clipTokenizer.tokenize(query)

                        // 2. Encode the tokens into a text embedding
                        val textEmbedding = textEncoder.encode(tokens)

                        // 3. Get all image embeddings from the database
                        // !!! ASSUMPTION: You have a DAO function `getAll()`
                        // !!! ASSUMPTION: Your entity is `ImageEmbedding`
                        val allImageEmbeddings = imageEmbeddingDao.getAllEmbeddings()
                        Log.d(TAG, "Comparing query against ${allImageEmbeddings.size} images")

                        val similarityResults = mutableListOf<SearchResult>()

                        // 4. Calculate similarity for each image
                        for (imageEmbedding in allImageEmbeddings) {
                            // !!! ASSUMPTION: The vector is on `.embedding` property
                            val similarity = SimilarityUtil.cosineSimilarity(
                                textEmbedding,
                                imageEmbedding.embedding
                            )

                            // --- ADDED LOGGING ---
                            // Get last 20 chars of URI for a cleaner log
                            val shortUri = imageEmbedding.uri.takeLast(20)
                            Log.d(TAG, "Similarity for ...$shortUri: $similarity")
                            // --- END ADDED LOGGING ---

                            // 5. Filter by a similarity threshold
                            if (similarity > 0.2f) { // <-- You can tune this threshold
                                // !!! ASSUMPTION: The URI is on `.uri` property
                                similarityResults.add(
                                    SearchResult(imageEmbedding.uri, similarity)
                                )
                            }
                        }

                        // 6. Sort by similarity (highest first)
                        similarityResults.sortByDescending { it.similarity }

                        // 7. Return just the list of URIs
                        similarityResults.map { it.uri }
                    }

                    // --- The rest of this is your *existing* UI logic ---
                    val message: String
                    if (foundUris.isEmpty()) {
                        message = "I couldn't find any photos matching '$query'."
                        addMessage(ChatMessage(text = message, sender= Sender.AGENT))
                    } else {
                        _uiState.update {
                            it.copy(
                                isSelectionSheetOpen = true,
                                selectionSheetUris = foundUris
                            )
                        }
                        message = "I found ${foundUris.size} photos for you. [Tap to view and select]"
                        addMessage(ChatMessage(
                            text = message,
                            sender = Sender.AGENT,
                            imageUris = foundUris, // Store URIs in the message
                            hasSelectionPrompt = true
                        ))
                    }
                    mapOf("photos_found" to foundUris.size)
                }
                "delete_photos" -> {
                    val uris = getUrisFromArgsOrSelection(toolCall.args["photo_uris"])
                    if (uris.isEmpty()) throw Exception("No photos were selected for deletion.")

                    val intentSender = galleryTools.createDeleteIntentSender(uris)

                    if (intentSender != null) {
                        pendingToolCallId = toolCall.id
                        pendingToolArgs = mapOf("photo_uris" to uris)
                        setStatus(AgentStatus.RequiresPermission(
                            intentSender, PermissionType.DELETE, "Waiting for user permission to delete..."
                        ))
                        null
                    } else {
                        mapOf("error" to "Could not create delete request.")
                    }
                }
                "move_photos_to_album" -> {
                    val uris = getUrisFromArgsOrSelection(toolCall.args["photo_uris"])
                    if (uris.isEmpty()) throw Exception("No photos were selected to move.")

                    val intentSender = galleryTools.createWriteIntentSender(uris)

                    if (intentSender != null) {
                        pendingToolCallId = toolCall.id
                        pendingToolArgs = mapOf(
                            "photo_uris" to uris,
                            "album_name" to (toolCall.args["album_name"] as? String ?: "New Album")
                        )
                        setStatus(AgentStatus.RequiresPermission(
                            intentSender, PermissionType.WRITE, "Waiting for user permission to move files..."
                        ))
                        null
                    } else {
                        mapOf("error" to "Could not create write/move request.")
                    }
                }
                "create_collage" -> {
                    val uris = getUrisFromArgsOrSelection(toolCall.args["photo_uris"])
                    if (uris.isEmpty()) throw Exception("No photos were selected for the collage.")

                    val title = toolCall.args["title"] as? String ?: "My Collage"
                    val newCollageUri = galleryTools.createCollage(uris, title)

                    // Collage is simple, we can still show the result in-line
                    val message = "I've created the collage '$title' for you."
                    val imageList = if (newCollageUri != null) listOf(newCollageUri) else null
                    addMessage(ChatMessage(text = message, sender= Sender.AGENT, imageUris = imageList))
                    viewModelScope.launch { _galleryDidChange.emit(Unit) }
                    newCollageUri
                }

                // --- *** THIS IS THE FIX *** ---
                "apply_filter" -> {
                    val uris = getUrisFromArgsOrSelection(toolCall.args["photo_uris"])
                    if (uris.isEmpty()) throw Exception("No photos were selected to apply a filter.")

                    val filterName = toolCall.args["filter_name"] as? String ?: "grayscale"
                    val newImageUris = galleryTools.applyFilter(uris, filterName)

                    val message: String
                    if (newImageUris.isEmpty()) {
                        message = "I wasn't able to apply the filter to the selected photos."
                        addMessage(ChatMessage(text = message, sender = Sender.ERROR))
                    } else {
                        // 1. Set state to open the sheet
                        _uiState.update {
                            it.copy(
                                isSelectionSheetOpen = true,
                                selectionSheetUris = newImageUris
                            )
                        }
                        // 2. Add a *prompt* to the chat
                        message = "I've applied the '$filterName' filter to ${newImageUris.size} photo(s). [Tap to view]"
                        addMessage(ChatMessage(
                            text = message,
                            sender = Sender.AGENT,
                            imageUris = newImageUris, // Store URIs in the message
                            hasSelectionPrompt = true
                        ))
                    }
                    if (newImageUris.isNotEmpty()) {
                        viewModelScope.launch { _galleryDidChange.emit(Unit) }
                    }
                    newImageUris
                }
                // --- *** END OF FIX *** ---

                else -> {
                    Log.w(TAG, "Unknown tool called: ${toolCall.name}")
                    mapOf("error" to "Tool '${toolCall.name}' is not implemented on this client.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing local tool: ${toolCall.name}", e)
            addMessage(ChatMessage(text = "Error: ${e.message}", sender = Sender.ERROR))
            mapOf("error" to "Failed to execute ${toolCall.name}: ${e.message}")
        }

        return result
    }

    private fun getUrisFromArgsOrSelection(argUris: Any?): List<String> {
        val selectedUris = _uiState.value.selectedImageUris

        if (selectedUris.isNotEmpty()) {
            return selectedUris.toList()
        }

        return (argUris as? List<String>) ?: emptyList()
    }

    private fun sendToolResult(resultJsonString: String, toolCallId: String) {
        viewModelScope.launch {
            setStatus(AgentStatus.Loading("Sending result to agent..."))

            val toolResult = ToolResult(
                toolCallId = toolCallId,
                content = resultJsonString
            )

            val request = AgentRequest(
                sessionId = currentSessionId,
                userInput = null,
                toolResult = toolResult,
                selectedUris = null
            )

            Log.d(TAG, "Sending tool result: $resultJsonString")
            handleAgentRequest(request)
        }
    }

    private fun addMessage(message: ChatMessage) {
        _uiState.update { currentState ->
            currentState.copy(messages = currentState.messages + message)
        }
    }

    private fun setStatus(newStatus: AgentStatus) {
        _uiState.update { it.copy(currentStatus = newStatus) }
    }
}


/**
 * ViewModel Factory
 */
class AgentViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {

    private val gson: Gson by lazy {
        NetworkModule.gson
    }

    private val galleryTools: GalleryTools by lazy {
        GalleryTools(application.contentResolver)
    }

    private val agentApi: AgentApiService by lazy {
        NetworkModule.apiService
    }

    // --- ADDED DEPENDENCIES ---
    private val appDatabase: AppDatabase by lazy {
        AppDatabase.getDatabase(application)
    }

    private val imageEmbeddingDao: ImageEmbeddingDao by lazy {
        appDatabase.imageEmbeddingDao()
    }

    private val clipTokenizer: ClipTokenizer by lazy {
        ClipTokenizer(application)
    }

    private val textEncoder: TextEncoder by lazy {
        TextEncoder(application)
    }
    // --- END ADDED DEPENDENCIES ---

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AgentViewModel::class.java)) {
            return AgentViewModel(
                application,
                agentApi,
                galleryTools,
                gson,
                imageEmbeddingDao,
                clipTokenizer,
                textEncoder
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}