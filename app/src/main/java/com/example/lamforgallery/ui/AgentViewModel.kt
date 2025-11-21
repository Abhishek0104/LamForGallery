// ... (imports match your original file)
package com.example.lamforgallery.ui

import android.app.Application
import android.content.IntentSender
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lamforgallery.data.AgentRequest
import com.example.lamforgallery.data.ToolResult
import com.example.lamforgallery.data.ToolCall
import com.example.lamforgallery.data.Suggestion
import com.example.lamforgallery.network.AgentApiService
import com.example.lamforgallery.tools.GalleryTools
import com.example.lamforgallery.database.ImageEmbeddingDao
import com.example.lamforgallery.ml.ClipTokenizer
import com.example.lamforgallery.ml.TextEncoder
import com.example.lamforgallery.utils.SimilarityUtil
import com.example.lamforgallery.utils.CleanupManager
import com.example.lamforgallery.utils.ImageHelper
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.time.LocalDate
import java.time.ZoneId

// ... (State classes ChatMessage, AgentUiState etc. - copy from original)
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val sender: Sender,
    val imageUris: List<String>? = null,
    val hasSelectionPrompt: Boolean = false,
    val isCleanupPrompt: Boolean = false,
    val suggestions: List<Suggestion>? = null
)

enum class Sender { USER, AGENT, ERROR }

sealed class AgentStatus {
    data class Loading(val message: String) : AgentStatus()
    data class RequiresPermission(val intentSender: IntentSender, val type: PermissionType, val message: String) : AgentStatus()
    object Idle : AgentStatus()
}

data class AgentUiState(
    val messages: List<ChatMessage> = emptyList(),
    val currentStatus: AgentStatus = AgentStatus.Idle,
    val selectedImageUris: Set<String> = emptySet(),
    val isSelectionSheetOpen: Boolean = false,
    val selectionSheetUris: List<String> = emptyList(),
    val cleanupGroups: List<CleanupManager.DuplicateGroup> = emptyList()
)

enum class PermissionType { DELETE, WRITE }

class AgentViewModel(
    private val application: Application,
    private val agentApi: AgentApiService,
    private val galleryTools: GalleryTools,
    private val gson: Gson,
    private val imageEmbeddingDao: ImageEmbeddingDao,
    private val clipTokenizer: ClipTokenizer,
    private val textEncoder: TextEncoder,
    private val cleanupManager: CleanupManager
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

    // ... (Keep toggleImageSelection, setExternalSelection, openSelectionSheet, confirmSelection, closeSelectionSheet, sendUserInput, onPermissionResult, handleAgentRequest) ...
    // Assume these methods exist exactly as in your previous file.

    fun toggleImageSelection(uri: String) { _uiState.update { s -> val n = s.selectedImageUris.toMutableSet(); if(n.contains(uri)) n.remove(uri) else n.add(uri); s.copy(selectedImageUris = n) } }
    fun setExternalSelection(uris: List<String>) { _uiState.update { it.copy(selectedImageUris = uris.toSet()) } }
    fun openSelectionSheet(uris: List<String>) { _uiState.update { it.copy(isSelectionSheetOpen = true, selectionSheetUris = uris) } }
    fun confirmSelection(newSelection: Set<String>) { _uiState.update { it.copy(isSelectionSheetOpen = false, selectedImageUris = newSelection, selectionSheetUris = emptyList()) } }
    fun closeSelectionSheet() { _uiState.update { it.copy(isSelectionSheetOpen = false, selectionSheetUris = emptyList()) } }

    fun sendUserInput(input: String) {
        val currentState = _uiState.value
        if (currentState.currentStatus !is AgentStatus.Idle) return
        val selectedUris = currentState.selectedImageUris.toList()
        _uiState.update { it.copy(selectedImageUris = emptySet()) }

        viewModelScope.launch {
            addMessage(ChatMessage(text = input, sender = Sender.USER, imageUris = selectedUris.ifEmpty { null }))
            setStatus(AgentStatus.Loading("Thinking..."))
            var base64Images: List<String>? = null
            if (selectedUris.isNotEmpty()) {
                setStatus(AgentStatus.Loading("Reading images..."))
                try {
                    val images = ImageHelper.encodeImages(application, selectedUris)
                    if (images.isNotEmpty()) base64Images = images
                } catch (e: Exception) { Log.e(TAG, "Encode failed", e) }
                setStatus(AgentStatus.Loading("Thinking..."))
            }
            val request = AgentRequest(sessionId = currentSessionId, userInput = input, toolResult = null, selectedUris = selectedUris.ifEmpty { null }, base64Images = base64Images)
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
                        try { urisToDelete.forEach { uri -> imageEmbeddingDao.deleteByUri(uri) } } catch (e: Exception) {}
                    }
                    _uiState.update { it.copy(selectedImageUris = it.selectedImageUris - urisToDelete.toSet(), selectionSheetUris = it.selectionSheetUris - urisToDelete.toSet()) }
                    sendToolResult(gson.toJson(true), toolCallId)
                    _galleryDidChange.emit(Unit)
                }
                PermissionType.WRITE -> {
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
                    addMessage(ChatMessage(text = message, sender = Sender.AGENT, suggestions = response.suggestedActions))
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
                    if (toolResultObject != null) sendToolResult(gson.toJson(toolResultObject), action.id)
                }
            }
        } catch (e: Exception) {
            addMessage(ChatMessage(text = e.message ?: "Unknown network error", sender= Sender.ERROR))
            setStatus(AgentStatus.Idle)
        }
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

    // --------------------------------------------------------------------------
    // UPDATED TOOL EXECUTION LOGIC
    // --------------------------------------------------------------------------
    private suspend fun executeLocalTool(toolCall: ToolCall): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            (toolCall.name == "delete_photos" || toolCall.name == "move_photos_to_album")) {
            return mapOf("error" to "Modify/Delete operations require Android 11+")
        }

        val result: Any? = try {
            when (toolCall.name) {
                "search_photos" -> {
                    val query = toolCall.args["query"] as? String ?: ""
                    val startDateStr = toolCall.args["start_date"] as? String
                    val endDateStr = toolCall.args["end_date"] as? String
                    val location = toolCall.args["location"] as? String // <--- NEW

                    var dateFilterUris: Set<String>? = null

                    // 1. Apply Date Filter
                    if (startDateStr != null && endDateStr != null) {
                        try {
                            val startMillis = LocalDate.parse(startDateStr).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            val endMillis = LocalDate.parse(endDateStr).atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            val urisList = galleryTools.getPhotosInDateRange(startMillis, endMillis)
                            dateFilterUris = urisList.toSet()
                        } catch (e: Exception) { Log.e(TAG, "Date error", e) }
                    }

                    val foundUris = withContext(Dispatchers.IO) {
                        val allImageEmbeddings = imageEmbeddingDao.getAllEmbeddings()

                        // 2. Apply Filters (Date & Location)
                        var candidates = allImageEmbeddings

                        if (dateFilterUris != null) {
                            candidates = candidates.filter { dateFilterUris.contains(it.uri) }
                        }

                        // --- LOCATION FILTER ---
                        if (!location.isNullOrBlank()) {
                            candidates = candidates.filter {
                                it.location?.contains(location, ignoreCase = true) == true
                            }
                        }
                        // -----------------------

                        // 3. Semantic Search (if query exists)
                        if (query.isNotBlank()) {
                            val tokens = clipTokenizer.tokenize(query)
                            val textEmbedding = textEncoder.encode(tokens)

                            candidates.mapNotNull {
                                val sim = SimilarityUtil.cosineSimilarity(textEmbedding, it.embedding)
                                if (sim > 0.2f) SearchResult(it.uri, sim) else null
                            }.sortedByDescending { it.similarity }.map { it.uri }
                        } else {
                            // If no query, just return the filtered list (up to 100 to prevent overload)
                            candidates.take(100).map { it.uri }
                        }
                    }

                    if (foundUris.isEmpty()) {
                        addMessage(ChatMessage(text = "I couldn't find any photos matching those criteria.", sender= Sender.AGENT))
                    } else {
                        addMessage(ChatMessage(text = "Found ${foundUris.size} photos.", sender = Sender.AGENT, imageUris = foundUris, hasSelectionPrompt = true))
                    }
                    mapOf("photos_found" to foundUris.size)
                }
                // ... (Other cases match your original file) ...
                "scan_for_cleanup" -> {
                    val duplicates = cleanupManager.findDuplicates()
                    if (duplicates.isEmpty()) {
                        addMessage(ChatMessage(text = "No duplicates found.", sender = Sender.AGENT))
                        mapOf("result" to "No duplicates found")
                    } else {
                        _uiState.update { it.copy(cleanupGroups = duplicates) }
                        addMessage(ChatMessage(text = "Found duplicates. Tap to review.", sender = Sender.AGENT, isCleanupPrompt = true))
                        mapOf("found_sets" to duplicates.size)
                    }
                }
                "delete_photos" -> {
                    val uris = getUrisFromArgsOrSelection(toolCall.args["photo_uris"])
                    val intentSender = galleryTools.createDeleteIntentSender(uris)
                    if (intentSender != null) {
                        pendingToolCallId = toolCall.id
                        pendingToolArgs = mapOf("photo_uris" to uris)
                        setStatus(AgentStatus.RequiresPermission(intentSender, PermissionType.DELETE, "Waiting for permission..."))
                        null
                    } else mapOf("error" to "Failed")
                }
                "move_photos_to_album" -> {
                    val uris = getUrisFromArgsOrSelection(toolCall.args["photo_uris"])
                    val intentSender = galleryTools.createWriteIntentSender(uris)
                    if (intentSender != null) {
                        pendingToolCallId = toolCall.id
                        pendingToolArgs = mapOf("photo_uris" to uris, "album_name" to (toolCall.args["album_name"] ?: "New Album"))
                        setStatus(AgentStatus.RequiresPermission(intentSender, PermissionType.WRITE, "Waiting for permission..."))
                        null
                    } else mapOf("error" to "Failed")
                }
                "create_collage" -> {
                    val uris = getUrisFromArgsOrSelection(toolCall.args["photo_uris"])
                    val title = toolCall.args["title"] as? String ?: "My Collage"
                    val newCollageUri = galleryTools.createCollage(uris, title)
                    val message = "I've created the collage '$title'."
                    val imageList = if (newCollageUri != null) listOf(newCollageUri) else null
                    addMessage(ChatMessage(text = message, sender= Sender.AGENT, imageUris = imageList, hasSelectionPrompt = true))
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
}