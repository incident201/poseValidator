package com.incident201.poseguard.intiface

import kotlinx.coroutines.flow.StateFlow

enum class IntifaceBackgroundMode { Off, Vibration }

enum class IntifaceViolationMode { Off, Vibration, Pause }

enum class IntifaceVibrationPattern { Constant, Pulse }

data class IntifaceVibrationSettings(
    val strength: Double = 0.5,
    val pattern: IntifaceVibrationPattern = IntifaceVibrationPattern.Constant,
    val durationSeconds: Double = 1.0,
    val pulseLengthSeconds: Double = 0.25,
    val pulsePauseSeconds: Double = 0.25
)

data class IntifaceDeviceInfo(
    val index: Long,
    val name: String,
    val displayName: String,
    val vibrateCount: Long
)

data class IntifaceRememberedDevice(
    val name: String,
    val displayName: String,
    val index: Long?
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
    CommandRejectedDetail,
    SavedDeviceNotFound
}

data class IntifaceUiMessage(
    val message: IntifaceMessage,
    val args: List<String> = emptyList()
)

enum class IntifaceOperation {
    Idle,
    Connecting,
    Scanning,
    Testing
}

data class IntifaceUiState(
    val isSupported: Boolean,
    val operation: IntifaceOperation = IntifaceOperation.Idle,
    val isConnected: Boolean = false,
    val isScanning: Boolean = false,
    val isTestingVibration: Boolean = false,
    val devices: List<IntifaceDeviceInfo> = emptyList(),
    val selectedDevice: IntifaceDeviceInfo? = null,
    val statusMessage: IntifaceUiMessage? = null,
    val errorMessage: IntifaceUiMessage? = null
) {
    val isBusy: Boolean
        get() = operation != IntifaceOperation.Idle
}

interface IntifaceController {
    val state: StateFlow<IntifaceUiState>

    suspend fun searchDevices(url: String)
    suspend fun connectToRememberedDevice(url: String, rememberedDevice: IntifaceRememberedDevice)
    suspend fun testVibration()
    suspend fun setVibrationStrength(strength: Double)
    suspend fun stopVibration()
    fun selectDevice(device: IntifaceDeviceInfo)
    fun disconnect()
    suspend fun resetConnection()
    fun clearTransientMessages()
}

