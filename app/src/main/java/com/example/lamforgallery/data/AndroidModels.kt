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
    @SerializedName("content") val content: String // Must be a JSON string of the result
)

// --- 2. RESPONSE Body Received from Backend ---

data class AgentResponse(
    @SerializedName("sessionId") val sessionId: String,
    @SerializedName("status") val status: String, // "requires_action" or "complete"
    @SerializedName("agentMessage") val agentMessage: String?,
    @SerializedName("nextActions") val nextActions: List<ToolCall>?
)

data class ToolCall(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("args") val args: Map<String, Any> // Gson handles this generic map
)