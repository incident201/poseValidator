package com.example.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseSmootherTest {
    @Test
    fun `static jitter is dampened`() {
        val smoother = PoseSmoother()
        val rawXValues = listOf(0.40f, 0.46f, 0.39f, 0.47f, 0.40f)

        val filteredXValues = rawXValues.mapIndexed { index, x ->
            smoother.smooth(poseWithLeftElbowX(x), timestampMs = index * 250L)
                .allLandmarks[13]
                .x
        }

        val rawAmplitude = rawXValues.maxOrNull()!! - rawXValues.minOrNull()!!
        val filteredAmplitude = filteredXValues.maxOrNull()!! - filteredXValues.minOrNull()!!

        assertTrue(
            "Expected filtered amplitude $filteredAmplitude to be at least 35% lower than raw amplitude $rawAmplitude",
            filteredAmplitude <= rawAmplitude * 0.65f
        )
    }

    @Test
    fun `real movement is followed`() {
        val smoother = PoseSmoother()
        val rawXValues = listOf(
            0.40f,
            0.40f,
            0.40f,
            0.45f,
            0.50f,
            0.55f,
            0.60f
        )

        val filteredXValues = rawXValues.mapIndexed { index, x ->
            smoother.smooth(poseWithLeftElbowX(x), timestampMs = index * 250L)
                .allLandmarks[13]
                .x
        }

        val movementFilteredXValues = filteredXValues.drop(2)
        assertTrue(
            "Expected filtered movement to be monotonic, but got $movementFilteredXValues",
            movementFilteredXValues.zipWithNext().all { (previous, current) ->
                current >= previous - 0.0001f
            }
        )
        assertTrue(
            "Expected final filtered x ${filteredXValues.last()} to approach raw x ${rawXValues.last()}",
            kotlin.math.abs(filteredXValues.last() - rawXValues.last()) < 0.12f
        )
    }

    @Test
    fun `reset clears history`() {
        val smoother = PoseSmoother()

        smoother.smooth(poseWithLeftElbowX(0.20f), timestampMs = 0L)
        smoother.smooth(poseWithLeftElbowX(0.20f), timestampMs = 250L)
        smoother.reset()

        val resultAfterReset = smoother.smooth(poseWithLeftElbowX(0.80f), timestampMs = 500L)

        assertEquals(0.80f, resultAfterReset.allLandmarks[13].x, 0.0001f)
    }

    private fun poseWithLeftElbowX(x: Float): PoseLandmarks {
        val all = MutableList(33) { index ->
            Point3D(
                x = 0.5f + index * 0.001f,
                y = 0.5f,
                z = 0f,
                visibility = 0.9f,
                presence = 0.8f
            )
        }
        all[13] = Point3D(
            x = x,
            y = 0.35f,
            z = 0f,
            visibility = 0.7f,
            presence = 0.6f
        )
        return PoseLandmarks.fromAllLandmarks(all)
    }
}
