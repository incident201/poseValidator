package com.example.tracker

import android.util.Log
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt

private const val CALIBRATION_WINDOW_MS = 2_000L

private const val FREEZE_VISIBILITY_HARD = 0.01f
private const val FREEZE_VISIBILITY_SOFT = 0.03f

private const val REACQUIRE_VISIBILITY = 0.12f
private const val DROP_BACK_TO_FROZEN_VISIBILITY = 0.03f

private const val JITTER_FREEZE_THRESHOLD = 0.06f
private const val REACQUIRE_STABILITY_DELTA = 0.04f
private const val REACQUIRE_STABLE_FRAMES = 5

class PoseOcclusionGuard {
    private val tag = "PoseOcclusionGuard"

    private val guardedIndices = listOf(
        13, 14, // elbows
        15, 16, // wrists
        25, 26, // knees
        27, 28 // ankles
    )

    private val calibrationFrames = ArrayDeque<CalibrationFrame>()
    private val frozenLandmarks = linkedMapOf<Int, FrozenLandmark>()

    data class FrozenLandmark(
        val index: Int,
        val localX: Float,
        val localY: Float,
        val localZ: Float,
        var stableVisibleFrames: Int = 0,
        var lastVisibleLocalX: Float? = null,
        var lastVisibleLocalY: Float? = null,
        var useRawTracking: Boolean = false
    )

    fun reset() {
        calibrationFrames.clear()
        frozenLandmarks.clear()
    }

    fun addCalibrationFrame(pose: PoseLandmarks, timestampMs: Long) {
        calibrationFrames.addLast(CalibrationFrame(pose, timestampMs))
        val minTimestampMs = timestampMs - CALIBRATION_WINDOW_MS
        while (calibrationFrames.firstOrNull()?.timestampMs?.let { it < minTimestampMs } == true) {
            calibrationFrames.removeFirst()
        }
    }

    fun finishCalibration(referencePose: PoseLandmarks) {
        frozenLandmarks.clear()
        val frames = calibrationFrames.map { it.pose }.ifEmpty { listOf(referencePose) }

        guardedIndices.forEach { index ->
            val samples = frames.mapNotNull { pose -> pose.localSample(index) }
            if (samples.isEmpty()) return@forEach

            val medianVisibility = samples.map { it.visibility }.median()
            val p10Visibility = samples.map { it.visibility }.percentile(0.10f)
            val medianLocalX = samples.map { it.localX }.median()
            val medianLocalY = samples.map { it.localY }.median()
            val localZ = samples.map { it.localZ }.median()
            val jitter = samples
                .map { distance2D(it.localX, it.localY, medianLocalX, medianLocalY) }
                .percentile(0.90f)

            val veryLowVisibility = medianVisibility < FREEZE_VISIBILITY_HARD
            val lowVisibility = medianVisibility < FREEZE_VISIBILITY_SOFT || p10Visibility < FREEZE_VISIBILITY_HARD
            val unstable = jitter > JITTER_FREEZE_THRESHOLD
            val shouldFreeze = unstable && (veryLowVisibility || lowVisibility)

            if (shouldFreeze) {
                frozenLandmarks[index] = FrozenLandmark(
                    index = index,
                    localX = medianLocalX,
                    localY = medianLocalY,
                    localZ = localZ
                )
                Log.i(
                    tag,
                    "frozen index=$index medianVis=${medianVisibility.format(4)} " +
                        "p10Vis=${p10Visibility.format(4)} jitter=${jitter.format(3)}"
                )
            }
        }
    }

    fun buildReferencePose(referencePose: PoseLandmarks): PoseLandmarks {
        if (frozenLandmarks.isEmpty()) return referencePose
        return referencePose.withProjectedFrozenLandmarks(frozenLandmarks.values)
    }

    @Suppress("UNUSED_PARAMETER")
    fun applyForTracking(pose: PoseLandmarks, timestampMs: Long): PoseLandmarks {
        if (frozenLandmarks.isEmpty()) return pose

        val center = pose.bodyCenter2D() ?: return pose
        val scale = pose.getBodyScale2D()
        val updatedLandmarks = pose.allLandmarks.toMutableList()
        var changed = false

        frozenLandmarks.values.forEach { frozen ->
            val rawPoint = pose.landmark(frozen.index)
            val rawVisibility = rawPoint?.visibility ?: 0f

            if (frozen.useRawTracking) {
                if (rawVisibility < DROP_BACK_TO_FROZEN_VISIBILITY) {
                    frozen.useRawTracking = false
                    frozen.stableVisibleFrames = 0
                    frozen.lastVisibleLocalX = null
                    frozen.lastVisibleLocalY = null
                    updatedLandmarks.setProjectedFrozen(frozen.index, frozen.project(center, scale, rawPoint))
                    changed = true
                }
                return@forEach
            }

            if (rawPoint == null || rawVisibility < REACQUIRE_VISIBILITY) {
                frozen.stableVisibleFrames = 0
                frozen.lastVisibleLocalX = null
                frozen.lastVisibleLocalY = null
                updatedLandmarks.setProjectedFrozen(frozen.index, frozen.project(center, scale, rawPoint))
                changed = true
                return@forEach
            }

            val rawLocalX = (rawPoint.x - center.x) / scale
            val rawLocalY = (rawPoint.y - center.y) / scale
            val previousLocalX = frozen.lastVisibleLocalX
            val previousLocalY = frozen.lastVisibleLocalY
            if (previousLocalX != null && previousLocalY != null &&
                distance2D(rawLocalX, rawLocalY, previousLocalX, previousLocalY) < REACQUIRE_STABILITY_DELTA
            ) {
                frozen.stableVisibleFrames += 1
            } else {
                frozen.stableVisibleFrames = 1
            }
            frozen.lastVisibleLocalX = rawLocalX
            frozen.lastVisibleLocalY = rawLocalY

            if (frozen.stableVisibleFrames >= REACQUIRE_STABLE_FRAMES) {
                frozen.useRawTracking = true
            } else {
                updatedLandmarks.setProjectedFrozen(frozen.index, frozen.project(center, scale, rawPoint))
                changed = true
            }
        }

        return if (changed) PoseLandmarks.fromAllLandmarks(updatedLandmarks) else pose
    }

    private fun PoseLandmarks.withProjectedFrozenLandmarks(frozen: Collection<FrozenLandmark>): PoseLandmarks {
        val center = bodyCenter2D() ?: return this
        val scale = getBodyScale2D()
        val updatedLandmarks = allLandmarks.toMutableList()
        var changed = false
        frozen.forEach { frozenLandmark ->
            updatedLandmarks.setProjectedFrozen(
                frozenLandmark.index,
                frozenLandmark.project(center, scale, landmark(frozenLandmark.index))
            )
            changed = true
        }
        return if (changed) PoseLandmarks.fromAllLandmarks(updatedLandmarks) else this
    }

    private fun MutableList<Point3D>.setProjectedFrozen(index: Int, point: Point3D) {
        while (size <= index) {
            add(Point3D(0f, 0f, 0f, visibility = 0f, presence = 0f))
        }
        this[index] = point
    }

    private fun FrozenLandmark.project(center: Point3D, scale: Float, originalPoint: Point3D?): Point3D {
        return Point3D(
            x = center.x + localX * scale,
            y = center.y + localY * scale,
            z = localZ,
            visibility = originalPoint?.visibility ?: 0f,
            presence = originalPoint?.presence ?: 0f
        )
    }

    private fun PoseLandmarks.localSample(index: Int): LocalSample? {
        val center = bodyCenter2D() ?: return null
        val scale = getBodyScale2D()
        val point = landmark(index) ?: return null
        return LocalSample(
            localX = (point.x - center.x) / scale,
            localY = (point.y - center.y) / scale,
            localZ = point.z,
            visibility = point.visibility ?: 0f
        )
    }

    private fun PoseLandmarks.bodyCenter2D(): Point3D? {
        val lh = leftHip
        val rh = rightHip
        if (lh != null && rh != null) {
            return Point3D((lh.x + rh.x) / 2f, (lh.y + rh.y) / 2f, 0f)
        }

        val ls = leftShoulder
        val rs = rightShoulder
        if (ls != null && rs != null) {
            return Point3D((ls.x + rs.x) / 2f, (ls.y + rs.y) / 2f, 0f)
        }

        return null
    }

    private fun PoseLandmarks.getBodyScale2D(): Float {
        val candidates = mutableListOf<Float>()

        if (leftShoulder != null && rightShoulder != null) {
            candidates += leftShoulder.distance2DTo(rightShoulder)
        }

        if (leftHip != null && rightHip != null) {
            candidates += leftHip.distance2DTo(rightHip)
        }

        val shoulderCenter =
            if (leftShoulder != null && rightShoulder != null) {
                Point3D((leftShoulder.x + rightShoulder.x) / 2f, (leftShoulder.y + rightShoulder.y) / 2f, 0f)
            } else {
                null
            }

        val hipCenter =
            if (leftHip != null && rightHip != null) {
                Point3D((leftHip.x + rightHip.x) / 2f, (leftHip.y + rightHip.y) / 2f, 0f)
            } else {
                null
            }

        if (shoulderCenter != null && hipCenter != null) {
            candidates += shoulderCenter.distance2DTo(hipCenter)
        }

        return (candidates.filter { it > 0.01f }.maxOrNull() ?: 0.3f).coerceAtLeast(0.001f)
    }

    private fun List<Float>.median(): Float {
        if (isEmpty()) return 0f
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2f
        } else {
            sorted[middle]
        }
    }

    private fun List<Float>.percentile(percentile: Float): Float {
        if (isEmpty()) return 0f
        val sorted = sorted()
        if (sorted.size == 1) return sorted.first()
        val clamped = percentile.coerceIn(0f, 1f)
        val index = (ceil(clamped * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun Float.format(decimals: Int): String = "% .${decimals}f".format(Locale.US, this).trim()

    private fun distance2D(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    private data class CalibrationFrame(val pose: PoseLandmarks, val timestampMs: Long)

    private data class LocalSample(
        val localX: Float,
        val localY: Float,
        val localZ: Float,
        val visibility: Float
    )
}
