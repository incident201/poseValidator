package com.example.tracker

import kotlin.math.exp

class PoseSmoother(
    private val tauMs: Float = 250f,
    private val resetAfterGapMs: Long = 1200L
) {
    private var previousPose: PoseLandmarks? = null
    private var previousTimestampMs: Long? = null

    fun reset() {
        previousPose = null
        previousTimestampMs = null
    }

    fun smooth(raw: PoseLandmarks, timestampMs: Long): PoseLandmarks {
        if (raw.allLandmarks.isEmpty()) {
            reset()
            return raw
        }

        val previous = previousPose
        val previousTs = previousTimestampMs

        if (previous == null || previousTs == null || timestampMs - previousTs > resetAfterGapMs) {
            previousPose = raw
            previousTimestampMs = timestampMs
            return raw
        }

        val dtMs = (timestampMs - previousTs).coerceAtLeast(1L).toFloat()
        val alpha = (1f - exp(-dtMs / tauMs)).coerceIn(0.05f, 1f)

        val count = minOf(raw.allLandmarks.size, previous.allLandmarks.size)
        val smoothedAll = raw.allLandmarks.mapIndexed { index, current ->
            if (index >= count) {
                current
            } else {
                val old = previous.allLandmarks[index]
                Point3D(
                    x = old.x + alpha * (current.x - old.x),
                    y = old.y + alpha * (current.y - old.y),
                    z = old.z + alpha * (current.z - old.z),
                    visibility = current.visibility,
                    presence = current.presence
                )
            }
        }

        val smoothed = PoseLandmarks.fromAllLandmarks(smoothedAll)
        previousPose = smoothed
        previousTimestampMs = timestampMs
        return smoothed
    }
}
