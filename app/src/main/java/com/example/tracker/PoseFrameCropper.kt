package com.example.tracker

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.max

object PoseFrameCropper {
    private const val TAG = "PoseFrameCropper"
    data class PoseCropRect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    fun calculateCropRect(
        bitmapWidth: Int,
        bitmapHeight: Int,
        pose: PoseLandmarks,
        paddingFactor: Float = 0.30f,
        minPaddingPx: Int = 32
    ): PoseCropRect? {
        val points = pose.allLandmarks.mapNotNull { point ->
            if (!point.x.isFinite() || !point.y.isFinite()) return@mapNotNull null
            val normalizedX = point.x.coerceIn(0f, 1f)
            val normalizedY = point.y.coerceIn(0f, 1f)
            Pair(normalizedX * bitmapWidth, normalizedY * bitmapHeight)
        }

        if (points.size < 6) return null

        val minX = points.minOf { it.first }
        val minY = points.minOf { it.second }
        val maxX = points.maxOf { it.first }
        val maxY = points.maxOf { it.second }
        val bboxWidth = maxX - minX
        val bboxHeight = maxY - minY
        if (bboxWidth <= 1f || bboxHeight <= 1f) return null

        val padX = max(minPaddingPx.toFloat(), bboxWidth * paddingFactor)
        val padY = max(minPaddingPx.toFloat(), bboxHeight * paddingFactor)
        val left = (minX - padX).toInt().coerceIn(0, bitmapWidth - 1)
        val top = (minY - padY).toInt().coerceIn(0, bitmapHeight - 1)
        val right = (maxX + padX).toInt().coerceIn(left + 1, bitmapWidth)
        val bottom = (maxY + padY).toInt().coerceIn(top + 1, bitmapHeight)

        val cropWidth = right - left
        val cropHeight = bottom - top
        if (cropWidth <= 1 || cropHeight <= 1) return null

        return PoseCropRect(left, top, right, bottom)
    }

    fun cropAroundPose(
        bitmap: Bitmap,
        pose: PoseLandmarks,
        paddingFactor: Float = 0.30f,
        minPaddingPx: Int = 32
    ): Bitmap {
        val rect = calculateCropRect(
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
            pose = pose,
            paddingFactor = paddingFactor,
            minPaddingPx = minPaddingPx
        ) ?: return bitmap

        Log.d(TAG, "crop rect: left=${rect.left} top=${rect.top} width=${rect.width} height=${rect.height}")
        return Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
    }
}
