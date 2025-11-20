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

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val sender: Sender,
    val imageUris: List<String>? = null,
    val hasSelectionPrompt: Boolean = false
)

enum class Sender {
    USER, AGENT, ERROR
}

sealed class AgentStatus {
    data class Loading(val message: String) : AgentStatus()
    data class RequiresPermission(
        val intentSender: IntentSender,
        val type: PermissionType,
        val message: String
    ) : AgentStatus()
    object Idle : AgentStatus()
}

data class AgentUiState(
    val messages: List<ChatMessage> = emptyList(),
    val currentStatus: AgentStatus = AgentStatus.Idle,
    val selectedImageUris: Set<String> = emptySet(), // Holds the *final* selection

    // --- STATE FOR THE BOTTOM SHEET ---
    val isSelectionSheetOpen: Boolean = false,
    val selectionSheetUris: List<String> = emptyList()
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

    // --- NEW: ALLOW EXTERNAL SELECTION (Gallery to Agent) ---
    fun setExternalSelection(uris: List<String>) {
        _uiState.update {
            it.copy(selectedImageUris = uris.toSet())
        }
        // Optional: Add a system message or prompt to guide the user?
        // For now, we rely on the UI showing "X images selected"
    }
    // --- END NEW ---

    // --- FUNCTIONS FOR BOTTOM SHEET ---
    fun openSelectionSheet(uris: List<String>) {
        _uiState.update {
            it.copy(
                isSelectionSheetOpen = true,
                selectionSheetUris = uris
            )
        }
    }

    fun confirmSelection(newSelection: Set<String>) {
        _uiState.update {
            it.copy(
                isSelectionSheetOpen = false,
                selectedImageUris = newSelection,
                selectionSheetUris = emptyList()
            )
        }
    }

    fun closeSelectionSheet() {
        _uiState.update {
            it.copy(
                isSelectionSheetOpen = false,
                selectionSheetUris = emptyList()
            )
        }
    }
    // --- END BOTTOM SHEET FUNCTIONS ---


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
                        }
                    }

                    _uiState.update { currentState ->
                        currentState.copy(
                            selectedImageUris = currentState.selectedImageUris - urisToDelete.toSet(),
                            selectionSheetUris = currentState.selectionSheetUris - urisToDelete.toSet()
                        )
                    }

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

                    val foundUris = withContext(Dispatchers.IO) {
                        val tokens = clipTokenizer.tokenize(query)
                        val textEmbedding = textEncoder.encode(tokens)
                        val allImageEmbeddings = imageEmbeddingDao.getAllEmbeddings()

                        val similarityResults = mutableListOf<SearchResult>()

                        for (imageEmbedding in allImageEmbeddings) {
                            val similarity = SimilarityUtil.cosineSimilarity(
                                textEmbedding,
                                imageEmbedding.embedding
                            )

                            if (similarity > 0.2f) {
                                similarityResults.add(
                                    SearchResult(imageEmbedding.uri, similarity)
                                )
                            }
                        }
                        similarityResults.sortByDescending { it.similarity }
                        similarityResults.map { it.uri }
                    }

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
                            imageUris = foundUris,
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

                    val message = "I've created the collage '$title' for you."
                    val imageList = if (newCollageUri != null) listOf(newCollageUri) else null
                    addMessage(ChatMessage(text = message, sender= Sender.AGENT, imageUris = imageList))
                    viewModelScope.launch { _galleryDidChange.emit(Unit) }
                    newCollageUri
                }
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
                        _uiState.update {
                            it.copy(
                                isSelectionSheetOpen = true,
                                selectionSheetUris = newImageUris
                            )
                        }
                        message = "I've applied the '$filterName' filter to ${newImageUris.size} photo(s). [Tap to view]"
                        addMessage(ChatMessage(
                            text = message,
                            sender = Sender.AGENT,
                            imageUris = newImageUris,
                            hasSelectionPrompt = true
                        ))
                    }
                    if (newImageUris.isNotEmpty()) {
                        viewModelScope.launch { _galleryDidChange.emit(Unit) }
                    }
                    newImageUris
                }

                // --- NEW TOOL HANDLER ---
                "get_photo_metadata" -> {
                    val uris = getUrisFromArgsOrSelection(toolCall.args["photo_uris"])
                    if (uris.isEmpty()) throw Exception("No photos were selected to check metadata.")

                    val metadataSummary = galleryTools.getPhotoMetadata(uris)

                    // We return the raw text to the Agent so it can summarize/chat about it
                    mapOf("metadata_summary" to metadataSummary)
                }
                // --- END NEW HANDLER ---

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