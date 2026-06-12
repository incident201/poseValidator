package com.incident201.poseguard.intiface

import io.github.blackspherefollower.buttplug4j.client.ButtplugClientDevice
import io.github.blackspherefollower.buttplug4j.client.IDeviceEvent
import io.github.blackspherefollower.buttplug4j.connectors.jetty.websocket.client.ButtplugClientWSClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

internal class OnlineIntifaceController : IntifaceController {
    private val mutableState = MutableStateFlow(IntifaceUiState(isSupported = true))
    override val state: StateFlow<IntifaceUiState> = mutableState.asStateFlow()

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clientLock = Any()
    private val operationGeneration = AtomicLong(0L)
    private var client: ButtplugClientWSClient? = null

    override suspend fun searchDevices(url: String) = withContext(Dispatchers.IO) {
        val generation = operationGeneration.incrementAndGet()
        disconnectCurrentClient()
        val uri = parseWebSocketUri(url) ?: run {
            mutableState.value = IntifaceUiState(
                isSupported = true,
                errorMessage = IntifaceUiMessage(IntifaceMessage.InvalidUrl)
            )
            return@withContext
        }

        mutableState.value = IntifaceUiState(
            isSupported = true,
            isScanning = true,
            statusMessage = IntifaceUiMessage(IntifaceMessage.Connecting)
        )

        val newClient = ButtplugClientWSClient("Pose Guard")
        synchronized(clientLock) {
            client = newClient
        }
        registerCallbacks(newClient, generation)

        try {
            newClient.connect(uri)
            if (!isCurrent(newClient, generation)) return@withContext

            mutableState.value = mutableState.value.copy(
                isConnected = true,
                isScanning = true,
                statusMessage = IntifaceUiMessage(IntifaceMessage.Scanning),
                errorMessage = null
            )
            newClient.startScanning()
            delay(SCAN_DURATION_MS)
            if (!isCurrent(newClient, generation)) return@withContext

            runCatching { newClient.stopScanning() }
            newClient.requestDeviceList()
            val devices = newClient.getDevices()
                .filter { it.getScalarVibrateCount() > 0L }
                .map { it.toDeviceInfo() }
                .sortedBy { it.displayName.lowercase() }

            mutableState.value = mutableState.value.copy(
                isConnected = newClient.isConnected(),
                isScanning = false,
                devices = devices,
                selectedDevice = mutableState.value.selectedDevice
                    ?.takeIf { selected -> devices.any { it.index == selected.index } },
                statusMessage = if (devices.isEmpty()) {
                    IntifaceUiMessage(IntifaceMessage.NoVibrateDevices)
                } else {
                    IntifaceUiMessage(
                        IntifaceMessage.FoundDevices,
                        listOf(devices.size.toString())
                    )
                }
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (isCurrent(newClient, generation)) {
                runCatching { newClient.disconnect() }
                synchronized(clientLock) {
                    if (client === newClient) client = null
                }
                mutableState.value = mutableState.value.copy(
                    isConnected = false,
                    isScanning = false,
                    devices = emptyList(),
                    selectedDevice = null,
                    statusMessage = null,
                    errorMessage = error.toConnectionErrorMessage()
                )
            }
        }
    }

    override suspend fun testVibration() = withContext(Dispatchers.IO) {
        if (mutableState.value.isTestingVibration) return@withContext

        val selected = mutableState.value.selectedDevice
        if (selected == null) {
            mutableState.value = mutableState.value.copy(
                errorMessage = IntifaceUiMessage(IntifaceMessage.SelectDeviceFirst)
            )
            return@withContext
        }

        val activeClient = synchronized(clientLock) { client }
        if (activeClient == null || !activeClient.isConnected()) {
            mutableState.value = mutableState.value.copy(
                isConnected = false,
                errorMessage = IntifaceUiMessage(IntifaceMessage.UnableToConnect)
            )
            return@withContext
        }

        var deviceToStop: ButtplugClientDevice? = null
        try {
            val device = activeClient.getDevices()
                .firstOrNull { it.getDeviceIndex() == selected.index }
            if (device == null) {
                mutableState.value = mutableState.value.copy(
                    selectedDevice = null,
                    statusMessage = null,
                    errorMessage = IntifaceUiMessage(IntifaceMessage.SelectedDeviceMissing)
                )
                return@withContext
            }
            deviceToStop = device
            if (device.getScalarVibrateCount() <= 0L) {
                mutableState.value = mutableState.value.copy(
                    errorMessage = IntifaceUiMessage(IntifaceMessage.NoVibrateCapability)
                )
                return@withContext
            }

            mutableState.value = mutableState.value.copy(
                isTestingVibration = true,
                statusMessage = IntifaceUiMessage(IntifaceMessage.TestVibration),
                errorMessage = null
            )
            repeat(TEST_PULSE_COUNT) { pulseIndex ->
                device.sendScalarVibrateCmd(TEST_VIBRATION_STRENGTH).get()
                delay(TEST_PULSE_DURATION_MS)
                device.sendScalarVibrateCmd(0.0).get()
                if (pulseIndex < TEST_PULSE_COUNT - 1) {
                    delay(TEST_PULSE_PAUSE_MS)
                }
            }
            mutableState.value = mutableState.value.copy(
                statusMessage = IntifaceUiMessage(IntifaceMessage.TestVibrationDone)
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            mutableState.value = mutableState.value.copy(
                statusMessage = null,
                errorMessage = error.toVibrationErrorMessage()
            )
        } finally {
            deviceToStop?.let { selectedDevice ->
                runCatching { selectedDevice.sendScalarVibrateCmd(0.0).get() }
                runCatching { selectedDevice.sendStopDeviceCmd().get() }
            }
            mutableState.value = mutableState.value.copy(isTestingVibration = false)
        }
    }

    override fun selectDevice(device: IntifaceDeviceInfo) {
        val availableDevice = mutableState.value.devices.firstOrNull { it.index == device.index } ?: return
        mutableState.value = mutableState.value.copy(
            selectedDevice = availableDevice,
            statusMessage = IntifaceUiMessage(
                IntifaceMessage.SelectedDevice,
                listOf(availableDevice.displayName)
            ),
            errorMessage = null
        )
    }

    override fun disconnect() {
        val generation = operationGeneration.incrementAndGet()
        val clientToDisconnect = takeCurrentClient()
        val disconnectedState = IntifaceUiState(
            isSupported = true,
            statusMessage = IntifaceUiMessage(IntifaceMessage.Disconnected)
        )
        mutableState.value = disconnectedState

        controllerScope.launch {
            disconnectClient(clientToDisconnect)

            val stillCurrent = operationGeneration.get() == generation &&
                synchronized(clientLock) { client == null }
            if (stillCurrent) {
                mutableState.value = disconnectedState
            }
        }
    }

    private fun registerCallbacks(newClient: ButtplugClientWSClient, generation: Long) {
        newClient.setDeviceAdded(object : IDeviceEvent {
            override fun deviceAdded(device: ButtplugClientDevice) {
                if (!isCurrent(newClient, generation) || mutableState.value.isScanning) return
                runCatching { refreshDevices(newClient) }
            }

            override fun deviceRemoved(deviceIndex: Long) {
                if (!isCurrent(newClient, generation) || mutableState.value.isScanning) return
                runCatching { refreshDevices(newClient) }
            }
        })
        newClient.setDeviceRemoved(object : IDeviceEvent {
            override fun deviceAdded(device: ButtplugClientDevice) = Unit

            override fun deviceRemoved(deviceIndex: Long) {
                if (!isCurrent(newClient, generation) || mutableState.value.isScanning) return
                runCatching { refreshDevices(newClient) }
            }
        })
        newClient.setScanningFinished {
            if (isCurrent(newClient, generation)) {
                mutableState.value = mutableState.value.copy(isScanning = false)
            }
        }
        newClient.setErrorReceived {
            if (isCurrent(newClient, generation)) {
                mutableState.value = mutableState.value.copy(
                    isScanning = false,
                    errorMessage = IntifaceUiMessage(IntifaceMessage.ServerError)
                )
            }
        }
        newClient.setOnConnected {
            if (isCurrent(newClient, generation)) {
                mutableState.value = mutableState.value.copy(
                    isConnected = true,
                    statusMessage = IntifaceUiMessage(IntifaceMessage.Connected),
                    errorMessage = null
                )
            }
        }
    }

    private fun refreshDevices(sourceClient: ButtplugClientWSClient) {
        val devices = sourceClient.getDevices()
            .filter { it.getScalarVibrateCount() > 0L }
            .map { it.toDeviceInfo() }
            .sortedBy { it.displayName.lowercase() }
        val selected = mutableState.value.selectedDevice
            ?.takeIf { selectedDevice -> devices.any { it.index == selectedDevice.index } }
        mutableState.value = mutableState.value.copy(devices = devices, selectedDevice = selected)
    }

    private fun takeCurrentClient(): ButtplugClientWSClient? =
        synchronized(clientLock) {
            client.also { client = null }
        }

    private fun disconnectClient(targetClient: ButtplugClientWSClient?) {
        if (targetClient == null) return
        runCatching { targetClient.stopAllDevices() }
        runCatching { targetClient.disconnect() }
    }

    private fun disconnectCurrentClient() {
        disconnectClient(takeCurrentClient())
    }

    private fun isCurrent(candidate: ButtplugClientWSClient, generation: Long): Boolean =
        operationGeneration.get() == generation && synchronized(clientLock) { client === candidate }

    private fun parseWebSocketUri(value: String): URI? = runCatching {
        URI(value.trim()).takeIf { uri ->
            uri.isAbsolute && uri.host != null && uri.scheme.lowercase() in setOf("ws", "wss")
        }
    }.getOrNull()

    private fun ButtplugClientDevice.toDeviceInfo(): IntifaceDeviceInfo = IntifaceDeviceInfo(
        index = getDeviceIndex(),
        name = getName().orEmpty(),
        displayName = getDisplayName()?.takeIf { it.isNotBlank() }
            ?: getName().orEmpty().ifBlank { getDeviceIndex().toString() },
        vibrateCount = getScalarVibrateCount()
    )

    private fun Throwable.toConnectionErrorMessage(): IntifaceUiMessage {
        val detail = message?.takeIf { it.isNotBlank() }
            ?: cause?.message?.takeIf { it.isNotBlank() }
        return if (detail == null) {
            IntifaceUiMessage(IntifaceMessage.UnableToConnect)
        } else {
            IntifaceUiMessage(IntifaceMessage.UnableToConnectDetail, listOf(detail))
        }
    }

    private fun Throwable.toVibrationErrorMessage(): IntifaceUiMessage {
        val detail = message?.takeIf { it.isNotBlank() }
            ?: cause?.message?.takeIf { it.isNotBlank() }
        return if (detail == null) {
            IntifaceUiMessage(IntifaceMessage.TestVibrationFailed)
        } else {
            IntifaceUiMessage(IntifaceMessage.TestVibrationFailedDetail, listOf(detail))
        }
    }

    private companion object {
        const val SCAN_DURATION_MS = 5_000L
        const val TEST_PULSE_COUNT = 3
        const val TEST_VIBRATION_STRENGTH = 0.6
        const val TEST_PULSE_DURATION_MS = 180L
        const val TEST_PULSE_PAUSE_MS = 180L
    }
}
