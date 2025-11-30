package com.example.lamforgallery.ui

import android.app.Application
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lamforgallery.database.AppDatabase
import com.example.lamforgallery.database.ImageEmbedding
import com.example.lamforgallery.database.ImageEmbeddingDao
import com.example.lamforgallery.database.ImagePersonCrossRef
import com.example.lamforgallery.database.Person
import com.example.lamforgallery.ml.FaceEncoder
import com.example.lamforgallery.ml.ImageEncoder
import com.example.lamforgallery.tools.GalleryTools
import com.example.lamforgallery.utils.SimilarityUtil
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.math.pow
import kotlin.math.sqrt

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

    private val db = AppDatabase.getDatabase(application)
    private val personDao = db.personDao()
    private val faceEncoder = FaceEncoder(application)
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.1f)
            .build()
    )

    companion object {
        private const val TAG = "EmbeddingViewModel"
        private const val MIN_FACE_SIZE_PIXELS = 64 // Minimum width/height for a face
        private const val BLUR_THRESHOLD = 35.0 // Lower is more blurry. Adjusted for manual implementation.
        private const val SIMILARITY_THRESHOLD = 0.65f // Stricter matching
    }

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
                val allImageUris = getAllImageUris()
                val indexedUris = dao.getAllEmbeddings().map { it.uri }.toSet()
                val newImages = allImageUris.filter { !indexedUris.contains(it.toString()) }
                val totalNewImages = newImages.size

                if (totalNewImages == 0) {
                    _uiState.update { it.copy(isIndexing = false, statusMessage = "All caught up!", totalImages = allImageUris.size, indexedImages = allImageUris.size) }
                    return@launch
                }

                newImages.forEachIndexed { index, uri ->
                    val imageNumber = index + 1
                    _uiState.update { it.copy(statusMessage = "Indexing $imageNumber of $totalNewImages...", progress = imageNumber.toFloat() / totalNewImages.toFloat()) }

                    var bitmap: Bitmap? = null
                    try {
                        bitmap = loadBitmap(uri)
                        if (bitmap == null) return@forEachIndexed

                        val embedding = imageEncoder.encode(bitmap)
                        val info = galleryTools.extractImageInfo(uri.toString())

                        dao.insert(ImageEmbedding(
                            uri = uri.toString(),
                            embedding = embedding,
                            location = info.location,
                            dateTaken = info.dateTaken,
                            width = info.width,
                            height = info.height,
                            cameraModel = info.cameraModel
                        ))

                        processFaces(bitmap, uri.toString())

                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process image $uri: ${e.message}", e)
                    } finally {
                        bitmap?.recycle()
                    }
                }

                _uiState.update { it.copy(isIndexing = false, statusMessage = "Done! Added $totalNewImages new images.", progress = 1f) }

            } catch (e: Exception) {
                _uiState.update { it.copy(isIndexing = false, statusMessage = "Error: ${e.message}") }
            }
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }

            val targetSize = 1024
            var scale = 1
            while (options.outWidth / scale / 2 >= targetSize && options.outHeight / scale / 2 >= targetSize) {
                scale *= 2
            }

            val loadOptions = BitmapFactory.Options().apply { inSampleSize = scale }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, loadOptions) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap: ${e.message}")
            null
        }
    }

    private fun floatArrayToByteArray(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4)
        buffer.asFloatBuffer().put(floats)
        return buffer.array()
    }

    private suspend fun identifyAndLinkPerson(
        newVector: FloatArray,
        uri: String,
        bounds: android.graphics.Rect,
        imageWidth: Int,
        imageHeight: Int
    ) {
        val allPeople = personDao.getAllPeople()
        var bestMatch: Person? = null
        var bestSim = 0f

        for (person in allPeople) {
            val sim = SimilarityUtil.cosineSimilarity(newVector, person.embedding)
            if (sim > bestSim) {
                bestSim = sim
                bestMatch = person
            }
        }

        val personId: String
        if (bestMatch != null && bestSim > SIMILARITY_THRESHOLD) {
            personId = bestMatch.id
            val embeddingBytes = floatArrayToByteArray(bestMatch.embedding)
            personDao.updateEmbedding(bestMatch.id, embeddingBytes, bestMatch.faceCount + 1)
        } else {
            val w = if (imageWidth > 0) imageWidth.toFloat() else 1f
            val h = if (imageHeight > 0) imageHeight.toFloat() else 1f

            val newPerson = Person(
                name = "Unknown ${allPeople.size + 1}",
                embedding = newVector,
                coverUri = uri,
                faceLeft = bounds.left / w,
                faceTop = bounds.top / h,
                faceRight = bounds.right / w,
                faceBottom = bounds.bottom / h
            )
            personDao.insertPerson(newPerson)
            personId = newPerson.id
        }

        personDao.insertImagePersonLink(ImagePersonCrossRef(uri, personId))
    }

    private fun isFaceQualityGood(faceBitmap: Bitmap): Boolean {
        if (faceBitmap.width < MIN_FACE_SIZE_PIXELS || faceBitmap.height < MIN_FACE_SIZE_PIXELS) {
            Log.d(TAG, "Face rejected: Too small (${faceBitmap.width}x${faceBitmap.height})")
            return false
        }

        val variance = calculateBlurriness(faceBitmap)
        if (variance < BLUR_THRESHOLD) {
            Log.d(TAG, "Face rejected: Too blurry (Variance: $variance)")
            return false
        }

        return true
    }

    private fun calculateBlurriness(bitmap: Bitmap): Double {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 3 || height < 3) return 0.0 // Not enough data to calculate

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var sumOfVariances = 0.0
        var mean = 0.0
        val laplacian = IntArray(width * height)

        // Simplified grayscale and Laplacian operator in one pass
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val p = y * width + x

                // Get grayscale values of 3x3 neighborhood
                val p1 = Color.red(pixels[p - width - 1])
                val p2 = Color.red(pixels[p - width])
                val p3 = Color.red(pixels[p - width + 1])
                val p4 = Color.red(pixels[p - 1])
                val p5 = Color.red(pixels[p])
                val p6 = Color.red(pixels[p + 1])
                val p7 = Color.red(pixels[p + width - 1])
                val p8 = Color.red(pixels[p + width])
                val p9 = Color.red(pixels[p + width + 1])

                // Apply Laplacian kernel: [[0, 1, 0], [1, -4, 1], [0, 1, 0]]
                val edge = (p2 + p4 + p6 + p8) - 4 * p5
                laplacian[p] = edge
                mean += edge
            }
        }

        mean /= (width * height)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                sumOfVariances += (laplacian[y * width + x] - mean).pow(2.0)
            }
        }

        return sumOfVariances / (width * height)
    }


    private suspend fun processFaces(bitmap: Bitmap, uri: String) {
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val faces = detectFacesSuspend(inputImage)

            for (face in faces) {
                val bounds = face.boundingBox
                if (bounds.left < 0 || bounds.top < 0 || bounds.right > bitmap.width || bounds.bottom > bitmap.height) {
                    continue
                }

                val faceCrop = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())

                if (isFaceQualityGood(faceCrop)) {
                    val faceVector = faceEncoder.getFaceEmbedding(faceCrop)
                    identifyAndLinkPerson(faceVector, uri, bounds, bitmap.width, bitmap.height)
                }

                faceCrop.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Face processing error", e)
        }
    }

    private suspend fun detectFacesSuspend(image: InputImage): List<com.google.mlkit.vision.face.Face> {
        return suspendCancellableCoroutine { cont ->
            faceDetector.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener {
                    Log.e(TAG, "Face detection failed", it)
                    cont.resume(emptyList())
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