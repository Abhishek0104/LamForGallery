package com.example.lamforgallery.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

object ImageHelper {

    private const val MAX_DIMENSION = 1024 // Max width/height in pixels
    private const val JPEG_QUALITY = 80    // Good balance of size vs quality

    /**
     * converts a list of content URIs into a list of Base64 encoded strings.
     * Performs resizing and compression to keep payloads light.
     */
    suspend fun encodeImages(context: Context, uris: List<String>): List<String> {
        return withContext(Dispatchers.IO) {
            uris.mapNotNull { uriString ->
                encodeSingleImage(context, Uri.parse(uriString))
            }
        }
    }

    private fun encodeSingleImage(context: Context, uri: Uri): String? {
        return try {
            // 1. Decode dimensions first (to avoid loading massive image into RAM)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }

            // 2. Calculate scale factor
            options.inSampleSize = calculateInSampleSize(options, MAX_DIMENSION, MAX_DIMENSION)
            options.inJustDecodeBounds = false

            // 3. Load the resized bitmap
            val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: return null

            // 4. Compress to JPEG and encode to Base64
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            val byteArray = outputStream.toByteArray()

            // Clean up
            bitmap.recycle()
            outputStream.close()

            // Return standard Base64 string (NO line breaks)
            Base64.encodeToString(byteArray, Base64.NO_WRAP)

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}