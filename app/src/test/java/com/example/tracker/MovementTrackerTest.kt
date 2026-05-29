package com.example.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MovementTrackerTest {
    @Test
    fun `identical pose produces zero drift and motion`() {
        val tracker = MovementTracker()
        val reference = referencePose()

        tracker.startTracking(reference)
        val result = tracker.trackFrame(reference, currentTime = 0L)

        assertTrue(result.violation is MovementTracker.Violation.None)
        assertEquals(0f, result.metrics.driftNormalizedScore, 0.0001f)
        assertEquals(0f, result.metrics.motionNormalizedScore, 0.0001f)
    }

    @Test
    fun `small jitter is absorbed by deadband`() {
        val tracker = MovementTracker()
        val reference = referencePose()
        val jittered = reference.translated(dx = 0.002f, dy = 0.002f)

        tracker.startTracking(reference)
        val result = tracker.trackFrame(jittered, currentTime = 0L)

        assertTrue(result.violation is MovementTracker.Violation.None)
        assertEquals(0f, result.metrics.driftNormalizedScore, 0.0001f)
        assertEquals(0f, result.metrics.motionNormalizedScore, 0.0001f)
    }

    @Test
    fun `arm pose change breaches drift after grace period`() {
        val tracker = MovementTracker()
        val reference = referencePose()
        val armsLowered = reference.withMovedLandmarks(
            13 to Point3D(0.34f, 0.56f, 0f),
            14 to Point3D(0.66f, 0.56f, 0f),
            15 to Point3D(0.32f, 0.76f, 0f),
            16 to Point3D(0.68f, 0.76f, 0f)
        )

        tracker.startTracking(reference)
        val firstResult = tracker.trackFrame(armsLowered, currentTime = 0L)
        val sustainedResult = tracker.trackFrame(armsLowered, currentTime = 1601L)

        assertTrue(firstResult.metrics.driftNormalizedScore > firstResult.metrics.driftThresholdFactor)
        assertTrue(sustainedResult.violation is MovementTracker.Violation.DriftLimitExceeded)
    }

    @Test
    fun `knee bend breaches drift threshold`() {
        val tracker = MovementTracker()
        val reference = referencePose()
        val squat = reference.withMovedLandmarks(
            25 to Point3D(0.42f, 1.20f, 0f),
            26 to Point3D(0.58f, 1.20f, 0f),
            27 to Point3D(0.39f, 1.45f, 0f),
            28 to Point3D(0.61f, 1.45f, 0f)
        )

        tracker.startTracking(reference)
        val result = tracker.trackFrame(squat, currentTime = 0L)

        assertTrue(result.metrics.driftNormalizedScore > result.metrics.driftThresholdFactor)
        assertTrue(result.violation is MovementTracker.Violation.None)
    }

    @Test
    fun `slow global drift accumulates against reference without requiring motion breach`() {
        val tracker = MovementTracker()
        val reference = referencePose()

        tracker.startTracking(reference)
        tracker.trackFrame(reference.translated(dx = 0.016f), currentTime = 0L)
        tracker.trackFrame(reference.translated(dx = 0.032f), currentTime = 500L)
        tracker.trackFrame(reference.translated(dx = 0.048f), currentTime = 1000L)
        tracker.trackFrame(reference.translated(dx = 0.064f), currentTime = 1500L)
        val driftStarted = tracker.trackFrame(reference.translated(dx = 0.088f), currentTime = 2000L)
        val driftExceeded = tracker.trackFrame(reference.translated(dx = 0.088f), currentTime = 3601L)

        assertTrue(driftStarted.metrics.driftNormalizedScore > driftStarted.metrics.driftThresholdFactor)
        assertTrue(driftStarted.metrics.motionNormalizedScore <= driftStarted.metrics.motionThresholdFactor)
        assertTrue(driftExceeded.violation is MovementTracker.Violation.DriftLimitExceeded)
    }

    @Test
    fun `abrupt pose changes breach motion after grace period`() {
        val tracker = MovementTracker()
        val reference = referencePose()
        val armsLowered = reference.withMovedLandmarks(
            13 to Point3D(0.34f, 0.56f, 0f),
            14 to Point3D(0.66f, 0.56f, 0f),
            15 to Point3D(0.32f, 0.76f, 0f),
            16 to Point3D(0.68f, 0.76f, 0f)
        )

        tracker.startTracking(reference)
        val firstResult = tracker.trackFrame(armsLowered, currentTime = 0L)
        val secondResult = tracker.trackFrame(reference, currentTime = 901L)

        assertTrue(firstResult.metrics.motionNormalizedScore > firstResult.metrics.motionThresholdFactor)
        assertTrue(secondResult.violation is MovementTracker.Violation.MotionLimitExceeded)
    }

    private fun referencePose(): PoseLandmarks {
        val all = MutableList(33) { Point3D(0.5f, 0.5f, 0f) }
        all[11] = Point3D(0.3f, 0.2f, 0f)
        all[12] = Point3D(0.7f, 0.2f, 0f)
        all[13] = Point3D(0.2f, 0.35f, 0f)
        all[14] = Point3D(0.8f, 0.35f, 0f)
        all[15] = Point3D(0.15f, 0.2f, 0f)
        all[16] = Point3D(0.85f, 0.2f, 0f)
        all[23] = Point3D(0.35f, 0.5f, 0f)
        all[24] = Point3D(0.65f, 0.5f, 0f)
        all[25] = Point3D(0.4f, 0.8f, 0f)
        all[26] = Point3D(0.6f, 0.8f, 0f)
        all[27] = Point3D(0.4f, 1.0f, 0f)
        all[28] = Point3D(0.6f, 1.0f, 0f)
        return PoseLandmarks.fromAllLandmarks(all)
    }

    private fun PoseLandmarks.translated(dx: Float = 0f, dy: Float = 0f): PoseLandmarks {
        return PoseLandmarks.fromAllLandmarks(
            allLandmarks.map { point ->
                Point3D(point.x + dx, point.y + dy, point.z)
            }
        )
    }

    private fun PoseLandmarks.withMovedLandmarks(vararg replacements: Pair<Int, Point3D>): PoseLandmarks {
        val updated = allLandmarks.toMutableList()
        replacements.forEach { (index, point) -> updated[index] = point }
        return PoseLandmarks.fromAllLandmarks(updated)
    }
}
