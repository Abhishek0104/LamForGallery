package com.example.lamforgallery.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import coil.size.Size
import coil.transform.Transformation
import kotlin.math.max

/**
 * specific region of an image (the face) and crops/zooms into it.
 */
class FaceCropTransformation(
    private val faceLeft: Float,
    private val faceTop: Float,
    private val faceRight: Float,
    private val faceBottom: Float
) : Transformation {

    override val cacheKey: String = "face_crop_$faceLeft-$faceTop-$faceRight-$faceBottom"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = input.width
        val height = input.height

        // 1. Convert normalized coordinates back to pixel pixels
        val cx = ((faceLeft + faceRight) / 2) * width
        val cy = ((faceTop + faceBottom) / 2) * height
        val faceW = (faceRight - faceLeft) * width
        val faceH = (faceBottom - faceTop) * height

        // 2. Determine crop size (make it square, adding some padding)
        val faceSize = max(faceW, faceH)
        // Add 50% padding so it's not an extreme close-up
        val cropSize = faceSize * 1.5f

        val halfCrop = cropSize / 2

        // 3. Calculate crop rect, ensuring we stay within image bounds
        var left = (cx - halfCrop).toInt().coerceAtLeast(0)
        var top = (cy - halfCrop).toInt().coerceAtLeast(0)
        var right = (cx + halfCrop).toInt().coerceAtMost(width)
        var bottom = (cy + halfCrop).toInt().coerceAtMost(height)

        // Adjust to keep it square if near edges (simplified: just take what we can)
        val cropWidth = right - left
        val cropHeight = bottom - top
        val finalSize = kotlin.math.min(cropWidth, cropHeight)

        // Final Square Rect
        right = left + finalSize
        bottom = top + finalSize

        // 4. Crop
        val cropped = Bitmap.createBitmap(input, left, top, finalSize, finalSize)
        return cropped
    }
}