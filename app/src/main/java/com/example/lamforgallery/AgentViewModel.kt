package com.example.lamforgallery

import android.app.Application
import android.content.IntentSender
import android.os.Build
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

// Define a type for our pending permissions
enum class PermissionType { DELETE, WRITE }

sealed class AgentUiState {
    object Idle : AgentUiState()
    data class Loading(val message: String) : AgentUiState()
    data class AgentMessage(val message: String) : AgentUiState()
    data class Error(val error: String) : AgentUiState()

    /**
     * Refactored state: Now holds the IntentSender AND the type of
     * permission we are requesting.
     */
    data class RequiresPermission(
        val intentSender: IntentSender,
        val type: PermissionType,
        val message: String
    ) : AgentUiState()
}

class AgentViewModel(
    private val apiService: AgentApiService,
    private val galleryTools: GalleryTools,
    private val gson: Gson
) : ViewModel() {

    private val TAG = "AgentViewModel"
    private var currentSessionId: String? = null

    // Store the full context of a pending tool call
    private var pendingToolCallId: String? = null
    private var pendingToolArgs: Map<String, Any>? = null

    private val _uiState = MutableStateFlow<AgentUiState>(AgentUiState.Idle)
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

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
     * Renamed and generalized permission result handler.
     * This is the callback from the Activity.
     */
    fun onPermissionResult(wasSuccessful: Boolean, type: PermissionType) {
        val toolCallId = pendingToolCallId
        if (toolCallId == null) {
            Log.e(TAG, "onPermissionResult called but no pendingToolCallId")
            return
        }

        // Clear the pending call *after* use
        val args = pendingToolArgs

        pendingToolCallId = null
        pendingToolArgs = null

        viewModelScope.launch {
            if (!wasSuccessful) {
                Log.w(TAG, "User denied permission for $type")
                sendToolResult(gson.toJson(false), toolCallId)
                return@launch
            }

            // User granted permission. Now we do the *second step* if needed.
            Log.d(TAG, "User granted permission for $type")
            when (type) {
                PermissionType.DELETE -> {
                    // Delete was one-step. Just send the success.
                    sendToolResult(gson.toJson(true), toolCallId)
                }
                PermissionType.WRITE -> {
                    // Write is two-step. We must now perform the move.
                    if (args == null) {
                        Log.e(TAG, "Write permission granted but no pending args!")
                        sendToolResult(gson.toJson(false), toolCallId)
                        return@launch
                    }

                    _uiState.value = AgentUiState.Loading("Moving files...")

                    // Extract the arguments we saved
                    val uris = args["photo_uris"] as? List<String> ?: emptyList()
                    val album = args["album_name"] as? String ?: "New Album"

                    // Call the *second* GalleryTools function
                    val moveResult = galleryTools.performMoveOperation(uris, album)

                    // Send the *final* result of the move back to the agent
                    sendToolResult(gson.toJson(moveResult), toolCallId)
                }
            }
        }
    }

    private suspend fun handleAgentRequest(request: AgentRequest) {
        try {
            val response = apiService.invokeAgent(request)
            Log.d(TAG, "Received response: $response")
            currentSessionId = response.sessionId

            when (response.status) {
                "complete" -> {
                    val message = response.agentMessage ?: "Done."
                    _uiState.value = AgentUiState.AgentMessage(message)
                }
                "requires_action" -> {
                    val action = response.nextActions?.firstOrNull()
                    if (action == null) {
                        Log.e(TAG, "Agent required action but sent none.")
                        sendToolResult("ERROR: Agent asked for action but sent none.", "error-id")
                        return
                    }

                    _uiState.value = AgentUiState.Loading("Working on it: ${action.name}...")
                    val toolResultJson = executeLocalTool(action)

                    if (toolResultJson != null) {
                        // Tool executed synchronously (like search)
                        sendToolResult(toolResultJson, action.id)
                    } else {
                        // Tool (delete/move) has paused for permission.
                        Log.d(TAG, "Loop paused, waiting for permission result.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in agent loop", e)
            _uiState.value = AgentUiState.Error(e.message ?: "Unknown network error")
        }
    }

    private suspend fun executeLocalTool(toolCall: ToolCall): String? {
        Log.d(TAG, "Executing local tool: ${toolCall.name} with args: ${toolCall.args}")

        // This is for API 30 (Android 11) and above.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Modify operations only supported on Android 11+")
            return gson.toJson(mapOf("error" to "Modify/Delete operations require Android 11+"))
        }

        val result: Any? = try {
            when (toolCall.name) {
                "search_photos" -> {
                    val query = toolCall.args["query"] as? String ?: ""
                    galleryTools.searchPhotos(query)
                }
                "delete_photos" -> {
                    val uris = toolCall.args["photo_uris"] as? List<String> ?: emptyList()
                    val intentSender = galleryTools.createDeleteIntentSender(uris)

                    if (intentSender != null) {
                        pendingToolCallId = toolCall.id
                        pendingToolArgs = null // Delete has no second step
                        _uiState.value = AgentUiState.RequiresPermission(
                            intentSender,
                            PermissionType.DELETE,
                            "Waiting for user permission to delete..."
                        )
                        null // Pause the loop
                    } else {
                        mapOf("error" to "Could not create delete request.")
                    }
                }
                "move_photos_to_album" -> {
                    val uris = toolCall.args["photo_uris"] as? List<String> ?: emptyList()
                    val intentSender = galleryTools.createWriteIntentSender(uris)

                    if (intentSender != null) {
                        pendingToolCallId = toolCall.id
                        pendingToolArgs = toolCall.args // SAVE arguments for step 2
                        _uiState.value = AgentUiState.RequiresPermission(
                            intentSender,
                            PermissionType.WRITE,
                            "Waiting for user permission to move files..."
                        )
                        null // Pause the loop
                    } else {
                        mapOf("error" to "Could not create write/move request.")
                    }
                }
                "create_collage" -> {
                    // ... (stub remains) ...
                    val uris = toolCall.args["photo_uris"] as? List<String> ?: emptyList()
                    val title = toolCall.args["title"] as? String ?: "My Collage"
                    galleryTools.createCollage(uris, title)
                }
                else -> {
                    mapOf("error" to "Tool '${toolCall.name}' is not implemented on this client.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing local tool: ${toolCall.name}", e)
            mapOf("error" to "Failed to execute ${toolCall.name}: ${e.message}")
        }

        return result?.let {
            val jsonResult = gson.toJson(it)
            Log.d(TAG, "Tool ${toolCall.name} result (JSON): $jsonResult")
            jsonResult
        }
    }

    private fun sendToolResult(resultJsonString: String, toolCallId: String) {
        viewModelScope.launch {
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
            handleAgentRequest(request)
        }
    }
}

// Factory is unchanged
class AgentViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AgentViewModel::class.java)) {
            val apiService = NetworkModule.apiService
            val galleryTools = GalleryTools(application.applicationContext)
            val gson = NetworkModule.gson

            @Suppress("UNCHECKED_CAST")
            return AgentViewModel(apiService, galleryTools, gson) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}