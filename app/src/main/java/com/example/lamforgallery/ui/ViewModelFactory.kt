package com.example.lamforgallery.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.lamforgallery.network.AgentApiService
import com.example.lamforgallery.network.NetworkModule
import com.example.lamforgallery.tools.GalleryTools
import com.google.gson.Gson

/**
 * A single, unified ViewModel Factory for the entire application.
 * This factory provides all necessary dependencies (like GalleryTools)
 * as singletons to any ViewModel that requests them.
 */
class ViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {

    // --- Create Singleton Dependencies ---
    // These are created once and shared by all ViewModels

    private val gson: Gson by lazy {
        NetworkModule.gson
    }

    private val galleryTools: GalleryTools by lazy {
        GalleryTools(application.contentResolver)
    }

    private val agentApi: AgentApiService by lazy {
        NetworkModule.apiService
    }

    // --- End Dependencies ---

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            // When AgentViewModel is requested...
            modelClass.isAssignableFrom(AgentViewModel::class.java) -> {
                AgentViewModel(application, agentApi, galleryTools, gson) as T
            }

            // When PhotosViewModel is requested...
            modelClass.isAssignableFrom(PhotosViewModel::class.java) -> {
                PhotosViewModel(galleryTools) as T
            }

            // When AlbumsViewModel is requested...
            modelClass.isAssignableFrom(AlbumsViewModel::class.java) -> {
                AlbumsViewModel(galleryTools) as T
            }

            else -> {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}