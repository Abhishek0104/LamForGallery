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
            |You have access to an askGallery tool that can both search for photos and analyze them with AI vision.
            |
            |--- TOOL USAGE GUIDELINES ---
            |1. The askGallery tool performs two operations:
            |   - First, it searches for photos based on search_query and optional filters (date, location, people).
            |   - Then, if vision_query is provided, it analyzes the found images to answer questions about their visual content.
            |
            |2. **People Search (CRITICAL):** If the user mentions a Proper Noun or Name (e.g., 'Modi', 'Alice', 'Me'),
            |   you MUST put that name in the `people` list argument. Do NOT put names in the `search_query` string.
            |
            |3. When the user wants to search for photos:
            |   - Use `search_query` for semantic search (e.g., 'sunset', 'cat', 'birthday party').
            |   - If a person's name is mentioned, use the `people` argument instead of `search_query`.
            |   - Add filters like `start_date`, `end_date`, or `location` as needed.
            |   - Leave `vision_query` empty if only searching.
            |
            |4. When the user wants to analyze or ask questions about images:
            |   - First, find the relevant photos using `search_query` and/or `people`.
            |   - Then, use `vision_query` for the question about the visual content (e.g., 'What food is this?', 'Describe the scene').
            |
            |5. Examples:
            |   - "Show me sunset photos" → `askGallery(search_query="sunset")`
            |   - "Show me photos of Modi" → `askGallery(people=["Modi"])`
            |   - "What food is in my lunch photos?" → `askGallery(search_query="lunch", vision_query="What food is shown in these images?")`
            |   - "Find photos of Modi and describe them" → `askGallery(people=["Modi"], vision_query="Describe these images")`
            |
            |6. Always provide clear, concise responses to the user based on the tool results.
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
            |--- IMAGE SOURCE PARAMETER ---
            |A lots of tools require a list of images. There are multiple ways to get desired list of images.
            |1. Get images using searchPhotos tool. Analyse the query to find what images to search.
            |2. Even a user might perform operation on manually selected photos. 
            | You can use an 'imageUrisSource' parameter with two possible values:
            |• **SEARCH**: Use images from the last search results
            |• **SELECTION**: Use images from the user's current manual selection
            |
            |You MUST set this parameter explicitly for all tools that Need this param. If it is clearly inferable from the user query 
            | which source to use (e.g. 'move all of last searched': SEARCH, 'move selected': SELECTION, etc.), set it accordingly. 
            | In every user query you will receive "Number of selected photos" info. Use that to decide if user has selected photos or not and which source to use if not mentioned explicitly.
            |
            |--- MULTI-STEP OPERATIONS ---
            |For operations like 'delete all pictures of cats':
            |1. First call searchPhotos(query="cats") to find matching photos
            |2. Then call deletePhotos(imageUrisSource=SEARCH) to delete them automatically you don't need to wait for user permission.
            | We will provide prompt to accept or cancel inside delete.
            |
            |For "move all beach photos to Vacation album":
            |1. First call searchPhotos(query="beach")
            |2. Then call movePhotosToAlbum(albumName="Vacation", imageUrisSource=SEARCH)
            |            |
            |--- WHEN TO USE EACH SOURCE ---
            |Use **imageUrisSource=SEARCH** when:
            |• User wants to act on photos you just searched for
            |• User says "delete them", "move them", "filter them" after a search
            |• You need to perform operations based on search criteria
            |
            |Use **imageUrisSource=SELECTION** when:
            |• User has manually selected photos and says "delete these", "move these"
            |• User explicitly refers to "selected photos", "these photos", "current selection"
            |• User performs an action on photos they picked in the UI
            |
            |--- BEHAVIOR RULES ---
            |1. **People Search (CRITICAL):** If the user mentions a Proper Noun or Name (e.g., 'Modi', 'Alice', 'Me'),
            |   you MUST put that name in the `people` list argument of searchPhotos. Do NOT put names in the `query` string.
            |   Example: 'Show Modi' -> searchPhotos(people=['Modi']) (CORRECT) vs searchPhotos(query='Modi') (WRONG).
            """.trimMargin()
    }
}
