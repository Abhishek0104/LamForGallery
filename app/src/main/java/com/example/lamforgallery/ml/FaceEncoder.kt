package com.example.lamforgallery.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.FloatBuffer

/**
 * Runs the ArcFace / MobileFaceNet ONNX model.
 * Input: 112x112 Face Crop
 * Output: 128-dimension identity vector
 */
class FaceEncoder(private val context: Context) {

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession
    private val inputName: String

    companion object {
        private const val MODEL_FILE = "mobile_face_net.onnx" // Make sure this is in assets
        private const val TAG = "FaceEncoder"
        private const val IMG_SIZE = 112 // MobileFaceNet standard input
    }

    init {
        val modelPath = getModelPath()
        ortSession = ortEnv.createSession(modelPath)
        inputName = ortSession.inputNames.first()
    }

    fun getFaceEmbedding(faceBitmap: Bitmap): FloatArray {
        try {
            // 1. Pre-process: Resize and Normalize
            val buffer = bitmapToFloatBuffer(faceBitmap)

            // 2. Tensor Shape: [1, 3, 112, 112]
            val shape = longArrayOf(1, 3, IMG_SIZE.toLong(), IMG_SIZE.toLong())

            // 3. Run Inference
            OnnxTensor.createTensor(ortEnv, buffer, shape).use { inputTensor ->
                ortSession.run(mapOf(inputName to inputTensor)).use { outputs ->
                    (outputs.first().value as OnnxTensor).use { outputTensor ->
                        // Output is [1, 128]
                        val embedding = (outputTensor.value as Array<FloatArray>)[0]
                        return embedding
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Face encoding failed", e)
            return FloatArray(128) // Return empty zero vector on fail
        }
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, IMG_SIZE, IMG_SIZE, true)
        val readable = if (scaled.config == Bitmap.Config.HARDWARE) scaled.copy(Bitmap.Config.ARGB_8888, false) else scaled

        val buffer = FloatBuffer.allocate(1 * 3 * IMG_SIZE * IMG_SIZE)
        val pixels = IntArray(IMG_SIZE * IMG_SIZE)
        readable.getPixels(pixels, 0, IMG_SIZE, 0, 0, IMG_SIZE, IMG_SIZE)

        for (pixel in pixels) {
            val r = ((pixel shr 16 and 0xFF) - 127.5f) / 128.0f
            val g = ((pixel shr 8 and 0xFF) - 127.5f) / 128.0f
            val b = ((pixel and 0xFF) - 127.5f) / 128.0f
            // NCHW format
            buffer.put(r)
            buffer.put(g)
            buffer.put(b)
        }
        buffer.rewind()

        if (readable != scaled) readable.recycle()
        scaled.recycle()

        // Re-organize buffer to be planar (RRR...GGG...BBB)
        // The loop above interleaves them (RGBRGB). We need planar for ONNX.
        // Let's redo the loop correctly for Planar.
        val planarBuffer = FloatBuffer.allocate(1 * 3 * IMG_SIZE * IMG_SIZE)

        // R Plane
        for (i in pixels.indices) planarBuffer.put(i, ((pixels[i] shr 16 and 0xFF) - 127.5f) / 128.0f)
        // G Plane
        for (i in pixels.indices) planarBuffer.put(i + pixels.size, ((pixels[i] shr 8 and 0xFF) - 127.5f) / 128.0f)
        // B Plane
        for (i in pixels.indices) planarBuffer.put(i + (2 * pixels.size), ((pixels[i] and 0xFF) - 127.5f) / 128.0f)

        planarBuffer.rewind()
        return planarBuffer
    }

    private fun getModelPath(): String {
        val modelFile = File(context.cacheDir, MODEL_FILE)
        val dataFile = File(context.cacheDir, "$MODEL_FILE.data") // The external weights file

        try {
            // 1. Copy main model file
            if (!modelFile.exists()) {
                context.assets.open(MODEL_FILE).use { input ->
                    FileOutputStream(modelFile).use { output -> input.copyTo(output) }
                }
            }

            // 2. Copy external data file (if it exists in assets)
            // You must manually put 'mobile_face_net.onnx.data' in assets first!
            try {
                context.assets.open("$MODEL_FILE.data").use { input ->
                    FileOutputStream(dataFile).use { output -> input.copyTo(output) }
                }
            } catch (e: FileNotFoundException) {
                // Ignore if the model doesn't actually use external data
            }

        } catch (e: Exception) {
            throw RuntimeException("Failed to copy model files", e)
        }
        return modelFile.absolutePath
    }
}