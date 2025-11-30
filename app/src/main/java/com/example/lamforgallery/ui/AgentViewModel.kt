package com.example.lamforgallery.ui

import android.app.Application
import android.content.IntentSender
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.lamforgallery.agent.AgentFactory
import com.example.lamforgallery.data.Suggestion
import com.example.lamforgallery.tools.GalleryTools
import com.example.lamforgallery.tools.GalleryToolSet
// import com.example.lamforgallery.tools.UserTools
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.asTools
import com.example.lamforgallery.database.ImageEmbeddingDao
import com.example.lamforgallery.ml.ClipTokenizer
import com.example.lamforgallery.ml.TextEncoder
import com.example.lamforgallery.utils.SimilarityUtil
import com.example.lamforgallery.utils.CleanupManager
import com.example.lamforgallery.utils.ImageHelper
import com.example.lamforgallery.database.PersonDao
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
import kotlinx.coroutines.suspendCancellableCoroutine

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
    val cleanupGroups: List<CleanupManager.DuplicateGroup> = emptyList(),
    val isWaitingForUserInput: Boolean = false
)

enum class PermissionType { DELETE, WRITE }

class AgentViewModel(
    private val application: Application,
    private val galleryTools: GalleryTools,
    private val gson: Gson,
    private val imageEmbeddingDao: ImageEmbeddingDao,
    private val personDao: PersonDao,
    private val clipTokenizer: ClipTokenizer,
    private val textEncoder: TextEncoder,
    private val cleanupManager: CleanupManager
) : ViewModel() {

    private val TAG = "AgentViewModel"
    private var pendingToolCallId: String? = null
    private var pendingToolArgs: Map<String, Any>? = null
    private val _uiState = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()
    private val _galleryDidChange = MutableSharedFlow<Unit>()
    val galleryDidChange: SharedFlow<Unit> = _galleryDidChange.asSharedFlow()

    // --- STATE CACHE ---
    private var lastSearchResults: List<String> = emptyList()
    // --- NEW: Cache for manual selection to persist context after UI clears ---
    private var lastManualSelection: List<String> = emptyList()
    
    // --- AGENT-USER COMMUNICATION STATE ---
    private val _waitingForUserInput = MutableStateFlow(false)
    private var userInputContinuation: ((String) -> Unit)? = null
    
    init {
        // Initialize with a simple greeting message
        viewModelScope.launch {
            // Log.d(TAG, "🚀 Initializing gallery assistant")
            // addMessage(ChatMessage(
            //     text = "Hello! I'm your gallery assistant. How can I help you today?",
            //     sender = Sender.AGENT
            // ))
            setStatus(AgentStatus.Idle)
        }
    }
    suspend fun processMessage(message: String): String {
        Log.d(TAG, "Agent says: $message")
        
        // Show the agent's message in the chat
        withContext(Dispatchers.Main) {
            addMessage(ChatMessage(
                text = message,
                sender = Sender.AGENT
            ))
            // Set status to Idle and mark as waiting for input so UI enables the input field
            setStatus(AgentStatus.Idle)
            _uiState.update { it.copy(isWaitingForUserInput = true) }
        }
        
        // Wait for user input
        return suspendCancellableCoroutine { continuation ->
            userInputContinuation = { userResponse ->
                Log.d(TAG, "User responded: $userResponse")
                continuation.resume(userResponse) {}
            }
            _waitingForUserInput.value = true
        }
    }

    // --- AI AGENT (created on demand) ---
    private var agent: AIAgent<String, String>? = null

    fun toggleImageSelection(uri: String) { _uiState.update { s -> val n = s.selectedImageUris.toMutableSet(); if(n.contains(uri)) n.remove(uri) else n.add(uri); s.copy(selectedImageUris = n) } }
    fun setExternalSelection(uris: List<String>) { _uiState.update { it.copy(selectedImageUris = uris.toSet()) } }

    // --- NEW: Explicit Clear Function ---
    fun clearSelection() { _uiState.update { it.copy(selectedImageUris = emptySet()) } }

    fun openSelectionSheet(uris: List<String>) { _uiState.update { it.copy(isSelectionSheetOpen = true, selectionSheetUris = uris) } }
    fun confirmSelection(newSelection: Set<String>) { _uiState.update { it.copy(isSelectionSheetOpen = false, selectedImageUris = newSelection, selectionSheetUris = emptyList()) } }
    fun closeSelectionSheet() { _uiState.update { it.copy(isSelectionSheetOpen = false, selectionSheetUris = emptyList()) } }

    fun sendUserInput(input: String) {
        val currentState = _uiState.value
        
        // 1. Capture the selection
        val selectedUris = currentState.selectedImageUris.toList()

        // 2. Update the "Last Manual Selection" cache so the agent can use it
        if (selectedUris.isNotEmpty()) {
            lastManualSelection = selectedUris
        }

        // 3. Clear the UI immediately (The Fix)
        _uiState.update { it.copy(selectedImageUris = emptySet()) }
        
        // Add user message to chat
        addMessage(ChatMessage(text = input, sender = Sender.USER, imageUris = selectedUris.ifEmpty { null }))
        
        // Check if we're responding to an agent question or starting a new conversation
        if (_waitingForUserInput.value) {
            // Resume the agent with the user's response
            _waitingForUserInput.value = false
            _uiState.update { it.copy(isWaitingForUserInput = false) }
            setStatus(AgentStatus.Loading("Thinking..."))
            userInputContinuation?.invoke("{\"selected_photos_uris\": ${selectedUris}, \"user_input\": \"$input\"}")
            userInputContinuation = null
        } else {
            // Start a new agent conversation
            if (currentState.currentStatus !is AgentStatus.Idle) return
            
            viewModelScope.launch {
                setStatus(AgentStatus.Loading("Thinking..."))

                try {
                    // Create agent if it doesn't exist
                    if (agent == null) {
                        val toolRegistry = ToolRegistry {
                            tools(createGalleryToolSet().asTools())
                        }
                        
                        agent = AgentFactory.createGalleryAgent(toolRegistry, ::processMessage)
                    }
                    
                    Log.d(TAG, "🚀 AGENT EXECUTION START with user query: $input")
                    val response = agent!!.run("{\"selected_photos_uris\": ${selectedUris}, \"user_input\": \"$input\"}")
                    Log.d(TAG, "✅ AGENT EXECUTION COMPLETE: $response")
                    
                    addMessage(ChatMessage(
                        text = response ?: "No response from agent.",
                        sender = Sender.AGENT
                    ))
                } catch (e: Exception) {
                    Log.e(TAG, "Error in agent execution", e)
                    addMessage(ChatMessage(text = "Error: ${e.message}", sender = Sender.ERROR))
                } finally {
                    setStatus(AgentStatus.Idle)
                }
            }
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
                setStatus(AgentStatus.Idle)
                return@launch
            }
            when (type) {
                PermissionType.DELETE -> {
                    val urisToDelete = args?.get("photo_uris") as? List<String> ?: emptyList()
                    withContext(Dispatchers.IO) {
                        try { urisToDelete.forEach { uri -> imageEmbeddingDao.deleteByUri(uri) } } catch (e: Exception) {}
                    }
                    _uiState.update {
                        it.copy(
                            selectedImageUris = it.selectedImageUris - urisToDelete.toSet(),
                            selectionSheetUris = it.selectionSheetUris - urisToDelete.toSet()
                        )
                    }
                    addMessage(ChatMessage(text = "Successfully deleted ${urisToDelete.size} photos.", sender = Sender.AGENT))
                    _galleryDidChange.emit(Unit)
                }
                PermissionType.WRITE -> {
                    val uris = args?.get("photo_uris") as? List<String> ?: emptyList()
                    val album = args?.get("album_name") as? String ?: "New Album"
                    val moveResult = galleryTools.performMoveOperation(uris, album)
                    if (moveResult) {
                        addMessage(ChatMessage(text = "Successfully moved ${uris.size} photos to $album.", sender = Sender.AGENT))
                    } else {
                        addMessage(ChatMessage(text = "Failed to move photos.", sender = Sender.ERROR))
                    }
                    _galleryDidChange.emit(Unit)
                }
            }
            _uiState.update { it.copy(selectedImageUris = emptySet()) }
            setStatus(AgentStatus.Idle)
        }
    }

    private fun addMessage(message: ChatMessage) {
        _uiState.update { currentState -> currentState.copy(messages = currentState.messages + message) }
    }

    private fun setStatus(newStatus: AgentStatus) {
        _uiState.update { it.copy(currentStatus = newStatus) }
    }

    // --- HELPER: Create GalleryToolSet with callbacks ---
    private fun createGalleryToolSet(): GalleryToolSet {
        return GalleryToolSet(
            context = application,
            galleryTools = galleryTools,
            imageEmbeddingDao = imageEmbeddingDao,
            personDao = personDao,
            clipTokenizer = clipTokenizer,
            textEncoder = textEncoder,
            cleanupManager = cleanupManager,
            onSearchResults = { uris -> 
                lastSearchResults = uris 
            },
            getLastSearchResults = { lastSearchResults },
            getLastManualSelection = { lastManualSelection },
            onPermissionRequired = { intentSender, type, message, args ->
                pendingToolCallId = UUID.randomUUID().toString()
                pendingToolArgs = args
                setStatus(AgentStatus.RequiresPermission(intentSender, type, message))
            },
            onMessage = { text, imageUris, hasSelectionPrompt, isCleanupPrompt ->
                addMessage(ChatMessage(
                    text = text,
                    sender = Sender.AGENT,
                    imageUris = imageUris,
                    hasSelectionPrompt = hasSelectionPrompt,
                    isCleanupPrompt = isCleanupPrompt
                ))
            },
            onCleanupGroups = { groups ->
                _uiState.update { it.copy(cleanupGroups = groups) }
            },
            onGalleryChanged = {
                _galleryDidChange.emit(Unit)
            }
        )
    }

    fun clearChat() {
        lastSearchResults = emptyList()
        lastManualSelection = emptyList()
        agent = null // Reset the agent for fresh conversation
        _waitingForUserInput.value = false // Reset waiting state
        userInputContinuation = null // Clear any pending continuation
        _uiState.update {
            it.copy(
                messages = emptyList(),
                currentStatus = AgentStatus.Idle,
                selectedImageUris = emptySet(),
                cleanupGroups = emptyList(),
                isWaitingForUserInput = false // Reset UI waiting state
            )
        }
    }
}