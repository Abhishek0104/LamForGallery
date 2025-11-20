package com.example.lamforgallery.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.lamforgallery.network.AgentApiService
import com.example.lamforgallery.network.NetworkModule
import com.example.lamforgallery.tools.GalleryTools
import com.example.lamforgallery.database.AppDatabase
import com.example.lamforgallery.database.ImageEmbeddingDao
import com.example.lamforgallery.ml.ClipTokenizer
import com.example.lamforgallery.ml.ImageEncoder
import com.example.lamforgallery.ml.TextEncoder
import com.example.lamforgallery.utils.CleanupManager // --- NEW IMPORT ---
import com.google.gson.Gson

class ViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {

    private val gson: Gson by lazy { NetworkModule.gson }
    private val galleryTools: GalleryTools by lazy { GalleryTools(application.contentResolver) }
    private val agentApi: AgentApiService by lazy { NetworkModule.apiService }
    private val appDatabase: AppDatabase by lazy { AppDatabase.getDatabase(application) }
    private val imageEmbeddingDao: ImageEmbeddingDao by lazy { appDatabase.imageEmbeddingDao() }
    private val imageEncoder: ImageEncoder by lazy { ImageEncoder(application) }
    private val clipTokenizer: ClipTokenizer by lazy { ClipTokenizer(application) }
    private val textEncoder: TextEncoder by lazy { TextEncoder(application) }

    // --- NEW DEPENDENCY ---
    private val cleanupManager: CleanupManager by lazy { CleanupManager(imageEmbeddingDao) }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(AgentViewModel::class.java) -> {
                AgentViewModel(
                    application, agentApi, galleryTools, gson,
                    imageEmbeddingDao, clipTokenizer, textEncoder,
                    cleanupManager // --- PASSED HERE ---
                ) as T
            }
            modelClass.isAssignableFrom(PhotosViewModel::class.java) -> PhotosViewModel(galleryTools) as T
            modelClass.isAssignableFrom(AlbumsViewModel::class.java) -> AlbumsViewModel(galleryTools) as T
            modelClass.isAssignableFrom(AlbumDetailViewModel::class.java) -> AlbumDetailViewModel(galleryTools) as T
            modelClass.isAssignableFrom(EmbeddingViewModel::class.java) -> EmbeddingViewModel(application, imageEmbeddingDao, imageEncoder) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}