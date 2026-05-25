package com.example.tracker

import android.util.Log
import kotlin.math.sqrt

data class Point3D(val x: Float, val y: Float, val z: Float) {
    fun distanceTo(other: Point3D): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}

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
}

class MovementTracker {
    private val TAG = "MovementTracker"

    // Configuration / Thresholds from TZ Section 8
    // Thresholds are normalized relative to bodyScale
    var driftThresholdFactor: Float = 0.46f
    var motionThresholdFactor: Float = 0.32f

    var referencePose: PoseLandmarks? = null
    var previousPose: PoseLandmarks? = null

    // Track state times for breaches
    private var driftExceededSince: Long? = null
    private var motionExceededSince: Long? = null

    sealed class Violation {
        object None : Violation()
        data class DriftLimitExceeded(val score: Float, val threshold: Float) : Violation()
        data class MotionLimitExceeded(val score: Float, val threshold: Float) : Violation()
        object PersonDisappeared : Violation()
    }

    fun startTracking(pose: PoseLandmarks) {
        referencePose = pose
        previousPose = pose
        driftExceededSince = null
        motionExceededSince = null
        Log.i(TAG, "Started tracking with body scale = ${pose.getBodyScale()}")
    }

    fun reset() {
        referencePose = null
        previousPose = null
        driftExceededSince = null
        motionExceededSince = null
    }

    fun trackFrame(currentPose: PoseLandmarks, currentTime: Long = System.currentTimeMillis()): Violation {
        val ref = referencePose ?: return Violation.None
        val prev = previousPose ?: return Violation.None

        val scale = currentPose.getBodyScale()
        val driftThreshold = driftThresholdFactor * scale
        val motionThreshold = motionThresholdFactor * scale

        // Check if person is valid (or vanished completely if major keypoints are missing)
        val keypointsPresent = countKeypoints(currentPose)
        if (!currentPose.hasEnoughKeypoints()) {
            Log.w(TAG, "Keypoints disappeared or insufficient. Count = $keypointsPresent")
            return Violation.PersonDisappeared
        }

        // Calculate scores
        val driftScore = calculateDisplacement(currentPose, ref)
        val motionScore = calculateDisplacement(currentPose, prev)

        // Drift checks
        if (driftScore > driftThreshold) {
            if (driftExceededSince == null) {
                driftExceededSince = currentTime
            } else if (currentTime - driftExceededSince!! > 1500) {
                Log.w(TAG, "Drift breach! Score: $driftScore, Threshold: $driftThreshold")
                return Violation.DriftLimitExceeded(driftScore, driftThreshold)
            }
        } else {
            driftExceededSince = null
        }

        // Motion checks
        if (motionScore > motionThreshold) {
            if (motionExceededSince == null) {
                motionExceededSince = currentTime
            } else if (currentTime - motionExceededSince!! > 900) {
                Log.w(TAG, "Motion breach! Score: $motionScore, Threshold: $motionThreshold")
                return Violation.MotionLimitExceeded(motionScore, motionThreshold)
            }
        } else {
            motionExceededSince = null
        }

        previousPose = currentPose
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

    private fun calculateDisplacement(pose1: PoseLandmarks, pose2: PoseLandmarks): Float {
        var totalDistance = 0f
        var nodeCount = 0

        fun addDist(p1: Point3D?, p2: Point3D?) {
            if (p1 != null && p2 != null) {
                totalDistance += p1.distanceTo(p2)
                nodeCount++
            }
        }

        addDist(pose1.leftShoulder, pose2.leftShoulder)
        addDist(pose1.rightShoulder, pose2.rightShoulder)
        addDist(pose1.leftElbow, pose2.leftElbow)
        addDist(pose1.rightElbow, pose2.rightElbow)
        addDist(pose1.leftHip, pose2.leftHip)
        addDist(pose1.rightHip, pose2.rightHip)
        addDist(pose1.leftKnee, pose2.leftKnee)
        addDist(pose1.rightKnee, pose2.rightKnee)

        return if (nodeCount > 0) totalDistance / nodeCount else 0f
    }
}
