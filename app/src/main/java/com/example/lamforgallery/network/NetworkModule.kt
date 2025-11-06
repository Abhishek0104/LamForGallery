package com.example.lamforgallery.network

import com.example.lamforgallery.network.AgentApiService
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object NetworkModule {

    // --- !!! IMPORTANT !!! ---
    //
    // 1. Find your computer's "Local" or "WLAN" IP address.
    //    - On Mac/Linux: run `ifconfig | grep "inet "` in terminal.
    //    - On Windows: run `ipconfig` in Command Prompt.
    //    (It will look like 192.168.1.10, NOT 127.0.0.1)
    //
    // 2. If using a PHYSICAL Android device (on the same WiFi):
    //    const val BASE_URL = "http://YOUR_COMPUTER_IP:8000/"
    //
    // 3. If using the Android EMULATOR:
    //    Use this special IP, which points from the emulator to your computer.
    //
    const val BASE_URL = "http://192.168.0.7:8000"
    //
    // --- !!! IMPORTANT !!! ---


    // We need a shared Gson instance for the ViewModel
    val gson: Gson = GsonBuilder().create()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()

    val apiService: AgentApiService = retrofit.create(AgentApiService::class.java)
}