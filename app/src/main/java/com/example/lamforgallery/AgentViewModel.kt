package com.example.lamforgallery

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
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val imageUris: List<String>? = null
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
    val selectedImageUris: Set<String> = emptySet() // <-- Holds the user's selection
)

enum class PermissionType { DELETE, WRITE }

// --- END STATE ---


class AgentViewModel(
    application: Application,
    private val agentApi: AgentApiService,
    private val galleryTools: GalleryTools,
    private val gson: Gson
) : ViewModel() {

    private val TAG = "AgentViewModel"
    private var currentSessionId: String = UUID.randomUUID().toString()

    // Used to pause the loop for permission
    private var pendingToolCallId: String? = null
    private var pendingToolArgs: Map<String, Any>? = null

    // The ViewModel now holds our new, combined UI state
    private val _uiState = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    /**
     * Called by the UI when a user taps an image.
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
            // 1. Add the user's message to the chat list
            addMessage(ChatMessage(text = input, sender = Sender.USER))

            // 2. Set the status to Loading
            setStatus(AgentStatus.Loading("Thinking..."))

            // 3. Send to agent (WITH the selected URIs)
            val request = AgentRequest(
                sessionId = currentSessionId,
                userInput = input,
                toolResult = null,
                selectedUris = selectedUris.ifEmpty { null } // Send null if empty
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

        // Clear the pending state
        pendingToolCallId = null
        pendingToolArgs = null

        viewModelScope.launch {
            if (!wasSuccessful) {
                Log.w(TAG, "User denied permission for $type")
                addMessage(ChatMessage(text = "User denied permission.", sender = Sender.ERROR))
                sendToolResult(gson.toJson(false), toolCallId)
                return@launch
            }

            // User granted permission
            when (type) {
                PermissionType.DELETE -> {
                    // Permission was for delete, result is just "true"
                    sendToolResult(gson.toJson(true), toolCallId)
                }
                PermissionType.WRITE -> {
                    // Permission was for write, now we must execute the *second* part
                    if (args == null) {
                        Log.e(TAG, "Write permission granted but no pending args!")
                        addMessage(ChatMessage(text = "Error: Missing context for move operation.", sender = Sender.ERROR))
                        sendToolResult(gson.toJson(false), toolCallId)
                        return@launch
                    }

                    setStatus(AgentStatus.Loading("Moving files..."))

                    // We know this is for the move op, extract args
                    val uris = args["photo_uris"] as? List<String> ?: emptyList()
                    val album = args["album_name"] as? String ?: "New Album"

                    val moveResult = galleryTools.performMoveOperation(uris, album)
                    sendToolResult(gson.toJson(moveResult), toolCallId)
                }
            }
        }
    }

    /**
     * The main agent loop logic.
     */
    private suspend fun handleAgentRequest(request: AgentRequest) {
        try {
            val response = agentApi.invokeAgent(request)
            currentSessionId = response.sessionId

            when (response.status) {
                "complete" -> {
                    val message = response.agentMessage ?: "Done."
                    // Add the agent's final message and set status to Idle
                    addMessage(ChatMessage(text = message, sender = Sender.AGENT))
                    setStatus(AgentStatus.Idle)
                }
                "requires_action" -> {
                    val action = response.nextActions?.firstOrNull()
                    if (action == null) {
                        Log.e(TAG, "Agent required action but sent none.")
                        addMessage(ChatMessage(text = "Agent error: No action provided.", sender = Sender.ERROR))
                        setStatus(AgentStatus.Idle) // Stop the loop
                        return
                    }

                    // Set status to "Loading" with the tool name
                    setStatus(AgentStatus.Loading("Working on it: ${action.name}..."))

                    val toolResultObject = executeLocalTool(action)

                    // executeLocalTool returns null if it pauses for permission
                    if (toolResultObject != null) {
                        // We got a direct result, send it back
                        val resultJson = gson.toJson(toolResultObject)
                        sendToolResult(resultJson, action.id)
                    } else {
                        // Loop is paused, waiting for permission result.
                        // Status was already set inside executeLocalTool.
                        Log.d(TAG, "Loop paused, waiting for permission result.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in agent loop", e)
            addMessage(ChatMessage(text = e.message ?: "Unknown network error", sender = Sender.ERROR))
            setStatus(AgentStatus.Idle)
        }
    }

    /**
     * This is the "nervous system." It calls the correct Kotlin function
     * based on the agent's tool request.
     */
    private suspend fun executeLocalTool(toolCall: ToolCall): Any? {
        // Simple check for Android version
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            (toolCall.name == "delete_photos" || toolCall.name == "move_photos_to_album")) {
            Log.w(TAG, "Skipping ${toolCall.name}, requires Android 11+")
            return mapOf("error" to "Modify/Delete operations require Android 11+")
        }

        val result: Any? = try {
            when (toolCall.name) {
                "search_photos" -> {
                    val query = toolCall.args["query"] as? String ?: ""
                    val uris = galleryTools.searchPhotos(query)

                    // Add an agent message *with* the image URIs
                    val message = "I found ${uris.size} photos for you."
                    addMessage(ChatMessage(text = message, sender = Sender.AGENT, imageUris = uris))

                    uris // This is the return value
                }
                "delete_photos" -> {
                    // Use helper to get URIs from args OR user selection
                    val uris = getUrisFromArgsOrSelection(toolCall.args["photo_uris"])
                    val intentSender = galleryTools.createDeleteIntentSender(uris)

                    if (intentSender != null) {
                        pendingToolCallId = toolCall.id
                        pendingToolArgs = null // Delete has no second step

                        setStatus(AgentStatus.RequiresPermission(
                            intentSender, PermissionType.DELETE, "Waiting for user permission to delete..."
                        ))
                        null // Return null to pause the loop
                    } else {
                        mapOf("error" to "Could not create delete request.")
                    }
                }
                "move_photos_to_album" -> {
                    // Use helper to get URIs from args OR user selection
                    val uris = getUrisFromArgsOrSelection(toolCall.args["photo_uris"])
                    val intentSender = galleryTools.createWriteIntentSender(uris)

                    if (intentSender != null) {
                        // Pass *all* args (including uris and album_name)
                        pendingToolCallId = toolCall.id
                        pendingToolArgs = toolCall.args

                        setStatus(AgentStatus.RequiresPermission(
                            intentSender, PermissionType.WRITE, "Waiting for user permission to move files..."
                        ))
                        null // Return null to pause the loop
                    } else {
                        mapOf("error" to "Could not create write/move request.")
                    }
                }
                "create_collage" -> {
                    // Use helper to get URIs from args OR user selection
                    val uris = getUrisFromArgsOrSelection(toolCall.args["photo_uris"])
                    val title = toolCall.args["title"] as? String ?: "My Collage"
                    val newCollageUri = galleryTools.createCollage(uris, title)

                    val message = "I've created the collage '$title' for you."
                    val imageList = if (newCollageUri != null) listOf(newCollageUri) else null
                    addMessage(ChatMessage(text = message, sender = Sender.AGENT, imageUris = imageList))

                    newCollageUri
                }

                // --- NEW CASE ADDED ---
                "apply_filter" -> {
                    // Use helper to get URIs from args OR user selection
                    val uris = getUrisFromArgsOrSelection(toolCall.args["photo_uris"])
                    val filterName = toolCall.args["filter_name"] as? String ?: "grayscale"

                    val newImageUris = galleryTools.applyFilter(uris, filterName)

                    // Add a chat message showing the NEWLY created images
                    val message = "I've applied the '$filterName' filter to ${newImageUris.size} photo(s)."
                    addMessage(ChatMessage(
                        text = message,
                        sender = Sender.AGENT,
                        imageUris = newImageUris // Show the new images
                    ))

                    newImageUris // Return the new URIs to the agent
                }
                // --- END NEW CASE ---

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

    /**
     * Helper to get URIs either from the tool args or the user's selection.
     */
    private fun getUrisFromArgsOrSelection(argUris: Any?): List<String> {
        val selectedUris = _uiState.value.selectedImageUris

        // 1. Prioritize user's explicit selection
        if (selectedUris.isNotEmpty()) {
            return selectedUris.toList()
        }

        // 2. Fall back to the agent's provided arguments
        return (argUris as? List<String>) ?: emptyList()
    }

    /**
     * Sends the tool result back to the agent.
     */
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
                selectedUris = null // Not needed when sending a tool result
            )

            Log.d(TAG, "Sending tool result: $resultJsonString")
            handleAgentRequest(request)
        }
    }

    // Helper to add a message to the UI state
    private fun addMessage(message: ChatMessage) {
        _uiState.update { currentState ->
            currentState.copy(messages = currentState.messages + message)
        }
    }

    // Helper to update the agent's status
    private fun setStatus(newStatus: AgentStatus) {
        _uiState.update { it.copy(currentStatus = newStatus) }
    }
}


/**
 * ViewModel Factory
 * This is a simple factory to create the AgentViewModel with its dependencies.
 */
class AgentViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {

    // Create a singleton instance of Gson
    private val gson: Gson by lazy {
        NetworkModule.gson // Use the Gson from NetworkModule
    }

    // Create a singleton instance of GalleryTools
    private val galleryTools: GalleryTools by lazy {
        GalleryTools(application.contentResolver) // Pass Application context
    }

    // Create a singleton instance of AgentApiService
    private val agentApi: AgentApiService by lazy {
        NetworkModule.apiService // Use the apiService from NetworkModule
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AgentViewModel::class.java)) {
            return AgentViewModel(application, agentApi, galleryTools, gson) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}