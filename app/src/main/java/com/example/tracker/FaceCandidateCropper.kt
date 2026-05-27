package com.example.tracker

import kotlin.math.abs
import kotlin.math.roundToInt

object FaceCandidateCropper {
    data class FaceCandidateRect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    fun calculateFaceCandidateRect(
        bitmapWidth: Int,
        bitmapHeight: Int,
        pose: PoseLandmarks,
        bodyCropRect: PoseFrameCropper.PoseCropRect
    ): FaceCandidateRect? {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return null

        val facePoints = (0..10).mapNotNull { index ->
            finitePoint(pose, index)?.toPixel(bitmapWidth, bitmapHeight)
        }
        val leftShoulder = finitePoint(pose, 11)
        val rightShoulder = finitePoint(pose, 12)

        val centerX: Float
        val centerY: Float
        val cropSize: Float

        if (facePoints.size >= 2) {
            val minX = facePoints.minOf { it.first }
            val minY = facePoints.minOf { it.second }
            val maxX = facePoints.maxOf { it.first }
            val maxY = facePoints.maxOf { it.second }
            val shoulderWidthPx = if (leftShoulder != null && rightShoulder != null) {
                abs(leftShoulder.x - rightShoulder.x) * bitmapWidth
            } else {
                bodyCropRect.width * 0.35f
            }

            cropSize = maxOf(
                (maxX - minX) * 3.2f,
                (maxY - minY) * 4.0f,
                shoulderWidthPx * 1.15f,
                160f
            )
            centerX = (minX + maxX) / 2f
            centerY = (minY + maxY) / 2f - cropSize * 0.10f
        } else if (leftShoulder != null && rightShoulder != null) {
            val shoulderMidX = ((leftShoulder.x + rightShoulder.x) / 2f) * bitmapWidth
            val shoulderY = ((leftShoulder.y + rightShoulder.y) / 2f) * bitmapHeight
            val shoulderWidthPx = abs(leftShoulder.x - rightShoulder.x) * bitmapWidth
            cropSize = maxOf(shoulderWidthPx * 1.45f, 180f)
            centerX = shoulderMidX
            centerY = shoulderY - cropSize * 0.60f
        } else {
            return null
        }

        val fittedToBody = fitSquareToBounds(
            centerX = centerX,
            centerY = centerY,
            requestedSize = cropSize,
            minLeft = bodyCropRect.left,
            minTop = bodyCropRect.top,
            maxRight = bodyCropRect.right,
            maxBottom = bodyCropRect.bottom
        ) ?: return null

        val fittedCenterX = (fittedToBody.left + fittedToBody.right) / 2f
        val fittedCenterY = (fittedToBody.top + fittedToBody.bottom) / 2f
        val fittedToBitmap = fitSquareToBounds(
            centerX = fittedCenterX,
            centerY = fittedCenterY,
            requestedSize = fittedToBody.width.toFloat(),
            minLeft = 0,
            minTop = 0,
            maxRight = bitmapWidth,
            maxBottom = bitmapHeight
        ) ?: return null

        if (fittedToBitmap.width < 96 || fittedToBitmap.height < 96) return null
        return fittedToBitmap
    }

    private fun finitePoint(pose: PoseLandmarks, index: Int): Point3D? {
        val point = pose.allLandmarks.getOrNull(index) ?: return null
        return if (point.x.isFinite() && point.y.isFinite()) point else null
    }

    private fun Point3D.toPixel(bitmapWidth: Int, bitmapHeight: Int): Pair<Float, Float> {
        val px = x.coerceIn(0f, 1f) * bitmapWidth
        val py = y.coerceIn(0f, 1f) * bitmapHeight
        return px to py
    }

    private fun fitSquareToBounds(
        centerX: Float,
        centerY: Float,
        requestedSize: Float,
        minLeft: Int,
        minTop: Int,
        maxRight: Int,
        maxBottom: Int
    ): FaceCandidateRect? {
        if (requestedSize <= 0f) return null
        if (maxRight <= minLeft || maxBottom <= minTop) return null

        val boundsWidth = maxRight - minLeft
        val boundsHeight = maxBottom - minTop
        val size = minOf(
            requestedSize.roundToInt().coerceAtLeast(1),
            boundsWidth,
            boundsHeight
        )
        if (size <= 0) return null

        var left = (centerX - size / 2f).roundToInt()
        var top = (centerY - size / 2f).roundToInt()

        left = left.coerceIn(minLeft, maxRight - size)
        top = top.coerceIn(minTop, maxBottom - size)

        val right = left + size
        val bottom = top + size

        return FaceCandidateRect(left, top, right, bottom)
    }

}
