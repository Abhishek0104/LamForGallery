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
import com.example.lamforgallery.utils.CleanupManager // --- NEW IMPORT ---
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
    val hasSelectionPrompt: Boolean = false,
    val isCleanupPrompt: Boolean = false // --- NEW FLAG ---
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
    val selectedImageUris: Set<String> = emptySet(),
    val isSelectionSheetOpen: Boolean = false,
    val selectionSheetUris: List<String> = emptyList(),

    // --- NEW CLEANUP STATE ---
    val cleanupGroups: List<CleanupManager.DuplicateGroup> = emptyList()
)

enum class PermissionType { DELETE, WRITE }

class AgentViewModel(
    application: Application,
    private val agentApi: AgentApiService,
    private val galleryTools: GalleryTools,
    private val gson: Gson,
    private val imageEmbeddingDao: ImageEmbeddingDao,
    private val clipTokenizer: ClipTokenizer,
    private val textEncoder: TextEncoder,
    private val cleanupManager: CleanupManager // --- NEW INJECTION ---
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

    fun toggleImageSelection(uri: String) {
        _uiState.update { currentState ->
            val newSelection = currentState.selectedImageUris.toMutableSet()
            if (newSelection.contains(uri)) newSelection.remove(uri) else newSelection.add(uri)
            currentState.copy(selectedImageUris = newSelection)
        }
    }

    fun setExternalSelection(uris: List<String>) {
        _uiState.update { it.copy(selectedImageUris = uris.toSet()) }
    }

    fun openSelectionSheet(uris: List<String>) {
        _uiState.update { it.copy(isSelectionSheetOpen = true, selectionSheetUris = uris) }
    }

    fun confirmSelection(newSelection: Set<String>) {
        _uiState.update { it.copy(isSelectionSheetOpen = false, selectedImageUris = newSelection, selectionSheetUris = emptyList()) }
    }

    fun closeSelectionSheet() {
        _uiState.update { it.copy(isSelectionSheetOpen = false, selectionSheetUris = emptyList()) }
    }

    fun sendUserInput(input: String) {
        val currentState = _uiState.value
        if (currentState.currentStatus !is AgentStatus.Idle) return

        val selectedUris = currentState.selectedImageUris.toList()
        _uiState.update { it.copy(selectedImageUris = emptySet()) }

        viewModelScope.launch {
            addMessage(ChatMessage(text = input, sender = Sender.USER))
            setStatus(AgentStatus.Loading("Thinking..."))

            val request = AgentRequest(
                sessionId = currentSessionId,
                userInput = input,
                toolResult = null,
                selectedUris = selectedUris.ifEmpty { null }
            )
            handleAgentRequest(request)
        }
    }

    fun onPermissionResult(wasSuccessful: Boolean, type: PermissionType) {
        val toolCallId = pendingToolCallId
        val args = pendingToolArgs
        if (toolCallId == null) return

        viewModelScope.launch {
            pendingToolCallId = null
            pendingToolArgs = null
            if (!wasSuccessful) {
                addMessage(ChatMessage(text = "User denied permission.", sender = Sender.ERROR))
                sendToolResult(gson.toJson(false), toolCallId)
                return@launch
            }

            when (type) {
                PermissionType.DELETE -> {
                    val urisToDelete = args?.get("photo_uris") as? List<String> ?: emptyList()
                    withContext(Dispatchers.IO) {
                        try { urisToDelete.forEach { uri -> imageEmbeddingDao.deleteByUri(uri) } }
                        catch (e: Exception) { Log.e(TAG, "DB delete failed", e) }
                    }
                    _uiState.update { it.copy(
                        selectedImageUris = it.selectedImageUris - urisToDelete.toSet(),
                        selectionSheetUris = it.selectionSheetUris - urisToDelete.toSet()
                    )}
                    sendToolResult(gson.toJson(true), toolCallId)
                    _galleryDidChange.emit(Unit)
                }
                PermissionType.WRITE -> {
                    setStatus(AgentStatus.Loading("Moving files..."))
                    val uris = args?.get("photo_uris") as? List<String> ?: emptyList()
                    val album = args?.get("album_name") as? String ?: "New Album"
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
                        addMessage(ChatMessage(text = "Agent error: No action provided.", sender= Sender.ERROR))
                        setStatus(AgentStatus.Idle)
                        return
                    }
                    setStatus(AgentStatus.Loading("Working on it: ${action.name}..."))
                    val toolResultObject = executeLocalTool(action)
                    if (toolResultObject != null) {
                        sendToolResult(gson.toJson(toolResultObject), action.id)
                    }
                }
            }
        } catch (e: Exception) {
            addMessage(ChatMessage(text = e.message ?: "Unknown network error", sender= Sender.ERROR))
            setStatus(AgentStatus.Idle)
        }
    }

    private suspend fun executeLocalTool(toolCall: ToolCall): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            (toolCall.name == "delete_photos" || toolCall.name == "move_photos_to_album")) {
            return mapOf("error" to "Modify/Delete operations require Android 11+")
        }

        val result: Any? = try {
            when (toolCall.name) {
                "search_photos" -> {
                    val query = toolCall.args["query"] as? String ?: ""
                    val foundUris = withContext(Dispatchers.IO) {
                        val tokens = clipTokenizer.tokenize(query)
                        val textEmbedding = textEncoder.encode(tokens)
                        val allImageEmbeddings = imageEmbeddingDao.getAllEmbeddings()
                        val results = allImageEmbeddings.mapNotNull {
                            val sim = SimilarityUtil.cosineSimilarity(textEmbedding, it.embedding)
                            if (sim > 0.2f) SearchResult(it.uri, sim) else null
                        }.sortedByDescending { it.similarity }.map { it.uri }
                        results
                    }
                    if (foundUris.isEmpty()) {
                        addMessage(ChatMessage(text = "I couldn't find photos matching '$query'.", sender= Sender.AGENT))
                    } else {
                        val message = "I found ${foundUris.size} photos for you."
                        addMessage(ChatMessage(text = message, sender = Sender.AGENT, imageUris = foundUris, hasSelectionPrompt = true))
                    }
                    mapOf("photos_found" to foundUris.size)
                }
                // --- NEW TOOL HANDLER ---
                "scan_for_cleanup" -> {
                    setStatus(AgentStatus.Loading("Scanning gallery for duplicates..."))
                    val duplicates = cleanupManager.findDuplicates()

                    if (duplicates.isEmpty()) {
                        addMessage(ChatMessage(text = "I scanned the gallery but didn't find any duplicates.", sender = Sender.AGENT))
                        mapOf("result" to "No duplicates found")
                    } else {
                        _uiState.update { it.copy(cleanupGroups = duplicates) }
                        val count = duplicates.sumOf { it.duplicateUris.size }
                        addMessage(ChatMessage(
                            text = "I found ${duplicates.size} sets of duplicates (approx $count photos). Tap to review.",
                            sender = Sender.AGENT,
                            isCleanupPrompt = true // Signals UI to show "Review" button
                        ))
                        mapOf("found_sets" to duplicates.size, "total_duplicates" to count)
                    }
                }
                // --- END NEW TOOL ---
                "delete_photos" -> {
                    val uris = getUrisFromArgsOrSelection(toolCall.args["photo_uris"])
                    val intentSender = galleryTools.createDeleteIntentSender(uris)
                    if (intentSender != null) {
                        pendingToolCallId = toolCall.id
                        pendingToolArgs = mapOf("photo_uris" to uris)
                        setStatus(AgentStatus.RequiresPermission(intentSender, PermissionType.DELETE, "Waiting for permission..."))
                        null
                    } else mapOf("error" to "Failed to create delete request")
                }
                // ... (Other tools remain identical) ...
                "move_photos_to_album" -> {
                    val uris = getUrisFromArgsOrSelection(toolCall.args["photo_uris"])
                    val intentSender = galleryTools.createWriteIntentSender(uris)
                    if (intentSender != null) {
                        pendingToolCallId = toolCall.id
                        pendingToolArgs = mapOf("photo_uris" to uris, "album_name" to (toolCall.args["album_name"] ?: "New Album"))
                        setStatus(AgentStatus.RequiresPermission(intentSender, PermissionType.WRITE, "Waiting for permission..."))
                        null
                    } else mapOf("error" to "Failed to create write request")
                }
                "create_collage" -> {
                    val uris = getUrisFromArgsOrSelection(toolCall.args["photo_uris"])
                    val title = toolCall.args["title"] as? String ?: "My Collage"
                    val newCollageUri = galleryTools.createCollage(uris, title)
                    val message = "I've created the collage '$title'."
                    val imageList = if (newCollageUri != null) listOf(newCollageUri) else null
                    addMessage(ChatMessage(text = message, sender= Sender.AGENT, imageUris = imageList))
                    viewModelScope.launch { _galleryDidChange.emit(Unit) }
                    newCollageUri
                }
                "apply_filter" -> {
                    val uris = getUrisFromArgsOrSelection(toolCall.args["photo_uris"])
                    val filterName = toolCall.args["filter_name"] as? String ?: "grayscale"
                    val newImageUris = galleryTools.applyFilter(uris, filterName)
                    val message = "I've applied the '$filterName' filter."
                    addMessage(ChatMessage(text = message, sender = Sender.AGENT, imageUris = newImageUris, hasSelectionPrompt = true))
                    if (newImageUris.isNotEmpty()) viewModelScope.launch { _galleryDidChange.emit(Unit) }
                    newImageUris
                }
                "get_photo_metadata" -> {
                    val uris = getUrisFromArgsOrSelection(toolCall.args["photo_uris"])
                    val metadataSummary = galleryTools.getPhotoMetadata(uris)
                    mapOf("metadata_summary" to metadataSummary)
                }
                else -> mapOf("error" to "Tool '${toolCall.name}' is not implemented.")
            }
        } catch (e: Exception) {
            addMessage(ChatMessage(text = "Error: ${e.message}", sender = Sender.ERROR))
            mapOf("error" to "Failed: ${e.message}")
        }
        return result
    }

    private fun getUrisFromArgsOrSelection(argUris: Any?): List<String> {
        val selectedUris = _uiState.value.selectedImageUris
        if (selectedUris.isNotEmpty()) return selectedUris.toList()
        return (argUris as? List<String>) ?: emptyList()
    }

    private fun sendToolResult(resultJsonString: String, toolCallId: String) {
        viewModelScope.launch {
            setStatus(AgentStatus.Loading("Sending result..."))
            val toolResult = ToolResult(toolCallId = toolCallId, content = resultJsonString)
            val request = AgentRequest(sessionId = currentSessionId, userInput = null, toolResult = toolResult)
            handleAgentRequest(request)
        }
    }

    private fun addMessage(message: ChatMessage) {
        _uiState.update { currentState -> currentState.copy(messages = currentState.messages + message) }
    }

    private fun setStatus(newStatus: AgentStatus) {
        _uiState.update { it.copy(currentStatus = newStatus) }
    }
}