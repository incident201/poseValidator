package com.example.tracker

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseIdentityStabilizerTest {
    @Test
    fun `first valid frame returns Direct accepted and stores state`() {
        val stabilizer = PoseIdentityStabilizer()

        val firstResult = stabilizer.stabilize(basePose(), timestampMs = 0L)
        val secondResult = stabilizer.stabilize(globallySwapped(basePose()), timestampMs = 100L)

        assertEquals(PoseIdentityTransform.Direct, firstResult.transform)
        assertFalse(firstResult.ambiguous)
        assertFalse(firstResult.outlier)
        assertTrue(firstResult.accepted)
        assertEquals(PoseIdentityTransform.Swapped, secondResult.transform)
    }

    @Test
    fun `second frame with same identity remains Direct`() {
        val stabilizer = PoseIdentityStabilizer()

        stabilizer.stabilize(basePose(), timestampMs = 0L)
        val result = stabilizer.stabilize(basePose(xOffset = 0.01f), timestampMs = 100L)

        assertEquals(PoseIdentityTransform.Direct, result.transform)
        assertFalse(result.ambiguous)
        assertFalse(result.outlier)
        assertTrue(result.accepted)
        assertTrue(result.directScore < result.swappedScore)
    }

    @Test
    fun `globally swapped raw pose returns Swapped and output remains close to previous stable pose`() {
        val stabilizer = PoseIdentityStabilizer()
        val previousPose = basePose()

        stabilizer.stabilize(previousPose, timestampMs = 0L)
        val result = stabilizer.stabilize(globallySwapped(previousPose), timestampMs = 100L)

        assertEquals(PoseIdentityTransform.Swapped, result.transform)
        assertFalse(result.outlier)
        assertTrue(result.accepted)
        assertPoseClose(previousPose, result.pose)
        assertTrue(result.swappedScore < result.directScore)
    }

    @Test
    fun `scores within switch margin keep previous transform and mark ambiguous`() {
        val stabilizer = PoseIdentityStabilizer()

        stabilizer.stabilize(basePose(), timestampMs = 0L)
        stabilizer.stabilize(globallySwapped(basePose()), timestampMs = 100L)
        val result = stabilizer.stabilize(ambiguousOutlierPose(), timestampMs = 200L)

        assertEquals(PoseIdentityTransform.Swapped, result.transform)
        assertTrue(result.ambiguous)
        assertTrue(result.outlier)
        assertFalse(result.accepted)
        assertTrue(abs(result.directScore - result.swappedScore) < 0.0001f)
    }

    @Test
    fun `timestamp gap resets and treats next pose as new Direct baseline`() {
        val stabilizer = PoseIdentityStabilizer()

        stabilizer.stabilize(basePose(), timestampMs = 0L)
        val result = stabilizer.stabilize(globallySwapped(basePose()), timestampMs = 1201L)

        assertEquals(PoseIdentityTransform.Direct, result.transform)
        assertFalse(result.ambiguous)
        assertFalse(result.outlier)
        assertTrue(result.accepted)
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
        assertFalse(invalidResult.ambiguous)
        assertFalse(invalidResult.outlier)
        assertFalse(invalidResult.accepted)
        assertEquals(PoseIdentityTransform.Direct, nextResult.transform)
        assertTrue(nextResult.accepted)
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
        assertFalse(result.ambiguous)
        assertFalse(result.outlier)
        assertTrue(result.accepted)
        assertTrue(result.directScore < result.swappedScore)
    }

    @Test
    fun `single strongly deformed frame returns previous stable pose and does not update reference`() {
        val stabilizer = PoseIdentityStabilizer()
        val stablePose = basePose()

        stabilizer.stabilize(stablePose, timestampMs = 0L)
        val outlierResult = stabilizer.stabilize(scaledCorePose(scale = 0.20f), timestampMs = 100L)
        val swappedStableResult = stabilizer.stabilize(globallySwapped(stablePose), timestampMs = 200L)

        assertTrue(outlierResult.outlier)
        assertFalse(outlierResult.accepted)
        assertTrue(outlierResult.outlierReason.contains("scale"))
        assertPoseClose(stablePose, outlierResult.pose)
        assertEquals(PoseIdentityTransform.Swapped, swappedStableResult.transform)
        assertPoseClose(stablePose, swappedStableResult.pose)
    }

    @Test
    fun `normal frame after single outlier is accepted and resets outlier streak`() {
        val stabilizer = PoseIdentityStabilizer()

        stabilizer.stabilize(basePose(), timestampMs = 0L)
        val firstOutlier = stabilizer.stabilize(scaledCorePose(scale = 0.20f), timestampMs = 100L)
        val normalResult = stabilizer.stabilize(basePose(xOffset = 0.01f), timestampMs = 200L)
        val nextOutlier = stabilizer.stabilize(scaledCorePose(scale = 0.20f), timestampMs = 300L)

        assertTrue(firstOutlier.outlier)
        assertFalse(firstOutlier.accepted)
        assertFalse(normalResult.outlier)
        assertTrue(normalResult.accepted)
        assertTrue(nextOutlier.outlier)
        assertFalse(nextOutlier.accepted)
    }

    @Test
    fun `several consecutive outliers are accepted after freeze limit`() {
        val stabilizer = PoseIdentityStabilizer()
        val deformedPose = scaledCorePose(scale = 0.20f)

        stabilizer.stabilize(basePose(), timestampMs = 0L)
        val first = stabilizer.stabilize(deformedPose, timestampMs = 100L)
        val second = stabilizer.stabilize(deformedPose, timestampMs = 200L)
        val third = stabilizer.stabilize(deformedPose, timestampMs = 300L)

        assertTrue(first.outlier)
        assertFalse(first.accepted)
        assertTrue(second.outlier)
        assertFalse(second.accepted)
        assertFalse(third.outlier)
        assertTrue(third.accepted)
        assertPoseClose(deformedPose, third.pose)
    }

    @Test
    fun `ambiguous frame with small score can be accepted`() {
        val stabilizer = PoseIdentityStabilizer()
        val narrowPose = basePose(sideOffset = 0.003f)

        stabilizer.stabilize(narrowPose, timestampMs = 0L)
        val result = stabilizer.stabilize(globallySwapped(narrowPose), timestampMs = 100L)

        assertTrue(result.ambiguous)
        assertFalse(result.outlier)
        assertTrue(result.accepted)
        assertTrue(minOf(result.directScore, result.swappedScore) <= 0.16f)
    }

    @Test
    fun `ambiguous frame with large score is suppressed as outlier and does not update reference`() {
        val stabilizer = PoseIdentityStabilizer()
        val stablePose = basePose()

        stabilizer.stabilize(stablePose, timestampMs = 0L)
        val ambiguousOutlier = stabilizer.stabilize(ambiguousOutlierPose(), timestampMs = 100L)
        val swappedStableResult = stabilizer.stabilize(globallySwapped(stablePose), timestampMs = 200L)

        assertTrue(ambiguousOutlier.ambiguous)
        assertTrue(ambiguousOutlier.outlier)
        assertFalse(ambiguousOutlier.accepted)
        assertTrue(ambiguousOutlier.outlierReason.contains("score"))
        assertPoseClose(stablePose, ambiguousOutlier.pose)
        assertEquals(PoseIdentityTransform.Swapped, swappedStableResult.transform)
        assertPoseClose(stablePose, swappedStableResult.pose)
    }



    @Test
    fun `outlier decision uses chosen candidate score instead of best score`() {
        val stabilizer = PoseIdentityStabilizer()
        val stablePose = basePose()

        stabilizer.stabilize(stablePose, timestampMs = 0L)
        val result = stabilizer.stabilize(ambiguousDirectOutlierPose(), timestampMs = 100L)

        assertTrue(result.ambiguous)
        assertEquals(PoseIdentityTransform.Direct, result.transform)
        assertTrue(result.directScore > 0.32f)
        assertTrue(result.swappedScore < 0.32f)
        assertTrue(result.directScore - result.swappedScore < 0.05f)
        assertTrue(result.outlier)
        assertFalse(result.accepted)
        assertTrue(result.outlierReason.contains("score"))
        assertPoseClose(stablePose, result.pose)
    }

    @Test
    fun `non finite scoring coordinates are never accepted after freeze limit`() {
        val stabilizer = PoseIdentityStabilizer()
        val stablePose = basePose()
        val nonFinitePose = basePose(xOffset = 0.20f).withNonFiniteScoringPositions()

        stabilizer.stabilize(stablePose, timestampMs = 0L)
        val first = stabilizer.stabilize(nonFinitePose, timestampMs = 100L)
        val second = stabilizer.stabilize(nonFinitePose, timestampMs = 200L)
        val third = stabilizer.stabilize(nonFinitePose, timestampMs = 300L)

        listOf(first, second, third).forEach { result ->
            assertTrue(result.outlier)
            assertFalse(result.accepted)
            assertEquals("score", result.outlierReason)
            assertPoseClose(stablePose, result.pose)
        }
    }

    @Test
    fun `horizontal core collapse is suppressed even when torso length stays stable`() {
        val stabilizer = PoseIdentityStabilizer()
        val stablePose = basePose()

        stabilizer.stabilize(stablePose, timestampMs = 0L)
        val result = stabilizer.stabilize(basePose(sideOffset = 0.02f), timestampMs = 100L)

        assertTrue(result.outlier)
        assertFalse(result.accepted)
        assertTrue(result.outlierReason.contains("width"))
        assertPoseClose(stablePose, result.pose)
    }

    @Test
    fun `moderate core width change is accepted`() {
        val stabilizer = PoseIdentityStabilizer()

        stabilizer.stabilize(basePose(), timestampMs = 0L)
        val result = stabilizer.stabilize(basePose(sideOffset = 0.08f), timestampMs = 100L)

        assertFalse(result.outlier)
        assertTrue(result.accepted)
    }

    @Test
    fun `unavailable scores do not update stable reference as a good frame`() {
        val stabilizer = PoseIdentityStabilizer()
        val stablePose = basePose()

        stabilizer.stabilize(stablePose, timestampMs = 0L)
        val unavailableScoreResult = stabilizer.stabilize(
            basePose(xOffset = 0.20f).withNonFiniteScoringPositions(),
            timestampMs = 100L
        )
        val swappedStableResult = stabilizer.stabilize(globallySwapped(stablePose), timestampMs = 200L)

        assertTrue(unavailableScoreResult.ambiguous)
        assertTrue(unavailableScoreResult.outlier)
        assertFalse(unavailableScoreResult.accepted)
        assertEquals("score", unavailableScoreResult.outlierReason)
        assertEquals(-1f, unavailableScoreResult.directScore, 0.0001f)
        assertEquals(-1f, unavailableScoreResult.swappedScore, 0.0001f)
        assertEquals(PoseIdentityTransform.Swapped, swappedStableResult.transform)
        assertPoseClose(stablePose, swappedStableResult.pose)
    }

    @Test
    fun `reset clears outlier streak and returns next valid frame to Direct accepted baseline`() {
        val stabilizer = PoseIdentityStabilizer()

        stabilizer.stabilize(basePose(), timestampMs = 0L)
        stabilizer.stabilize(scaledCorePose(scale = 0.20f), timestampMs = 100L)
        stabilizer.stabilize(scaledCorePose(scale = 0.20f), timestampMs = 200L)
        stabilizer.reset()
        val result = stabilizer.stabilize(globallySwapped(basePose()), timestampMs = 300L)

        assertEquals(PoseIdentityTransform.Direct, result.transform)
        assertFalse(result.ambiguous)
        assertFalse(result.outlier)
        assertTrue(result.accepted)
        assertEquals(0f, result.directScore, 0.0001f)
        assertEquals(0f, result.swappedScore, 0.0001f)
    }

    private fun basePose(
        xOffset: Float = 0f,
        sideOffset: Float = 0.10f,
        leftWrist: Point3D = point(0.30f, 0.50f, visibility = 0.01f),
        rightWrist: Point3D = point(0.70f, 0.50f, visibility = 0.01f),
        leftAnkle: Point3D = point(0.43f, 0.90f, visibility = 0.01f),
        rightAnkle: Point3D = point(0.57f, 0.90f, visibility = 0.01f)
    ): PoseLandmarks {
        val centerX = 0.50f + xOffset
        val all = MutableList(33) { index ->
            point(centerX + index * 0.0001f, 0.50f, visibility = 0.5f)
        }
        all[11] = point(centerX - sideOffset, 0.30f)
        all[12] = point(centerX + sideOffset, 0.30f)
        all[13] = point(centerX - sideOffset * 1.5f, 0.42f)
        all[14] = point(centerX + sideOffset * 1.5f, 0.42f)
        all[15] = leftWrist.copy(x = leftWrist.x + xOffset)
        all[16] = rightWrist.copy(x = rightWrist.x + xOffset)
        all[23] = point(centerX - sideOffset * 0.7f, 0.60f)
        all[24] = point(centerX + sideOffset * 0.7f, 0.60f)
        all[25] = point(centerX - sideOffset * 0.7f, 0.78f)
        all[26] = point(centerX + sideOffset * 0.7f, 0.78f)
        all[27] = leftAnkle.copy(x = leftAnkle.x + xOffset)
        all[28] = rightAnkle.copy(x = rightAnkle.x + xOffset)
        return PoseLandmarks.fromAllLandmarks(all)
    }

    private fun scaledCorePose(scale: Float): PoseLandmarks {
        return basePose(sideOffset = 0.10f * scale).withCoreYScale(scale)
    }

    private fun PoseLandmarks.withCoreYScale(scale: Float): PoseLandmarks {
        val all = allLandmarks.toMutableList()
        val centerY = 0.45f
        listOf(11, 12, 13, 14, 23, 24, 25, 26).forEach { index ->
            val point = all[index]
            all[index] = point.copy(y = centerY + (point.y - centerY) * scale)
        }
        return PoseLandmarks.fromAllLandmarks(all)
    }



    private fun ambiguousDirectOutlierPose(): PoseLandmarks {
        val all = basePose().allLandmarks.toMutableList()
        all[11] = point(0.44054491f, 0.2679832f)
        all[12] = point(0.58080155f, 0.1987193f)
        all[13] = point(0.5207421f, 0.3865327f)
        all[14] = point(0.48232344f, 0.4171832f)
        all[23] = point(0.5338078f, 0.62821555f)
        all[24] = point(0.4266843f, 0.6595241f)
        all[25] = point(0.5254917f, 0.8709806f)
        all[26] = point(0.4372651f, 0.8778149f)
        return PoseLandmarks.fromAllLandmarks(all)
    }

    private fun PoseLandmarks.withNonFiniteScoringPositions(): PoseLandmarks {
        val all = allLandmarks.toMutableList()
        scoringIndices.forEach { index ->
            all[index] = all[index].copy(x = Float.NaN, y = Float.NaN)
        }
        return PoseLandmarks.fromAllLandmarks(all)
    }

    private fun ambiguousOutlierPose(): PoseLandmarks {
        val all = basePose().allLandmarks.toMutableList()
        listOf(11, 12).forEach { all[it] = point(0.50f, 0.15f) }
        listOf(13, 14).forEach { all[it] = point(0.50f, 0.30f) }
        listOf(23, 24).forEach { all[it] = point(0.50f, 0.75f) }
        listOf(25, 26).forEach { all[it] = point(0.50f, 0.95f) }
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
