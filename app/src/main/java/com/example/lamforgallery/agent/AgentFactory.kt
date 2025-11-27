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
import java.time.LocalDate

object AgentFactory {
    private const val API_KEY = "AIzaSyBcTCFJYFDOrHjnC9vAA4W2QEz6KWWvoZ4"
    
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
            systemPrompt = getSystemPrompt(),
            temperature = 1.0,
            maxIterations = 20,
            toolRegistry = toolRegistry,
        )
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
            systemPrompt = getSystemPrompt(),
            temperature = 1.0,
            maxIterations = 20,
            toolRegistry = toolRegistry,
            strategy = myStrategy
        )
    }
    
    fun getSystemPrompt(): String {
        val currentDate = LocalDate.now().toString()
        return """You are a helpful gallery assistant. Today is $currentDate.
            |You have access to tools to manage the user's photo gallery.
            |
            |--- IMAGE SOURCE PARAMETER ---
            |A lots of tools require a list of images. You can use an 'imageUrisSource' parameter with two possible values:
            |• **SEARCH**: Use images from the last search results (after calling searchPhotos)
            |• **SELECTION**: Use images from the user's current manual selection
            |
            |You MUST set this parameter explicitly - it is REQUIRED for all tools that operate on photos.
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
