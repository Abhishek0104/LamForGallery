package com.example.lamforgallery

import android.app.Application
import android.content.IntentSender
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.lamforgallery.data.AgentRequest
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

// Define the states our UI can be in
sealed class AgentUiState {
    object Idle : AgentUiState()
    data class Loading(val message: String) : AgentUiState()
    data class AgentMessage(val message: String) : AgentUiState()
    data class Error(val error: String) : AgentUiState()

    /**
     * A new state to tell the Activity to launch a permission request.
     * This state is "sticky" and will not be cleared by the ViewModel
     * until the Activity calls `onDeletePermissionResult`.
     */
    data class RequiresPermission(val intentSender: IntentSender) : AgentUiState()
}

class AgentViewModel(
    private val apiService: AgentApiService,
    private val galleryTools: GalleryTools,
    private val gson: Gson
) : ViewModel() {

    private val TAG = "AgentViewModel"
    private var currentSessionId: String? = null

    /**
     * We need to store the ID of the tool call that is
     * waiting for permission.
     */
    private var pendingToolCallId: String? = null

    // Backing property for the UI state
    private val _uiState = MutableStateFlow<AgentUiState>(AgentUiState.Idle)
    // Public, read-only state flow for the UI to observe
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    /**
     * Public entry point for the user to make a request.
     */
    fun sendUserInput(input: String) {
        if (input.isBlank() || _uiState.value is AgentUiState.Loading) return

        viewModelScope.launch {
            _uiState.value = AgentUiState.Loading("Thinking...")
            val request = AgentRequest(
                sessionId = currentSessionId,
                userInput = input,
                toolResult = null
            )
            Log.d(TAG, "Sending user input: $input")
            handleAgentRequest(request)
        }
    }

    /**
     * NEW: Public entry point for the Activity to report
     * the result of the delete permission dialog.
     */
    fun onDeletePermissionResult(wasSuccessful: Boolean) {
        val toolCallId = pendingToolCallId
        if (toolCallId == null) {
            Log.e(TAG, "onDeletePermissionResult called but no pendingToolCallId")
            return
        }

        // Clear the pending call ID
        pendingToolCallId = null

        // Convert the boolean result to a JSON string
        val resultJsonString = gson.toJson(wasSuccessful)

        // Resume the agent loop
        sendToolResult(resultJsonString, toolCallId)
    }

    /**
     * The main stateful loop that communicates with the agent.
     */
    private suspend fun handleAgentRequest(request: AgentRequest) {
        try {
            val response = apiService.invokeAgent(request)
            Log.d(TAG, "Received response: $response")
            currentSessionId = response.sessionId // Save session ID

            when (response.status) {
                "complete" -> {
                    val message = response.agentMessage ?: "Done."
                    _uiState.value = AgentUiState.AgentMessage(message)
                }
                "requires_action" -> {
                    val actions = response.nextActions ?: emptyList()
                    if (actions.isEmpty()) {
                        Log.e(TAG, "Agent required action but sent none.")
                        sendToolResult("ERROR: Agent asked for action but sent none.", "error-id")
                        return
                    }

                    val action = actions.first()
                    _uiState.value = AgentUiState.Loading("Working on it: ${action.name}...")

                    /**
                     * CRITICAL CHANGE: executeLocalTool might now return null
                     * if it needs to pause for user permission.
                     */
                    val toolResultJson = executeLocalTool(action)

                    if (toolResultJson != null) {
                        // Tool executed synchronously (like search)
                        // Send the result back to the agent to continue the loop
                        sendToolResult(toolResultJson, action.id)
                    } else {
                        // Tool (delete) has paused for permission.
                        // The UI state is now RequiresPermission.
                        // We do *not* call sendToolResult. We wait.
                        Log.d(TAG, "Loop paused, waiting for permission result.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in agent loop", e)
            _uiState.value = AgentUiState.Error(e.message ?: "Unknown network error")
        }
    }

    /**
     * This function maps the agent's tool name to your *actual* Kotlin code.
     * It now returns a String? (nullable).
     * - A String means the tool finished and here is the result.
     * - `null` means the tool has paused for user interaction.
     */
    private suspend fun executeLocalTool(toolCall: ToolCall): String? {
        Log.d(TAG, "Executing local tool: ${toolCall.name} with args: ${toolCall.args}")
        val result: Any? = try {
            when (toolCall.name) {
                "search_photos" -> {
                    val query = toolCall.args["query"] as? String ?: ""
                    galleryTools.searchPhotos(query) // Returns List<String>
                }
                "delete_photos" -> {
                    val uris = toolCall.args["photo_uris"] as? List<String> ?: emptyList()

                    // Ask GalleryTools to create the request
                    val intentSender = galleryTools.createDeleteRequest(uris)

                    if (intentSender != null) {
                        // SUCCESS: We have a request.
                        // 1. Save the tool ID so we can resume later
                        pendingToolCallId = toolCall.id
                        // 2. Tell the UI to launch the dialog
                        _uiState.value = AgentUiState.RequiresPermission(intentSender)
                        // 3. Return null to pause the loop
                        null
                    } else {
                        // FAILURE: Couldn't create request (e.g., old Android)
                        // Return an error result immediately.
                        mapOf("error" to "Could not create delete request. (Maybe unsupported Android version?)")
                    }
                }
                "create_collage" -> {
                    val uris = toolCall.args["photo_uris"] as? List<String> ?: emptyList()
                    val title = toolCall.args["title"] as? String ?: "My Collage"
                    galleryTools.createCollage(uris, title) // Returns new collage URI (String)
                }
                "move_photos_to_album" -> {
                    val uris = toolCall.args["photo_uris"] as? List<String> ?: emptyList()
                    val album = toolCall.args["album_name"] as? String ?: "New Album"
                    galleryTools.movePhotosToAlbum(uris, album) // Returns Boolean
                }
                else -> {
                    mapOf("error" to "Tool '${toolCall.name}' is not implemented on this client.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing local tool: ${toolCall.name}", e)
            mapOf("error" to "Failed to execute ${toolCall.name}: ${e.message}")
        }

        // If result is null, it means we're waiting for permission.
        // Otherwise, convert the result to JSON and return it.
        return result?.let {
            val jsonResult = gson.toJson(it)
            Log.d(TAG, "Tool ${toolCall.name} result (JSON): $jsonResult")
            jsonResult
        }
    }

    /**
     * Helper function to send the result of a tool back to the agent.
     * This is now also called by `onDeletePermissionResult`.
     */
    private fun sendToolResult(resultJsonString: String, toolCallId: String) {
        viewModelScope.launch {
            // Set state back to loading
            _uiState.value = AgentUiState.Loading("Sending result to agent...")

            val toolResult = ToolResult(
                toolCallId = toolCallId,
                content = resultJsonString
            )
            val request = AgentRequest(
                sessionId = currentSessionId,
                userInput = null,
                toolResult = toolResult
            )
            Log.d(TAG, "Sending tool result for $toolCallId")
            // Continue the loop
            handleAgentRequest(request)
        }
    }
}


/**
 * Simple ViewModel Factory to manually create the AgentViewModel
 * with its dependencies.
 */
class AgentViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AgentViewModel::class.java)) {
            // Manually provide the dependencies
            val apiService = NetworkModule.apiService
            val galleryTools = GalleryTools(application.applicationContext)
            val gson = NetworkModule.gson

            @Suppress("UNCHECKED_CAST")
            return AgentViewModel(apiService, galleryTools, gson) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}