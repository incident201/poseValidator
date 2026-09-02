package com.incident201.poseguard.viewmodel

import com.incident201.poseguard.tracker.AccelerationMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AccelerationModeTest {
    @Test
    fun automaticallyResolvedModeResetsToAutoAfterAppUpdate() {
        listOf(AccelerationMode.Cpu, AccelerationMode.Gpu).forEach { savedMode ->
            assertEquals(
                AccelerationMode.Auto,
                accelerationModeAfterAppUpdate(
                    savedMode = savedMode,
                    wasAutomaticallyResolved = true,
                    savedVersionCode = 10,
                    currentVersionCode = 11
                )
            )
        }
    }

    @Test
    fun automaticallyResolvedModeIsKeptWithinSameAppVersion() {
        assertEquals(
            AccelerationMode.Gpu,
            accelerationModeAfterAppUpdate(
                savedMode = AccelerationMode.Gpu,
                wasAutomaticallyResolved = true,
                savedVersionCode = 11,
                currentVersionCode = 11
            )
        )
    }

    @Test
    fun manuallySelectedModeIsKeptAfterAppUpdate() {
        listOf(AccelerationMode.Cpu, AccelerationMode.Gpu).forEach { savedMode ->
            assertEquals(
                savedMode,
                accelerationModeAfterAppUpdate(
                    savedMode = savedMode,
                    wasAutomaticallyResolved = false,
                    savedVersionCode = 10,
                    currentVersionCode = 11
                )
            )
        }
    }

    @Test
    fun previousReleaseWithoutAccelerationSettingStartsInAuto() {
        assertEquals(
            AccelerationMode.Auto,
            accelerationModeAfterAppUpdate(
                savedMode = AccelerationMode.Auto,
                wasAutomaticallyResolved = false,
                savedVersionCode = -1,
                currentVersionCode = 11
            )
        )
    }
}
