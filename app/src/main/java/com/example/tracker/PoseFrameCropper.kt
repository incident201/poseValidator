package com.example.tracker

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.max

object PoseFrameCropper {
    private const val TAG = "PoseFrameCropper"

    fun cropAroundPose(
        bitmap: Bitmap,
        pose: PoseLandmarks,
        paddingFactor: Float = 0.30f,
        minPaddingPx: Int = 32
    ): Bitmap {
        val points = pose.allLandmarks.mapNotNull { point ->
            if (!point.x.isFinite() || !point.y.isFinite()) return@mapNotNull null
            val normalizedX = point.x.coerceIn(0f, 1f)
            val normalizedY = point.y.coerceIn(0f, 1f)
            Pair(normalizedX * bitmap.width, normalizedY * bitmap.height)
        }

        if (points.size < 6) return bitmap

        val minX = points.minOf { it.first }
        val minY = points.minOf { it.second }
        val maxX = points.maxOf { it.first }
        val maxY = points.maxOf { it.second }

        val bboxWidth = maxX - minX
        val bboxHeight = maxY - minY

        if (bboxWidth <= 1f || bboxHeight <= 1f) return bitmap

        val padX = max(minPaddingPx.toFloat(), bboxWidth * paddingFactor)
        val padY = max(minPaddingPx.toFloat(), bboxHeight * paddingFactor)

        val left = (minX - padX).toInt().coerceIn(0, bitmap.width - 1)
        val top = (minY - padY).toInt().coerceIn(0, bitmap.height - 1)
        val right = (maxX + padX).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (maxY + padY).toInt().coerceIn(top + 1, bitmap.height)

        val cropWidth = right - left
        val cropHeight = bottom - top

        if (cropWidth <= 1 || cropHeight <= 1) return bitmap

        Log.d(TAG, "crop rect: left=$left top=$top width=$cropWidth height=$cropHeight")
        return Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
    }
}
