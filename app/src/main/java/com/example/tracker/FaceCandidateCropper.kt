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

        val half = cropSize / 2f
        val rawLeft = (centerX - half).roundToInt()
        val rawTop = (centerY - half).roundToInt()
        val rawRight = (centerX + half).roundToInt()
        val rawBottom = (centerY + half).roundToInt()

        val clampedToBody = clampRectToBounds(
            rawLeft,
            rawTop,
            rawRight,
            rawBottom,
            bodyCropRect.left,
            bodyCropRect.top,
            bodyCropRect.right,
            bodyCropRect.bottom
        )
        val clampedToBitmap = clampRectToBounds(
            clampedToBody.left,
            clampedToBody.top,
            clampedToBody.right,
            clampedToBody.bottom,
            0,
            0,
            bitmapWidth,
            bitmapHeight
        )
        if (clampedToBitmap.width < 96 || clampedToBitmap.height < 96) return null
        return clampedToBitmap
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

    private fun clampRectToBounds(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        minLeft: Int,
        minTop: Int,
        maxRight: Int,
        maxBottom: Int
    ): FaceCandidateRect {
        val clampedLeft = left.coerceIn(minLeft, maxRight - 1)
        val clampedTop = top.coerceIn(minTop, maxBottom - 1)
        val clampedRight = right.coerceIn(clampedLeft + 1, maxRight)
        val clampedBottom = bottom.coerceIn(clampedTop + 1, maxBottom)
        return FaceCandidateRect(clampedLeft, clampedTop, clampedRight, clampedBottom)
    }
}
