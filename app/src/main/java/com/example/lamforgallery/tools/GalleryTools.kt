package com.example.lamforgallery.tools

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ceil

class GalleryTools(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    private val TAG = "GalleryTools"

    data class ImageInfo(
        val location: String? = null,
        val dateTaken: Long = 0L,
        val width: Int = 0,
        val height: Int = 0,
        val cameraModel: String? = null
    )

    suspend fun extractImageInfo(uriString: String): ImageInfo {
        return withContext(Dispatchers.IO) {
            try {
                resolver.openInputStream(Uri.parse(uriString))?.use { inputStream ->
                    val exif = ExifInterface(inputStream)

                    var location: String? = null
                    val latLong = exif.latLong
                    if (latLong != null) {
                        location = reverseGeocode(latLong[0], latLong[1])
                    }

                    val dateString = exif.getAttribute(ExifInterface.TAG_DATETIME)
                    val dateTaken = parseExifDate(dateString)

                    val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                    val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)

                    val make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: ""
                    val model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: ""
                    val cameraModel = if (make.isNotBlank() || model.isNotBlank()) "$make $model".trim() else null

                    return@withContext ImageInfo(location, dateTaken, width, height, cameraModel)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading metadata for $uriString", e)
            }
            ImageInfo()
        }
    }

    private fun parseExifDate(dateString: String?): Long {
        if (dateString == null) return System.currentTimeMillis()
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            sdf.parse(dateString)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun reverseGeocode(lat: Double, lon: Double): String? {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                formatAddress(addresses?.firstOrNull())
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                formatAddress(addresses?.firstOrNull())
            }
        } catch (e: Exception) { null }
    }

    private fun formatAddress(address: android.location.Address?): String? {
        if (address == null) return null
        val city = address.locality ?: address.subAdminArea
        val country = address.countryName
        return when {
            city != null && country != null -> "$city, $country"
            city != null -> city
            country != null -> country
            else -> null
        }
    }

    suspend fun searchPhotos(query: String): List<String> {
        val photoUris = mutableListOf<String>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        return withContext(Dispatchers.IO) {
            resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    photoUris.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString())
                }
            }
            photoUris
        }
    }

    suspend fun getPhotosInDateRange(startMillis: Long, endMillis: Long): List<String> {
        val photoUris = mutableListOf<String>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} <= ?"
        val selectionArgs = arrayOf(startMillis.toString(), endMillis.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        return withContext(Dispatchers.IO) {
            try {
                resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        photoUris.add(ContentUris.withAppendedId(collection, id).toString())
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Error", e) }
            photoUris
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun createDeleteIntentSender(photoUris: List<String>): IntentSender? {
        val uris = photoUris.map { Uri.parse(it) }
        return MediaStore.createDeleteRequest(resolver, uris).intentSender
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun createWriteIntentSender(photoUris: List<String>): IntentSender? {
        val uris = photoUris.map { Uri.parse(it) }
        return MediaStore.createWriteRequest(resolver, uris).intentSender
    }

    suspend fun performMoveOperation(photoUris: List<String>, albumName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                for (uriString in photoUris) {
                    val values = ContentValues().apply { put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$albumName") }
                    resolver.update(Uri.parse(uriString), values, null, null)
                }
                true
            } catch (e: Exception) { false }
        }
    }

    // --- FIX: Add target size for downsampling ---
    private fun loadBitmapFromUri(uriString: String, targetWidth: Int = -1, targetHeight: Int = -1): Bitmap? {
        return try {
            val uri = Uri.parse(uriString)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(resolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.isMutableRequired = true
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE

                    // Downsample if targets provided
                    if (targetWidth > 0 && targetHeight > 0) {
                        val size = info.size
                        var sampleSize = 1
                        if (size.width > targetWidth || size.height > targetHeight) {
                            val halfHeight = size.height / 2
                            val halfWidth = size.width / 2
                            while ((halfHeight / sampleSize) >= targetHeight && (halfWidth / sampleSize) >= targetWidth) {
                                sampleSize *= 2
                            }
                        }
                        decoder.setTargetSampleSize(sampleSize)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(resolver, uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap: $uriString", e)
            null
        }
    }

    suspend fun applyFilter(photoUris: List<String>, filterName: String): List<String> {
        Log.d(TAG, "Applying filter '$filterName' to ${photoUris.size} photos")
        val filter = when (filterName.lowercase()) {
            "grayscale", "black and white", "b&w" -> FilterType.GRAYSCALE
            "sepia" -> FilterType.SEPIA
            else -> throw Exception("Unknown filter: $filterName.")
        }
        return withContext(Dispatchers.IO) {
            val newImageUris = mutableListOf<String>()
            for (uriString in photoUris) {
                val originalBitmap = loadBitmapFromUri(uriString) // Load full size for filters, or maybe downsample slightly?
                if (originalBitmap == null) continue

                try {
                    val filteredBitmap = applyColorFilter(originalBitmap, filter)
                    val originalName = getFileName(uriString) ?: "filtered_image"
                    val newTitle = "${originalName}_${filterName}_${System.currentTimeMillis()}"
                    val newUri = saveBitmapToMediaStore(filteredBitmap, newTitle, "Pictures/Filters")
                    newImageUris.add(newUri.toString())
                    originalBitmap.recycle()
                    filteredBitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process/save filtered bitmap", e)
                }
            }
            newImageUris
        }
    }

    suspend fun createCollage(photoUris: List<String>, title: String): String? {
        if (photoUris.isEmpty()) throw Exception("No photos provided.")
        return withContext(Dispatchers.IO) {
            try {
                // 1. Determine Grid Dimensions
                val count = photoUris.size
                val cols = if (count <= 1) 1 else 2
                val rows = ceil(count.toDouble() / cols).toInt()
                val finalWidth = 1080
                val cellWidth = finalWidth / cols
                val cellHeight = cellWidth // Square cells
                val finalHeight = rows * cellHeight

                // 2. Load Bitmaps with DOWNSAMPLING (OOM Fix)
                val bitmaps = photoUris.mapNotNull {
                    loadBitmapFromUri(it, targetWidth = cellWidth, targetHeight = cellHeight)
                }

                if (bitmaps.isEmpty()) throw Exception("Could not load bitmaps.")

                // 3. Draw Collage
                val collageBitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(collageBitmap)
                canvas.drawColor(android.graphics.Color.WHITE)

                for (i in bitmaps.indices) {
                    val bitmap = bitmaps[i]
                    val col = i % cols
                    val row = i / cols
                    val left = col * cellWidth
                    val top = row * cellHeight
                    val right = left + cellWidth
                    val bottom = top + cellHeight
                    val destRect = Rect(left, top, right, bottom)
                    val srcRect = getCenterCropRect(bitmap.width, bitmap.height)
                    canvas.drawBitmap(bitmap, srcRect, destRect, null)
                    bitmap.recycle() // Release memory immediately
                }

                val newUri = saveBitmapToMediaStore(collageBitmap, title, "Pictures/Stories")
                collageBitmap.recycle()
                newUri.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Collage failed", e)
                null
            }
        }
    }

    private fun getCenterCropRect(width: Int, height: Int): Rect {
        val size = width.coerceAtMost(height)
        val x = (width - size) / 2
        val y = (height - size) / 2
        return Rect(x, y, x + size, y + size)
    }

    suspend fun getPhotoMetadata(photoUris: List<String>): String {
        if (photoUris.isEmpty()) return "No photos selected."
        return withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            photoUris.forEachIndexed { index, uriString ->
                try {
                    resolver.openInputStream(Uri.parse(uriString))?.use { inputStream ->
                        val exif = ExifInterface(inputStream)
                        val date = exif.getAttribute(ExifInterface.TAG_DATETIME) ?: "Unknown"
                        val make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: "Unknown"
                        val model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: ""
                        sb.append("Photo ${index + 1}: Date: $date, Camera: $make $model\n")
                    }
                } catch (e: Exception) { }
            }
            sb.toString()
        }
    }

    data class Album(val name: String, val coverUri: String, val photoCount: Int)

    suspend fun getAlbums(): List<Album> {
        val albums = mutableMapOf<String, Album>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media._ID, "COUNT(${MediaStore.Images.Media._ID}) AS photo_count")
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} IS NOT NULL")
            putStringArray(ContentResolver.QUERY_ARG_GROUP_COLUMNS, arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} ASC")
        }
        return withContext(Dispatchers.IO) {
            try {
                resolver.query(collection, projection, queryArgs, null)?.use { cursor ->
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val countCol = cursor.getColumnIndexOrThrow("photo_count")
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol)
                        val id = cursor.getLong(idCol)
                        val count = cursor.getInt(countCol)
                        val cover = ContentUris.withAppendedId(collection, id).toString()
                        albums[name] = Album(name, cover, count)
                    }
                }
            } catch (e: Exception) { return@withContext getAlbumsFallback() }
            albums.values.toList()
        }
    }

    private suspend fun getAlbumsFallback(): List<Album> {
        val albums = mutableMapOf<String, MutableList<String>>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        return withContext(Dispatchers.IO) {
            resolver.query(collection, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val bucket = cursor.getString(bucketCol) ?: "Unknown"
                    val uri = ContentUris.withAppendedId(collection, id).toString()
                    albums.getOrPut(bucket) { mutableListOf() }.add(uri)
                }
            }
            albums.map { (name, uris) -> Album(name, uris.first(), uris.size) }
        }
    }

    suspend fun getPhotos(page: Int, pageSize: Int): List<String> {
        val photoUris = mutableListOf<String>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Images.Media.DATE_TAKEN} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, pageSize)
            putInt(ContentResolver.QUERY_ARG_OFFSET, page * pageSize)
        }
        return withContext(Dispatchers.IO) {
            resolver.query(collection, projection, queryArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    photoUris.add(ContentUris.withAppendedId(collection, id).toString())
                }
            }
            photoUris
        }
    }

    suspend fun getPhotosForAlbum(albumName: String, page: Int, pageSize: Int): List<String> {
        val photoUris = mutableListOf<String>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(albumName))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Images.Media.DATE_TAKEN} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, pageSize)
            putInt(ContentResolver.QUERY_ARG_OFFSET, page * pageSize)
        }
        return withContext(Dispatchers.IO) {
            resolver.query(collection, projection, queryArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    photoUris.add(ContentUris.withAppendedId(collection, id).toString())
                }
            }
            photoUris
        }
    }

    private enum class FilterType { GRAYSCALE, SEPIA }

    private fun applyColorFilter(bitmap: Bitmap, filter: FilterType): Bitmap {
        val newBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(newBitmap)
        val paint = android.graphics.Paint()
        val matrix = android.graphics.ColorMatrix()
        when (filter) {
            FilterType.GRAYSCALE -> matrix.setSaturation(0f)
            FilterType.SEPIA -> {
                matrix.setSaturation(0f)
                val sepia = android.graphics.ColorMatrix().apply { setScale(1f, 0.95f, 0.82f, 1f) }
                matrix.postConcat(sepia)
            }
        }
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return newBitmap
    }

    private fun getFileName(uriString: String): String? {
        return try {
            val uri = Uri.parse(uriString)
            resolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)).substringBeforeLast(".") else null
            }
        } catch (e: Exception) { null }
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap, title: String, directory: String): Uri {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val filename = "${title.replace(" ", "_")}_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, directory)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, contentValues) ?: throw Exception("Insert failed")
        val stream = resolver.openOutputStream(uri) ?: throw Exception("Stream failed")
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        stream.close()
        contentValues.clear()
        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, contentValues, null, null)
        return uri
    }
}