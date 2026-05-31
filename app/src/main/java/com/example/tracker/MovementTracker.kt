package com.example.tracker

import android.util.Log
import kotlin.math.sqrt

fun Point3D.distance2DTo(other: Point3D): Float {
    val dx = x - other.x
    val dy = y - other.y
    return sqrt(dx * dx + dy * dy)
}

data class PosePoint(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float? = null,
    val presence: Float? = null
) {
    fun distanceTo(other: Point3D): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}

typealias Point3D = PosePoint

data class PoseLandmarks(
    val leftShoulder: Point3D? = null,
    val rightShoulder: Point3D? = null,
    val leftElbow: Point3D? = null,
    val rightElbow: Point3D? = null,
    val leftHip: Point3D? = null,
    val rightHip: Point3D? = null,
    val leftKnee: Point3D? = null,
    val rightKnee: Point3D? = null,
    val allLandmarks: List<Point3D> = emptyList(),
) {
    fun hasEnoughKeypoints(): Boolean {
        return leftShoulder != null &&
            rightShoulder != null &&
            leftElbow != null &&
            rightElbow != null &&
            leftHip != null &&
            rightHip != null &&
            leftKnee != null &&
            rightKnee != null
    }

    fun getBodyScale(): Float {
        if (leftShoulder != null && rightShoulder != null) {
            val dist = leftShoulder.distanceTo(rightShoulder)
            if (dist > 0.01f) return dist
        }
        if (leftHip != null && rightHip != null) {
            val dist = leftHip.distanceTo(rightHip)
            if (dist > 0.01f) return dist
        }
        return 0.3f // Safe fallback normalization scale
    }

    companion object {
        fun fromAllLandmarks(all: List<Point3D>): PoseLandmarks {
            return PoseLandmarks(
                leftShoulder = all.getOrNull(11),
                rightShoulder = all.getOrNull(12),
                leftElbow = all.getOrNull(13),
                rightElbow = all.getOrNull(14),
                leftHip = all.getOrNull(23),
                rightHip = all.getOrNull(24),
                leftKnee = all.getOrNull(25),
                rightKnee = all.getOrNull(26),
                allLandmarks = all
            )
        }
    }
}

fun PoseLandmarks.landmark(index: Int): Point3D? = allLandmarks.getOrNull(index)

data class MovementMetrics(
    val active: Boolean = false,
    val driftNormalizedScore: Float = 0f,
    val motionNormalizedScore: Float = 0f,
    val driftThresholdFactor: Float = 0f,
    val motionThresholdFactor: Float = 0f
)

data class TrackingResult(
    val violation: MovementTracker.Violation,
    val metrics: MovementMetrics
)

class MovementTracker {
    private val TAG = "MovementTracker"

    // Thresholds are normalized relative to the fixed reference pose scale.
    var driftThresholdFactor: Float = 0.12f
    var motionThresholdFactor: Float = 0.06f

    var referencePose: PoseLandmarks? = null
    var previousPose: PoseLandmarks? = null

    private var referenceCenter: Point3D? = null
    private var referenceScale: Float = 0.3f
    private var referenceNormalizedPose: Map<Int, Point3D> = emptyMap()
    private var previousNormalizedPose: Map<Int, Point3D>? = null
    private var previousCenter: Point3D? = null

    private val trackedPoseIndices = listOf(
        11, 12, // shoulders
        13, 14, // elbows
        15, 16, // wrists
        23, 24, // hips
        25, 26, // knees
        27, 28 // ankles
    )

    private val driftDeadband = 0.025f
    private val motionDeadband = 0.015f
    private val globalDriftWeight = 1.0f
    private val motionImmediateSpikeMultiplier = 2.5f

    // Track state times for breaches
    private var motionExceededSince: Long? = null
    private var driftBreachAccumulatedMs: Long = 0L
    private var lastDriftCheckTimeMs: Long? = null

    private val driftGraceMs = 1500L
    private val driftResetFactor = 0.85f
    private val driftDecayMultiplier = 0.5f

    sealed class Violation {
        object None : Violation()
        data class DriftLimitExceeded(val score: Float, val threshold: Float) : Violation()
        data class MotionLimitExceeded(val score: Float, val threshold: Float) : Violation()
        object PersonDisappeared : Violation()
    }

    fun startTracking(pose: PoseLandmarks) {
        referencePose = pose
        referenceCenter = pose.bodyCenter2D()
        referenceScale = pose.getBodyScale2D().coerceAtLeast(0.001f)
        referenceNormalizedPose = referenceCenter?.let { normalizedPoseMap(pose, it, referenceScale) }.orEmpty()
        previousPose = pose
        previousNormalizedPose = referenceNormalizedPose
        previousCenter = referenceCenter
        driftBreachAccumulatedMs = 0L
        lastDriftCheckTimeMs = null
        motionExceededSince = null
        Log.i(TAG, "Started tracking with 2D reference scale = $referenceScale")
    }

    fun reset() {
        referencePose = null
        previousPose = null
        referenceCenter = null
        referenceScale = 0.3f
        referenceNormalizedPose = emptyMap()
        previousNormalizedPose = null
        previousCenter = null
        driftBreachAccumulatedMs = 0L
        lastDriftCheckTimeMs = null
        motionExceededSince = null
    }

    fun trackFrame(currentPose: PoseLandmarks, currentTime: Long = System.currentTimeMillis()): TrackingResult {
        val ref = referencePose ?: return TrackingResult(Violation.None, MovementMetrics())
        val refCenter = referenceCenter
        val currentCenter = currentPose.bodyCenter2D()
        val prevNormalized = previousNormalizedPose
        val prevCenter = previousCenter

        // Check if person is valid (or vanished completely if major keypoints are missing)
        val keypointsPresent = countKeypoints(currentPose)
        if (!currentPose.hasEnoughKeypoints() || refCenter == null || currentCenter == null) {
            Log.w(TAG, "Keypoints disappeared or insufficient. Count = $keypointsPresent")
            return TrackingResult(Violation.PersonDisappeared, MovementMetrics())
        }

        val currentNormalizedPose = normalizedPoseMap(currentPose, currentCenter, referenceScale)
        val refNormalized = if (referenceNormalizedPose.isNotEmpty()) {
            referenceNormalizedPose
        } else {
            normalizedPoseMap(ref, refCenter, referenceScale)
        }

        val poseDistances = trackedPoseIndices.mapNotNull { index ->
            val current = currentNormalizedPose[index] ?: return@mapNotNull null
            val reference = refNormalized[index] ?: return@mapNotNull null
            current.distance2DTo(reference)
        }
        val poseDrift = robustShapeScore(poseDistances)
        val globalDrift = currentCenter.distance2DTo(refCenter) / referenceScale
        val driftRaw = maxOf(poseDrift, globalDrift * globalDriftWeight)
        val driftScore = applyDeadband(driftRaw, driftDeadband)

        val poseMotion = if (prevNormalized == null) {
            0f
        } else {
            val motionDistances = trackedPoseIndices.mapNotNull { index ->
                val current = currentNormalizedPose[index] ?: return@mapNotNull null
                val previous = prevNormalized[index] ?: return@mapNotNull null
                current.distance2DTo(previous)
            }
            robustShapeScore(motionDistances)
        }
        val globalMotion = if (prevCenter == null) 0f else currentCenter.distance2DTo(prevCenter) / referenceScale
        val motionRaw = maxOf(poseMotion, globalMotion)
        val motionScore = applyDeadband(motionRaw, motionDeadband)

        val violation = evaluateViolation(driftScore, motionScore, currentTime)

        previousPose = currentPose
        previousNormalizedPose = currentNormalizedPose
        previousCenter = currentCenter

        return TrackingResult(
            violation = violation,
            metrics = MovementMetrics(
                active = true,
                driftNormalizedScore = driftScore,
                motionNormalizedScore = motionScore,
                driftThresholdFactor = driftThresholdFactor,
                motionThresholdFactor = motionThresholdFactor
            )
        )
    }

    private fun evaluateViolation(driftScore: Float, motionScore: Float, currentTime: Long): Violation {
        val previousDriftCheckTime = lastDriftCheckTimeMs
        val driftDtMs = if (previousDriftCheckTime == null) {
            0L
        } else {
            (currentTime - previousDriftCheckTime).coerceIn(0L, 250L)
        }
        lastDriftCheckTimeMs = currentTime

        val driftEnterThreshold = driftThresholdFactor
        val driftResetThreshold = driftThresholdFactor * driftResetFactor

        if (driftScore > driftEnterThreshold) {
            driftBreachAccumulatedMs += driftDtMs
        } else if (driftScore < driftResetThreshold) {
            driftBreachAccumulatedMs = 0L
        } else {
            val decayMs = (driftDtMs * driftDecayMultiplier).toLong()
            driftBreachAccumulatedMs = (driftBreachAccumulatedMs - decayMs).coerceAtLeast(0L)
        }

        if (driftBreachAccumulatedMs > driftGraceMs) {
            Log.w(
                TAG,
                "Drift breach! Score: $driftScore, Threshold: $driftThresholdFactor, " +
                    "AccumulatedMs: $driftBreachAccumulatedMs"
            )
            return Violation.DriftLimitExceeded(driftScore, driftThresholdFactor)
        }

        if (motionScore >= motionThresholdFactor * motionImmediateSpikeMultiplier) {
            motionExceededSince = null
            Log.w(TAG, "Immediate motion breach! Score: $motionScore, Threshold: $motionThresholdFactor")
            return Violation.MotionLimitExceeded(motionScore, motionThresholdFactor)
        }

        if (motionScore > motionThresholdFactor) {
            if (motionExceededSince == null) {
                motionExceededSince = currentTime
            } else if (currentTime - motionExceededSince!! > 900) {
                Log.w(TAG, "Motion breach! Score: $motionScore, Threshold: $motionThresholdFactor")
                return Violation.MotionLimitExceeded(motionScore, motionThresholdFactor)
            }
        } else {
            motionExceededSince = null
        }

        return Violation.None
    }

    private fun countKeypoints(pose: PoseLandmarks): Int {
        var count = 0
        if (pose.leftShoulder != null) count++
        if (pose.rightShoulder != null) count++
        if (pose.leftElbow != null) count++
        if (pose.rightElbow != null) count++
        if (pose.leftHip != null) count++
        if (pose.rightHip != null) count++
        if (pose.leftKnee != null) count++
        if (pose.rightKnee != null) count++
        return count
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

        return candidates.filter { it > 0.01f }.maxOrNull() ?: 0.3f
    }

    private fun normalizedPoseMap(pose: PoseLandmarks, center: Point3D, scale: Float): Map<Int, Point3D> {
        val safeScale = scale.coerceAtLeast(0.001f)
        return trackedPoseIndices.mapNotNull { index ->
            val p = pose.landmark(index) ?: return@mapNotNull null
            index to Point3D(
                x = (p.x - center.x) / safeScale,
                y = (p.y - center.y) / safeScale,
                z = 0f
            )
        }.toMap()
    }

    private fun robustMean(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        if (values.size <= 4) return values.average().toFloat()

        val sorted = values.sorted()
        val trimCount = 1
        val trimmed = sorted.drop(trimCount).dropLast(trimCount)
        return trimmed.average().toFloat()
    }

    private fun robustShapeScore(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        if (values.size == 1) return values.first()

        val sorted = values.sorted()
        val trimmedMean = robustMean(values)

        // Ignore a single isolated outlier, but catch local limb changes
        // where at least two landmarks move together, e.g. elbow + wrist or knee + ankle.
        val secondLargest = sorted[sorted.lastIndex - 1]

        return maxOf(trimmedMean, secondLargest)
    }

    private fun applyDeadband(value: Float, deadband: Float): Float {
        return (value - deadband).coerceAtLeast(0f)
    }
}
