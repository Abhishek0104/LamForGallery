package com.example.lamforgallery.data
import com.google.gson.annotations.SerializedName

// --- 1. REQUEST Body Sent to Backend ---

data class AgentRequest(
    @SerializedName("sessionId") val sessionId: String?,
    @SerializedName("userInput") val userInput: String?,
    @SerializedName("toolResult") val toolResult: ToolResult? = null,
    @SerializedName("selectedUris") val selectedUris: List<String>? = null,
    @SerializedName("base64Images") val base64Images: List<String>? = null
)

data class ToolResult(
    @SerializedName("tool_call_id") val toolCallId: String,
    @SerializedName("content") val content: String
)

// --- 2. RESPONSE Body Received from Backend ---

data class AgentResponse(
    @SerializedName("sessionId") val sessionId: String,
    @SerializedName("status") val status: String,
    @SerializedName("agentMessage") val agentMessage: String?,
    @SerializedName("nextActions") val nextActions: List<ToolCall>?,
    // --- NEW: Suggestions List ---
    @SerializedName("suggestedActions") val suggestedActions: List<Suggestion>? = null
)

// --- NEW: Suggestion Item ---
data class Suggestion(
    @SerializedName("label") val label: String,  // Text shown on button (e.g. "Delete Duplicates")
    @SerializedName("prompt") val prompt: String // Hidden command sent to agent (e.g. "Delete the duplicates you found")
)

data class ToolCall(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("args") val args: Map<String, Any>
)