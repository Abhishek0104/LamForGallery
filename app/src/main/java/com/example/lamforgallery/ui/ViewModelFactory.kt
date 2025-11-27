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
import com.example.lamforgallery.utils.CleanupManager
import com.google.gson.Gson

class ViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {

    private val gson: Gson by lazy { NetworkModule.gson }
    private val galleryTools: GalleryTools by lazy { GalleryTools(application) }
    private val agentApi: AgentApiService by lazy { NetworkModule.apiService }
    private val appDatabase: AppDatabase by lazy { AppDatabase.getDatabase(application) }
    private val imageEmbeddingDao: ImageEmbeddingDao by lazy { appDatabase.imageEmbeddingDao() }
    private val personDao by lazy { appDatabase.personDao() }

    private val imageEncoder: ImageEncoder by lazy { ImageEncoder(application) }
    private val clipTokenizer: ClipTokenizer by lazy { ClipTokenizer(application) }
    private val textEncoder: TextEncoder by lazy { TextEncoder(application) }
    private val cleanupManager: CleanupManager by lazy { CleanupManager(imageEmbeddingDao) }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(AgentViewModel::class.java) -> {
                AgentViewModel(
                    application, agentApi, galleryTools, gson,
                    imageEmbeddingDao, personDao, clipTokenizer,
                    textEncoder,
                    cleanupManager
                ) as T
            }
            modelClass.isAssignableFrom(PhotosViewModel::class.java) -> PhotosViewModel(galleryTools, imageEmbeddingDao) as T
            modelClass.isAssignableFrom(AlbumsViewModel::class.java) -> AlbumsViewModel(galleryTools) as T
            modelClass.isAssignableFrom(AlbumDetailViewModel::class.java) -> AlbumDetailViewModel(galleryTools) as T
            modelClass.isAssignableFrom(EmbeddingViewModel::class.java) -> EmbeddingViewModel(application, imageEmbeddingDao, imageEncoder, galleryTools) as T
            modelClass.isAssignableFrom(PhotoViewerViewModel::class.java) -> PhotoViewerViewModel() as T
            modelClass.isAssignableFrom(PeopleViewModel::class.java) -> PeopleViewModel(personDao) as T
            // --- NEW: Person Detail ViewModel ---
            modelClass.isAssignableFrom(PersonDetailViewModel::class.java) -> PersonDetailViewModel(personDao) as T
            modelClass.isAssignableFrom(TrashViewModel::class.java) -> TrashViewModel(imageEmbeddingDao) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}