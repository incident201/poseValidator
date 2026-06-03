package com.example.tracker

import kotlin.math.PI
import kotlin.math.abs

class PoseSmoother(
    private var minCutoff: Float = DEFAULT_MIN_CUTOFF,
    private var beta: Float = DEFAULT_BETA,
    private var derivativeCutoff: Float = DEFAULT_DERIVATIVE_CUTOFF
) {
    private val resetAfterGapMs = 1200L

    private val landmarkFilters = mutableMapOf<Int, LandmarkFilter>()
    private var previousTimestampMs: Long? = null

    fun reset() {
        landmarkFilters.clear()
        previousTimestampMs = null
    }

    fun updateConfig(minCutoff: Float, beta: Float, derivativeCutoff: Float) {
        val normalizedMinCutoff = minCutoff.coerceIn(MIN_CUTOFF_RANGE, MAX_CUTOFF_RANGE)
        val normalizedBeta = beta.coerceIn(MIN_BETA_RANGE, MAX_BETA_RANGE)
        val normalizedDerivativeCutoff = derivativeCutoff.coerceIn(MIN_CUTOFF_RANGE, MAX_CUTOFF_RANGE)
        if (this.minCutoff == normalizedMinCutoff &&
            this.beta == normalizedBeta &&
            this.derivativeCutoff == normalizedDerivativeCutoff
        ) {
            return
        }
        this.minCutoff = normalizedMinCutoff
        this.beta = normalizedBeta
        this.derivativeCutoff = normalizedDerivativeCutoff
        reset()
    }

    fun smooth(raw: PoseLandmarks, timestampMs: Long): PoseLandmarks {
        if (raw.allLandmarks.isEmpty()) {
            reset()
            return raw
        }

        val previousTs = previousTimestampMs
        if (previousTs == null) {
            val initializedAll = initializeFilters(raw)
            previousTimestampMs = timestampMs
            return PoseLandmarks.fromAllLandmarks(initializedAll)
        }

        if (timestampMs - previousTs > resetAfterGapMs) {
            reset()
            val initializedAll = initializeFilters(raw)
            previousTimestampMs = timestampMs
            return PoseLandmarks.fromAllLandmarks(initializedAll)
        }

        val dtSeconds = ((timestampMs - previousTs).coerceAtLeast(1L) / 1000f)
            .coerceIn(1f / 120f, 0.25f)
        val smoothedAll = raw.allLandmarks.mapIndexed { index, point ->
            landmarkFilters.getOrPut(index) { newLandmarkFilter() }.filter(point, dtSeconds)
        }

        previousTimestampMs = timestampMs
        return PoseLandmarks.fromAllLandmarks(smoothedAll)
    }

    private fun initializeFilters(raw: PoseLandmarks): List<Point3D> {
        return raw.allLandmarks.mapIndexed { index, point ->
            landmarkFilters.getOrPut(index) { newLandmarkFilter() }.filter(point, 1f / 120f)
        }
    }

    private fun newLandmarkFilter(): LandmarkFilter {
        return LandmarkFilter(
            minCutoff = minCutoff,
            beta = beta,
            derivativeCutoff = derivativeCutoff
        )
    }

    companion object {
        const val DEFAULT_MIN_CUTOFF = 0.35f
        const val DEFAULT_BETA = 0.025f
        const val DEFAULT_DERIVATIVE_CUTOFF = 1.0f
        const val MIN_CUTOFF_RANGE = 0.01f
        const val MAX_CUTOFF_RANGE = 5.0f
        const val MIN_BETA_RANGE = 0.0f
        const val MAX_BETA_RANGE = 1.0f
    }

    private class LowPassFilter {
        private var previousValue: Float? = null

        fun filter(value: Float, alpha: Float): Float {
            val previous = previousValue
            val filtered = if (previous == null) {
                value
            } else {
                alpha * value + (1f - alpha) * previous
            }
            previousValue = filtered
            return filtered
        }
    }

    private class OneEuroFilter(
        private val minCutoff: Float,
        private val beta: Float,
        private val derivativeCutoff: Float
    ) {
        private val valueFilter = LowPassFilter()
        private val derivativeFilter = LowPassFilter()
        private var previousRawValue: Float? = null

        fun filter(value: Float, dtSeconds: Float): Float {
            val previousRaw = previousRawValue
            val derivative = if (previousRaw == null) {
                0f
            } else {
                (value - previousRaw) / dtSeconds
            }
            previousRawValue = value

            val smoothedDerivative = derivativeFilter.filter(
                value = derivative,
                alpha = alpha(cutoff = derivativeCutoff, dtSeconds = dtSeconds)
            )
            val cutoff = minCutoff + beta * abs(smoothedDerivative)
            return valueFilter.filter(
                value = value,
                alpha = alpha(cutoff = cutoff, dtSeconds = dtSeconds)
            )
        }

        private fun alpha(cutoff: Float, dtSeconds: Float): Float {
            val tau = 1f / (2f * PI.toFloat() * cutoff)
            return 1f / (1f + tau / dtSeconds)
        }
    }

    private class LandmarkFilter(
        minCutoff: Float,
        beta: Float,
        derivativeCutoff: Float
    ) {
        private val xFilter = OneEuroFilter(minCutoff, beta, derivativeCutoff)
        private val yFilter = OneEuroFilter(minCutoff, beta, derivativeCutoff)
        private val zFilter = OneEuroFilter(minCutoff, beta, derivativeCutoff)

        fun filter(point: Point3D, dtSeconds: Float): Point3D {
            return Point3D(
                x = xFilter.filter(point.x, dtSeconds),
                y = yFilter.filter(point.y, dtSeconds),
                z = zFilter.filter(point.z, dtSeconds),
                visibility = point.visibility,
                presence = point.presence
            )
        }
    }
}
