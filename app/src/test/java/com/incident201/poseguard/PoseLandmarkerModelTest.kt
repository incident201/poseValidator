package com.incident201.poseguard

import com.incident201.poseguard.tracker.PoseLandmarkerModel
import com.incident201.poseguard.viewmodel.GameSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseLandmarkerModelTest {
    @Test
    fun heavyIsTheDefaultModel() {
        assertEquals(PoseLandmarkerModel.Heavy, GameSettings().poseLandmarkerModel)
    }

    @Test
    fun everyModelUsesItsBundledTaskAsset() {
        assertEquals(
            listOf(PoseLandmarkerModel.Heavy, PoseLandmarkerModel.Full),
            PoseLandmarkerModel.entries
        )
        val assetPaths = PoseLandmarkerModel.entries.map(PoseLandmarkerModel::assetPath)

        assertEquals(PoseLandmarkerModel.entries.size, assetPaths.toSet().size)
        assertTrue(assetPaths.all { it.startsWith("pose_landmarker_") && it.endsWith(".task") })
    }
}
