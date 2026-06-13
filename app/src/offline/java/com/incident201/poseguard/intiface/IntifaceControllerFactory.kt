package com.incident201.poseguard.intiface

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Suppress("UNUSED_PARAMETER")
fun createIntifaceController(context: Context): IntifaceController = OfflineIntifaceController()

private class OfflineIntifaceController : IntifaceController {
    private val onlineOnlyMessage = IntifaceUiMessage(IntifaceMessage.OnlineOnly)
    private val mutableState = MutableStateFlow(
        IntifaceUiState(
            isSupported = false,
            statusMessage = onlineOnlyMessage
        )
    )

    override val state: StateFlow<IntifaceUiState> = mutableState.asStateFlow()

    override suspend fun searchDevices(url: String) {
        mutableState.value = mutableState.value.copy(errorMessage = onlineOnlyMessage)
    }

    override suspend fun connectToRememberedDevice(url: String, rememberedDevice: IntifaceRememberedDevice) {
        mutableState.value = mutableState.value.copy(errorMessage = onlineOnlyMessage)
    }

    override suspend fun testVibration() {
        mutableState.value = mutableState.value.copy(errorMessage = onlineOnlyMessage)
    }

    override suspend fun setVibrationStrength(strength: Double) {
        mutableState.value = mutableState.value.copy(errorMessage = onlineOnlyMessage)
    }

    override suspend fun stopVibration() {
        mutableState.value = mutableState.value.copy(errorMessage = onlineOnlyMessage)
    }

    override fun selectDevice(device: IntifaceDeviceInfo) = Unit

    override fun disconnect() = Unit
}
