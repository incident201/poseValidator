package com.incident201.poseguard.intiface

import kotlinx.coroutines.flow.StateFlow

data class IntifaceDeviceInfo(
    val index: Long,
    val name: String,
    val displayName: String,
    val vibrateCount: Long
)

data class IntifaceUiState(
    val isSupported: Boolean,
    val isConnected: Boolean = false,
    val isScanning: Boolean = false,
    val devices: List<IntifaceDeviceInfo> = emptyList(),
    val selectedDevice: IntifaceDeviceInfo? = null,
    val statusText: String? = null,
    val errorText: String? = null
)

interface IntifaceController {
    val state: StateFlow<IntifaceUiState>

    suspend fun searchDevices(url: String)
    fun selectDevice(device: IntifaceDeviceInfo)
    fun disconnect()
}
