package com.example.lamforgallery.network

import com.example.lamforgallery.data.AgentRequest
import com.example.lamforgallery.data.AgentResponse
import retrofit2.http.Body
import retrofit2.http.POST

interface AgentApiService {
    @POST("agent/invoke")
    suspend fun invokeAgent(@Body request: AgentRequest): AgentResponse
}