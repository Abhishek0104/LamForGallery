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

// --- NEW STATE DEFINITIONS ---

/**
 * Represents one message in the chat
 * NEW: Added optional imageUris list
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val sender: Sender,
    val imageUris: List<String>? = null // <-- NEW FIELD
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
    val currentStatus: AgentStatus = AgentStatus.Idle
)

enum class PermissionType { DELETE, WRITE }

// --- END NEW STATE ---


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

    fun sendUserInput(input: String) {
        if (_uiState.value.currentStatus !is AgentStatus.Idle) {
            Log.w(TAG, "Agent is busy, ignoring input.")
            return
        }

        viewModelScope.launch {
            // 1. Add the user's message to the chat list
            addMessage(ChatMessage(text = input, sender = Sender.USER))

            // 2. Set the status to Loading
            setStatus(AgentStatus.Loading("Thinking..."))

            // 3. Send to agent
            val request = AgentRequest(
                sessionId = currentSessionId,
                userInput = input,
                toolResult = null // <-- THE FIX IS HERE
            )
            Log.d(TAG, "Sending user input: $input")
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

        // Clear the pending state
        pendingToolCallId = null
        pendingToolArgs = null

        viewModelScope.launch {
            if (!wasSuccessful) {
                Log.w(TAG, "User denied permission for $type")
                // Use named arguments
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

                    // --- MODIFIED to get an object, not just a string ---
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
     * Executes a tool and returns the raw result object (e.g., List<String>, String, Boolean).
     * Returns null if the tool pauses for permission.
     * * NEW: We also add image URIs to the agent's message *before* sending the result.
     */
    private suspend fun executeLocalTool(toolCall: ToolCall): Any? {
        // Simple check for Android version
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && toolCall.name != "search_photos") {
            Log.w(TAG, "Skipping ${toolCall.name}, requires Android 11+")
            return mapOf("error" to "Modify/Delete operations require Android 11+")
        }

        val result: Any? = try {
            when (toolCall.name) {
                "search_photos" -> {
                    val query = toolCall.args["query"] as? String ?: ""
                    val uris = galleryTools.searchPhotos(query)

                    // --- NEW LOGIC ---
                    // Add an agent message *with* the image URIs
                    val message = "I found ${uris.size} photos for you."
                    addMessage(ChatMessage(text = message, sender = Sender.AGENT, imageUris = uris))
                    // --- END NEW LOGIC ---

                    uris // This is the return value
                }
                "delete_photos" -> {
                    val uris = toolCall.args["photo_uris"] as? List<String> ?: emptyList()
                    val intentSender = galleryTools.createDeleteIntentSender(uris)

                    if (intentSender != null) {
                        pendingToolCallId = toolCall.id
                        pendingToolArgs = null // Delete has no second step

                        // Set the status to RequiresPermission
                        setStatus(AgentStatus.RequiresPermission(
                            intentSender,
                            PermissionType.DELETE,
                            "Waiting for user permission to delete..."
                        ))
                        null // Return null to pause the loop
                    } else {
                        mapOf("error" to "Could not create delete request.")
                    }
                }
                "move_photos_to_album" -> {
                    val uris = toolCall.args["photo_uris"] as? List<String> ?: emptyList()
                    val intentSender = galleryTools.createWriteIntentSender(uris)

                    if (intentSender != null) {
                        pendingToolCallId = toolCall.id
                        pendingToolArgs = toolCall.args // Save args for step 2

                        // Set the status to RequiresPermission
                        setStatus(AgentStatus.RequiresPermission(
                            intentSender,
                            PermissionType.WRITE,
                            "Waiting for user permission to move files..."
                        ))
                        null // Return null to pause the loop
                    } else {
                        mapOf("error" to "Could not create write/move request.")
                    }
                }
                "create_collage" -> {
                    val uris = toolCall.args["photo_uris"] as? List<String> ?: emptyList()
                    val title = toolCall.args["title"] as? String ?: "My Collage"
                    val newCollageUri = galleryTools.createCollage(uris, title)

                    // --- NEW LOGIC ---
                    val message = "I've created the collage '$title' for you."
                    val imageList = if (newCollageUri != null) listOf(newCollageUri) else null
                    addMessage(ChatMessage(text = message, sender = Sender.AGENT, imageUris = imageList))
                    // --- END NEW LOGIC ---

                    newCollageUri // This is the return value
                }
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

    private fun sendToolResult(resultJsonString: String, toolCallId: String) {
        viewModelScope.launch {
            setStatus(AgentStatus.Loading("Sending result to agent..."))

            val toolResult = ToolResult(
                toolCallId = toolCallId,
                content = resultJsonString
            )

            val request = AgentRequest(
                sessionId = currentSessionId,
                userInput = null, // Not user input
                toolResult = toolResult
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
        Gson()
    }

    // Create a singleton instance of GalleryTools
    private val galleryTools: GalleryTools by lazy {
        GalleryTools(application)
    }

    // Create a singleton instance of AgentApiService
    private val agentApi: AgentApiService by lazy {
        NetworkModule.apiService
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AgentViewModel::class.java)) {
            return AgentViewModel(application, agentApi, galleryTools, gson) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}