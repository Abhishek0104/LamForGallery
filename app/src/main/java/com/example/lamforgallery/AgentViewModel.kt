package com.example.lamforgallery
import android.app.Application
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
}

class AgentViewModel(
    private val apiService: AgentApiService,
    private val galleryTools: GalleryTools,
    private val gson: Gson
) : ViewModel() {

    private val TAG = "AgentViewModel"
    private var currentSessionId: String? = null

    // Backing property for the UI state
    private val _uiState = MutableStateFlow<AgentUiState>(AgentUiState.Idle)
    // Public, read-only state flow for the UI to observe
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    /**
     * Public entry point for the user to make a request.
     */
    fun sendUserInput(input: String) {
        if (input.isBlank()) return

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

                    // For now, execute the first action.
                    // A real app might handle multiple.
                    val action = actions.first()
                    _uiState.value = AgentUiState.Loading("Working on it: ${action.name}...")

                    val toolResultJson = executeLocalTool(action)

                    // Send the result back to the agent to continue the loop
                    sendToolResult(toolResultJson, action.id)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in agent loop", e)
            _uiState.value = AgentUiState.Error(e.message ?: "Unknown network error")
        }
    }

    /**
     * This function maps the agent's tool name to your *actual* Kotlin code.
     */
    private suspend fun executeLocalTool(toolCall: ToolCall): String {
        Log.d(TAG, "Executing local tool: ${toolCall.name} with args: ${toolCall.args}")
        val result: Any = try {
            when (toolCall.name) {
                "search_photos" -> {
                    val query = toolCall.args["query"] as? String ?: ""
                    galleryTools.searchPhotos(query) // Returns List<String>
                }
                "delete_photos" -> {
                    val uris = (toolCall.args["photo_uris"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                    galleryTools.deletePhotos(uris) // Returns Boolean
                }
                "create_collage" -> {
                    val uris = (toolCall.args["photo_uris"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                    val title = toolCall.args["title"] as? String ?: "My Collage"
                    galleryTools.createCollage(uris, title) // Returns new collage URI (String)
                }
                "move_photos_to_album" -> {
                    val uris = (toolCall.args["photo_uris"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
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

        // Convert the Kotlin result (List, Boolean, String, Map) into a JSON string
        val jsonResult = gson.toJson(result)
        Log.d(TAG, "Tool ${toolCall.name} result (JSON): $jsonResult")
        return jsonResult
    }

    /**
     * Helper function to send the result of a tool back to the agent.
     */
    private fun sendToolResult(resultJsonString: String, toolCallId: String) {
        viewModelScope.launch {
            // _uiState.value is already "Loading..."
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