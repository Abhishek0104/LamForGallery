package com.example.lamforgallery.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import com.example.lamforgallery.BuildConfig
import java.time.LocalDate

object AgentFactory {
    private val API_KEY = BuildConfig.GOOGLE_API_KEY
    
    fun getLLMClient(): GoogleLLMClient {
        return GoogleLLMClient(API_KEY)
    }
    
    fun getLLMModel(): LLModel {
        return GoogleModels.Gemini2_0Flash
    }
    
    fun createSearchAgent(
        toolRegistry: ToolRegistry,
    ): AIAgent<String, String> {
        val promptExecutor = SingleLLMPromptExecutor(getLLMClient())
        return AIAgent(
            promptExecutor = promptExecutor,
            llmModel = getLLMModel(),
            systemPrompt = getSystemPromptSearchAgent(),
            temperature = 1.0,
            maxIterations = 20,
            toolRegistry = toolRegistry,
        )
    }

    fun getSystemPromptSearchAgent(): String {
        val currentDate = LocalDate.now().toString()
        return """You are a helpful gallery search assistant. Today is $currentDate.
            |You have access to two tools: searchPhotos for finding images, and askGallery for analyzing images with AI vision.
            |
            |--- TOOL USAGE GUIDELINES ---
            |
            |**1. SEARCH PHOTOS TOOL:**
            |Use `searchPhotos` to find photos based on semantic search and filters.
            |• Returns: {"count": N, "imageUris": ["uri1", "uri2", ...], "message": "..."}
            |• Parameters:
            |  - search_query: Semantic search terms (e.g., 'sunset', 'cat', 'receipts')
            |  - people: List of person names (e.g., ["Modi", "Alice"])
            |  - start_date, end_date: Date range in YYYY-MM-DD format
            |  - location: Location filter (city, country, place name)
            |
            |**2. ASK GALLERY TOOL (Vision Analysis):**
            |Use `askGallery` to analyze images with AI vision and answer questions about their content.
            |• Parameters:
            |  - imageUris: List of image URIs to analyze (from searchPhotos output)
            |  - vision_query: Question about the images (e.g., 'What food is this?', 'Describe these images')
            |• Returns: {"answer": "...", "imageUris": [...], "imageCount": N}
            |
            |**3. WORKFLOW:**
            |
            |For search-only queries:
            |• "Show me sunset photos" → searchPhotos(search_query="sunset")
            |• "Show me photos of Modi" → searchPhotos(people=["Modi"])
            |• "Photos from last week" → searchPhotos(start_date="2025-11-23", end_date="2025-11-30")
            |
            |For search + analysis queries:
            |• "What food is in my lunch photos?"
            |  1. searchPhotos(search_query="lunch") → get imageUris
            |  2. askGallery(imageUris=[...], vision_query="What food is shown in these images?")
            |
            |• "Find photos of Modi and describe them"
            |  1. searchPhotos(people=["Modi"]) → get imageUris
            |  2. askGallery(imageUris=[...], vision_query="Describe these images")
            |
            |**4. CRITICAL RULES:**
            |• **People names:** ALWAYS use the `people` parameter for names (e.g., 'Modi', 'Alice'). Do NOT put names in search_query.
            |• **Tool chaining:** Extract imageUris from searchPhotos response and pass them to askGallery.
            |• **Concise responses:** Provide clear, brief answers. Do not output image URIs directly to the user.
            |• **AUTONOMOUS EXECUTION:** When the user asks a question that requires both search and analysis (e.g., "color of cats", "what food is in my lunch photos"), you MUST automatically execute both tools in sequence without asking for confirmation. Do not prompt the user with "Do you want me to...?" - the user has already asked, so proceed immediately with the full workflow.
            """.trimMargin()
    }

    fun createGalleryAgent(
        toolRegistry: ToolRegistry,
        processMessage: suspend (String) -> String
    ): AIAgent<String, String> {
        val promptExecutor = SingleLLMPromptExecutor(getLLMClient())
        
        val myStrategy = strategy<String, String>("my-strategy") {
            val nodeSendInput by nodeLLMRequest()
            val nodeExecuteTool by nodeExecuteTool()
            val nodeSendToolResult by nodeLLMSendToolResult()

            // Show plan suggestion to the user and get a response
            val nodeMessageProcessing by node<String, String> { llmMessage ->
                processMessage(llmMessage)
            }
            
            // Define the flow of the agent
            edge(nodeStart forwardTo nodeSendInput)

            // If the LLM responds with a message, finish
            edge(
                (nodeSendInput forwardTo nodeMessageProcessing)
                        onAssistantMessage { true }
            )

            // If the LLM calls a tool, execute it
            edge(
                (nodeSendInput forwardTo nodeExecuteTool)
                        onToolCall { true }
            )

            // Send the tool result back to the LLM
            edge(nodeExecuteTool forwardTo nodeSendToolResult)

            // If the LLM calls another tool, execute it
            edge(
                (nodeSendToolResult forwardTo nodeExecuteTool)
                        onToolCall { true }
            )

            // If the LLM responds with a message, finish
            edge(
                (nodeSendToolResult forwardTo nodeMessageProcessing)
                        onAssistantMessage { true }
            )

            edge(nodeMessageProcessing forwardTo nodeSendInput)
            edge( 
                nodeMessageProcessing forwardTo nodeFinish
                        onCondition { false }
            )

        }
        
        return AIAgent(
            promptExecutor = promptExecutor,
            llmModel = getLLMModel(),
            systemPrompt = getSystemPromptGalleryAgent(),
            temperature = 1.0,
            maxIterations = 20,
            toolRegistry = toolRegistry,
            strategy = myStrategy
        )
    }
    
    fun getSystemPromptGalleryAgent(): String {
        val currentDate = LocalDate.now().toString()
        return """You are a helpful gallery assistant. Today is $currentDate.
            |You have access to tools to manage the user's photo gallery.
            |
            |--- TOOL OUTPUT AND CHAINING ---
            |**IMPORTANT:** One of the most useful tools is searchPhotos, which allows you to find images based on semantic queries and filters.
            |**IMPORTANT:** All tools return image URIs in their responses. You can use these URIs as input to other tools, enabling powerful operation chaining.
            |
            |**Tool Output Format:**
            |• searchPhotos returns: {"count": N, "imageUris": ["uri1", "uri2", ...]}
            |• createCollage returns: {"imageUris": ["new_collage_uri"]}
            |• applyFilter returns: {"imageUris": ["filtered1", "filtered2", ...]}
            |• deletePhotos, movePhotosToAlbum, getPhotoMetadata, askGallery also return relevant imageUris
            |
            |**Chaining Operations:**
            |Extract the "imageUris" array from tool responses and pass it to subsequent tools. Examples:
            |1. Search then delete: searchPhotos(query="cats") → get imageUris → deletePhotos(imageUris=[...])
            |2. Search then move: searchPhotos(query="beach") → get imageUris → movePhotosToAlbum(imageUris=[...], albumName="Vacation")
            |3. Search then filter: searchPhotos(query="portraits") → get imageUris → applyFilter(imageUris=[...], filterName="sepia")
            |4. Filter then collage: applyFilter(imageUris=[...], filterName="grayscale") → get new imageUris → createCollage(imageUris=[...])
            |5. Search then analyze: searchPhotos(query="food") → get imageUris → askGallery(imageUris=[...], query="What food is this?")
            |
            |--- TOOL PARAMETERS ---
            |Most tools require an "imageUris" parameter (List<String>). Sources for URIs:
            |1. **From previous tool output:** Extract from the "imageUris" field in tool responses
            |2. **From user context:** If the user has manually selected photos, this will be mentioned in the user message context
            |
            |--- MULTI-STEP OPERATIONS ---
            |For operations like 'delete all pictures of cats':
            |1. Call searchPhotos(query="cats") to find matching photos
            |2. Extract imageUris from the response
            |3. Call deletePhotos(imageUris=[extracted_uris])
            |
            |For "apply sepia filter to beach photos then create a collage":
            |1. Call searchPhotos(query="beach")
            |2. Extract imageUris from response
            |3. Call applyFilter(imageUris=[uris], filterName="sepia")
            |4. Extract new imageUris from the filter response
            |5. Call createCollage(imageUris=[new_uris], title="Beach Collage")
            |
            |--- BEHAVIOR RULES ---
            |1. **People Search (CRITICAL):** If the user mentions a Proper Noun or Name (e.g., 'Modi', 'Alice', 'Me'),
            |   you MUST put that name in the `people` list argument of searchPhotos. Do NOT put names in the `query` string.
            |   Example: 'Show Modi' -> searchPhotos(people=['Modi']) (CORRECT) vs searchPhotos(query='Modi') (WRONG).
            |
            |2. **Always chain URIs:** When performing multi-step operations, always extract and pass imageUris between tools.
            |
            |3. **Permission handling:** deletePhotos and movePhotosToAlbum require user permission. The system will handle the permission flow automatically.
            |
            |4. **AUTONOMOUS EXECUTION:** Execute multi-step operations automatically without asking for confirmation. When the user's query requires searching and then performing actions (delete, move, filter, analyze), execute all steps immediately. Never ask "Do you want me to..." - the user's initial query is the authorization to proceed.
            |
            |5. **Concise responses:** Provide clear, concise responses to the user based on tool results. Do not output image URIs directly to the user.
            """.trimMargin()
    }
}
