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
                errorText = "Enter a valid ws:// or wss:// Intiface Central URL"
            )
            return@withContext
        }

        mutableState.value = IntifaceUiState(
            isSupported = true,
            isScanning = true,
            statusText = "Connecting to Intiface Central…"
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
                statusText = "Searching for Intiface devices…",
                errorText = null
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
                statusText = if (devices.isEmpty()) {
                    "No devices with vibration capability found"
                } else {
                    "Found ${devices.size} device(s)"
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
                    statusText = null,
                    errorText = error.toUserMessage()
                )
            }
        }
    }

    override fun selectDevice(device: IntifaceDeviceInfo) {
        val availableDevice = mutableState.value.devices.firstOrNull { it.index == device.index } ?: return
        mutableState.value = mutableState.value.copy(
            selectedDevice = availableDevice,
            statusText = "Selected: ${availableDevice.displayName}",
            errorText = null
        )
    }

    override fun disconnect() {
        operationGeneration.incrementAndGet()
        controllerScope.launch {
            disconnectCurrentClient()
            mutableState.value = IntifaceUiState(
                isSupported = true,
                statusText = "Disconnected from Intiface Central"
            )
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
        newClient.setErrorReceived { error ->
            if (isCurrent(newClient, generation)) {
                mutableState.value = mutableState.value.copy(
                    isScanning = false,
                    errorText = error.getErrorMessage()?.takeIf { it.isNotBlank() }
                        ?: "Intiface Central reported an error"
                )
            }
        }
        newClient.setOnConnected {
            if (isCurrent(newClient, generation)) {
                mutableState.value = mutableState.value.copy(
                    isConnected = true,
                    statusText = "Connected to Intiface Central",
                    errorText = null
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

    private fun disconnectCurrentClient() {
        val oldClient = synchronized(clientLock) {
            client.also { client = null }
        } ?: return
        runCatching { oldClient.stopAllDevices() }
        runCatching { oldClient.disconnect() }
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
            ?: getName().orEmpty().ifBlank { "Device ${getDeviceIndex()}" },
        vibrateCount = getScalarVibrateCount()
    )

    private fun Throwable.toUserMessage(): String {
        val detail = message?.takeIf { it.isNotBlank() } ?: cause?.message?.takeIf { it.isNotBlank() }
        return if (detail == null) {
            "Unable to connect to Intiface Central"
        } else {
            "Unable to connect to Intiface Central: $detail"
        }
    }

    private companion object {
        const val SCAN_DURATION_MS = 5_000L
    }
}
