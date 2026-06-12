package com.incident201.poseguard.intiface

import kotlinx.coroutines.flow.StateFlow

data class IntifaceDeviceInfo(
    val index: Long,
    val name: String,
    val displayName: String,
    val vibrateCount: Long
)

enum class IntifaceMessage {
    OnlineOnly,
    Connecting,
    Connected,
    Scanning,
    NoVibrateDevices,
    FoundDevices,
    SelectedDevice,
    Disconnected,
    InvalidUrl,
    ServerError,
    UnableToConnect,
    UnableToConnectDetail,
    TestVibration,
    TestVibrationDone,
    SelectDeviceFirst,
    SelectedDeviceMissing,
    NoVibrateCapability,
    TestVibrationFailed,
    TestVibrationFailedDetail,
    ScanRejected,
    CommandRejected,
    CommandRejectedDetail
}

data class IntifaceUiMessage(
    val message: IntifaceMessage,
    val args: List<String> = emptyList()
)

data class IntifaceUiState(
    val isSupported: Boolean,
    val isConnected: Boolean = false,
    val isScanning: Boolean = false,
    val isTestingVibration: Boolean = false,
    val devices: List<IntifaceDeviceInfo> = emptyList(),
    val selectedDevice: IntifaceDeviceInfo? = null,
    val statusMessage: IntifaceUiMessage? = null,
    val errorMessage: IntifaceUiMessage? = null
)

interface IntifaceController {
    val state: StateFlow<IntifaceUiState>

    suspend fun searchDevices(url: String)
    suspend fun testVibration()
    fun selectDevice(device: IntifaceDeviceInfo)
    fun disconnect()
}
