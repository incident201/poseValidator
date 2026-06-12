package com.incident201.poseguard.intiface

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val ONLINE_ONLY_MESSAGE = "Intiface Central is available only in the online build"

@Suppress("UNUSED_PARAMETER")
fun createIntifaceController(context: Context): IntifaceController = OfflineIntifaceController()

private class OfflineIntifaceController : IntifaceController {
    private val mutableState = MutableStateFlow(
        IntifaceUiState(
            isSupported = false,
            statusText = ONLINE_ONLY_MESSAGE
        )
    )

    override val state: StateFlow<IntifaceUiState> = mutableState.asStateFlow()

    override suspend fun searchDevices(url: String) {
        mutableState.value = mutableState.value.copy(errorText = ONLINE_ONLY_MESSAGE)
    }

    override fun selectDevice(device: IntifaceDeviceInfo) = Unit

    override fun disconnect() = Unit
}
