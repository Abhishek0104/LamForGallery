package com.example.lamforgallery.ui

import android.app.Application
import android.content.ContentUris
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lamforgallery.database.ImageEmbedding
import com.example.lamforgallery.database.ImageEmbeddingDao
import com.example.lamforgallery.ml.ImageEncoder
import com.example.lamforgallery.tools.GalleryTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EmbeddingUiState(
    val statusMessage: String = "Ready to index. Press 'Start' to begin.",
    val progress: Float = 0f,
    val isIndexing: Boolean = false,
    val totalImages: Int = 0,
    val indexedImages: Int = 0
)

class EmbeddingViewModel(
    application: Application,
    private val dao: ImageEmbeddingDao,
    private val imageEncoder: ImageEncoder,
    private val galleryTools: GalleryTools
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(EmbeddingUiState())
    val uiState: StateFlow<EmbeddingUiState> = _uiState.asStateFlow()
    private val contentResolver = application.contentResolver

    companion object { private const val TAG = "EmbeddingViewModel" }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val allImages = getAllImageUris()
            val indexedImages = dao.getAllEmbeddings().size
            _uiState.update {
                it.copy(totalImages = allImages.size, indexedImages = indexedImages, statusMessage = "Ready. ${allImages.size} total images found, ${indexedImages} already indexed.")
            }
        }
    }

    fun startIndexing() {
        if (_uiState.value.isIndexing) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isIndexing = true, statusMessage = "Starting...", progress = 0f) }

            try {
                _uiState.update { it.copy(statusMessage = "Scanning device for images...") }
                val allImageUris = getAllImageUris()
                val indexedUris = dao.getAllEmbeddings().map { it.uri }.toSet()
                val newImages = allImageUris.filter { !indexedUris.contains(it.toString()) }
                val totalNewImages = newImages.size

                if (totalNewImages == 0) {
                    _uiState.update { it.copy(isIndexing = false, statusMessage = "All ${allImageUris.size} images are already indexed!", totalImages = allImageUris.size, indexedImages = allImageUris.size) }
                    return@launch
                }

                newImages.forEachIndexed { index, uri ->
                    val imageNumber = index + 1
                    _uiState.update { it.copy(statusMessage = "Indexing $imageNumber of $totalNewImages...", progress = imageNumber.toFloat() / totalNewImages.toFloat()) }

                    try {
                        // 1. Decode Bitmap
                        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri))
                        } else {
                            @Suppress("DEPRECATION")
                            MediaStore.Images.Media.getBitmap(contentResolver, uri)
                        }

                        // 2. Generate Embedding
                        val embedding = imageEncoder.encode(bitmap)

                        // 3. Extract All Metadata (Location, Date, Model, Dims)
                        val info = galleryTools.extractImageInfo(uri.toString())

                        // 4. Save to DB
                        dao.insert(ImageEmbedding(
                            uri = uri.toString(),
                            embedding = embedding,
                            location = info.location,
                            dateTaken = info.dateTaken,
                            width = info.width,
                            height = info.height,
                            cameraModel = info.cameraModel
                        ))

                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process image $uri: ${e.message}", e)
                    }
                }

                _uiState.update { it.copy(isIndexing = false, statusMessage = "Indexing complete! Added $totalNewImages new images.", progress = 1f, totalImages = allImageUris.size, indexedImages = allImageUris.size) }

            } catch (e: Exception) {
                _uiState.update { it.copy(isIndexing = false, statusMessage = "Error: ${e.message}") }
            }
        }
    }

    private fun getAllImageUris(): List<Uri> {
        val imageUris = mutableListOf<Uri>()
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                imageUris.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
            }
        }
        return imageUris
    }
}