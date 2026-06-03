package com.example.tracker

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseIdentityStabilizerTest {
    @Test
    fun `first valid frame returns Direct and stores state`() {
        val stabilizer = PoseIdentityStabilizer()

        val firstResult = stabilizer.stabilize(basePose(), timestampMs = 0L)
        val secondResult = stabilizer.stabilize(globallySwapped(basePose()), timestampMs = 100L)

        assertEquals(PoseIdentityTransform.Direct, firstResult.transform)
        assertEquals(false, firstResult.ambiguous)
        assertEquals(PoseIdentityTransform.Swapped, secondResult.transform)
    }

    @Test
    fun `second frame with same identity remains Direct`() {
        val stabilizer = PoseIdentityStabilizer()

        stabilizer.stabilize(basePose(), timestampMs = 0L)
        val result = stabilizer.stabilize(basePose(xOffset = 0.01f), timestampMs = 100L)

        assertEquals(PoseIdentityTransform.Direct, result.transform)
        assertEquals(false, result.ambiguous)
        assertTrue(result.directScore < result.swappedScore)
    }

    @Test
    fun `globally swapped raw pose returns Swapped and output remains close to previous stable pose`() {
        val stabilizer = PoseIdentityStabilizer()
        val previousPose = basePose()

        stabilizer.stabilize(previousPose, timestampMs = 0L)
        val result = stabilizer.stabilize(globallySwapped(previousPose), timestampMs = 100L)

        assertEquals(PoseIdentityTransform.Swapped, result.transform)
        assertPoseClose(previousPose, result.pose)
        assertTrue(result.swappedScore < result.directScore)
    }

    @Test
    fun `scores within switch margin keep previous transform and mark ambiguous`() {
        val stabilizer = PoseIdentityStabilizer()

        stabilizer.stabilize(basePose(), timestampMs = 0L)
        stabilizer.stabilize(globallySwapped(basePose()), timestampMs = 100L)
        val result = stabilizer.stabilize(ambiguousPose(), timestampMs = 200L)

        assertEquals(PoseIdentityTransform.Swapped, result.transform)
        assertEquals(true, result.ambiguous)
        assertTrue(abs(result.directScore - result.swappedScore) < 0.0001f)
    }

    @Test
    fun `timestamp gap resets and treats next pose as new Direct baseline`() {
        val stabilizer = PoseIdentityStabilizer()

        stabilizer.stabilize(basePose(), timestampMs = 0L)
        val result = stabilizer.stabilize(globallySwapped(basePose()), timestampMs = 1201L)

        assertEquals(PoseIdentityTransform.Direct, result.transform)
        assertEquals(false, result.ambiguous)
        assertEquals(0f, result.directScore, 0.0001f)
        assertEquals(0f, result.swappedScore, 0.0001f)
    }

    @Test
    fun `pose with fewer than 33 landmarks resets and returns Direct`() {
        val stabilizer = PoseIdentityStabilizer()

        stabilizer.stabilize(basePose(), timestampMs = 0L)
        val invalidResult = stabilizer.stabilize(PoseLandmarks.fromAllLandmarks(basePose().allLandmarks.take(10)), timestampMs = 100L)
        val nextResult = stabilizer.stabilize(globallySwapped(basePose()), timestampMs = 200L)

        assertEquals(PoseIdentityTransform.Direct, invalidResult.transform)
        assertEquals(false, invalidResult.ambiguous)
        assertEquals(PoseIdentityTransform.Direct, nextResult.transform)
    }

    @Test
    fun `wrists and ankles do not affect Direct Swapped decision`() {
        val stabilizer = PoseIdentityStabilizer()

        stabilizer.stabilize(basePose(), timestampMs = 0L)
        val noisyExtremitiesPose = basePose(
            leftWrist = point(0.95f, 0.95f, visibility = 0.01f),
            rightWrist = point(0.05f, 0.95f, visibility = 0.01f),
            leftAnkle = point(0.98f, 0.05f, visibility = 0.01f),
            rightAnkle = point(0.02f, 0.05f, visibility = 0.01f)
        )
        val result = stabilizer.stabilize(noisyExtremitiesPose, timestampMs = 100L)

        assertEquals(PoseIdentityTransform.Direct, result.transform)
        assertEquals(false, result.ambiguous)
        assertTrue(result.directScore < result.swappedScore)
    }

    private fun basePose(
        xOffset: Float = 0f,
        leftWrist: Point3D = point(0.30f, 0.50f, visibility = 0.01f),
        rightWrist: Point3D = point(0.70f, 0.50f, visibility = 0.01f),
        leftAnkle: Point3D = point(0.43f, 0.90f, visibility = 0.01f),
        rightAnkle: Point3D = point(0.57f, 0.90f, visibility = 0.01f)
    ): PoseLandmarks {
        val all = MutableList(33) { index ->
            point(0.50f + xOffset + index * 0.0001f, 0.50f, visibility = 0.5f)
        }
        all[11] = point(0.40f + xOffset, 0.30f)
        all[12] = point(0.60f + xOffset, 0.30f)
        all[13] = point(0.35f + xOffset, 0.42f)
        all[14] = point(0.65f + xOffset, 0.42f)
        all[15] = leftWrist.copy(x = leftWrist.x + xOffset)
        all[16] = rightWrist.copy(x = rightWrist.x + xOffset)
        all[23] = point(0.43f + xOffset, 0.60f)
        all[24] = point(0.57f + xOffset, 0.60f)
        all[25] = point(0.43f + xOffset, 0.78f)
        all[26] = point(0.57f + xOffset, 0.78f)
        all[27] = leftAnkle.copy(x = leftAnkle.x + xOffset)
        all[28] = rightAnkle.copy(x = rightAnkle.x + xOffset)
        return PoseLandmarks.fromAllLandmarks(all)
    }

    private fun ambiguousPose(): PoseLandmarks {
        val all = basePose().allLandmarks.toMutableList()
        listOf(11, 12).forEach { all[it] = point(0.50f, 0.30f) }
        listOf(13, 14).forEach { all[it] = point(0.50f, 0.42f) }
        listOf(23, 24).forEach { all[it] = point(0.50f, 0.60f) }
        listOf(25, 26).forEach { all[it] = point(0.50f, 0.78f) }
        return PoseLandmarks.fromAllLandmarks(all)
    }

    private fun point(x: Float, y: Float, visibility: Float = 1f): Point3D {
        return Point3D(
            x = x,
            y = y,
            z = 0f,
            visibility = visibility,
            presence = 1f
        )
    }

    private fun globallySwapped(pose: PoseLandmarks): PoseLandmarks {
        val swapped = pose.allLandmarks.toMutableList()
        leftRightPairs.forEach { (leftIndex, rightIndex) ->
            val left = swapped[leftIndex]
            swapped[leftIndex] = swapped[rightIndex]
            swapped[rightIndex] = left
        }
        return PoseLandmarks.fromAllLandmarks(swapped)
    }

    private fun assertPoseClose(expected: PoseLandmarks, actual: PoseLandmarks) {
        scoringIndices.forEach { index ->
            assertEquals(expected.allLandmarks[index].x, actual.allLandmarks[index].x, 0.0001f)
            assertEquals(expected.allLandmarks[index].y, actual.allLandmarks[index].y, 0.0001f)
        }
    }

    private companion object {
        private val scoringIndices = listOf(11, 12, 13, 14, 23, 24, 25, 26)
        private val leftRightPairs = listOf(
            1 to 4,
            2 to 5,
            3 to 6,
            7 to 8,
            9 to 10,
            11 to 12,
            13 to 14,
            15 to 16,
            17 to 18,
            19 to 20,
            21 to 22,
            23 to 24,
            25 to 26,
            27 to 28,
            29 to 30,
            31 to 32
        )
    }
}
