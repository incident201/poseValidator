package com.incident201.poseguard.intiface

import io.github.blackspherefollower.buttplug4j.client.ButtplugClientDevice
import io.github.blackspherefollower.buttplug4j.client.IDeviceEvent
import io.github.blackspherefollower.buttplug4j.connectors.jetty.websocket.client.ButtplugClientWSClient
import io.github.blackspherefollower.buttplug4j.protocol.ButtplugMessage
import io.github.blackspherefollower.buttplug4j.protocol.messages.Error
import io.github.blackspherefollower.buttplug4j.protocol.messages.Ok
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

internal class OnlineIntifaceController : IntifaceController {
    private sealed class CommandAttempt {
        object Skipped : CommandAttempt()
        object Success : CommandAttempt()
        data class Failure(val error: Throwable) : CommandAttempt()
    }

    private val mutableState = MutableStateFlow(IntifaceUiState(isSupported = true))
    override val state: StateFlow<IntifaceUiState> = mutableState.asStateFlow()

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clientLock = Any()
    private val searchDevicesMutex = Mutex()
    private val vibrationCommandMutex = Mutex()
    private val clientLifecycleMutex = Mutex()
    private val operationGeneration = AtomicLong(0L)
    private var client: ButtplugClientWSClient? = null
    private var connectedUri: URI? = null
    @Volatile
    private var pendingClientDisconnectJob: Job? = null

    override suspend fun searchDevices(url: String) = withContext(Dispatchers.IO) {
        searchDevicesMutex.withLock {
            runSearchDevicesLocked(url)
        }
    }

    override suspend fun connectToRememberedDevice(url: String, rememberedDevice: IntifaceRememberedDevice) = withContext(Dispatchers.IO) {
        searchDevicesMutex.withLock {
            runConnectToRememberedLocked(url, rememberedDevice)
        }
    }

    private suspend fun ensureClientConnected(
        url: String,
        forceNewConnection: Boolean = false
    ): Pair<ButtplugClientWSClient, Long>? {
        val uri = parseWebSocketUri(url) ?: run {
            clientLifecycleMutex.withLock {
                val oldClient = takeAndInvalidateCurrentClient()
                disconnectClient(oldClient)
            }
            mutableState.value = IntifaceUiState(
                isSupported = true,
                errorMessage = IntifaceUiMessage(IntifaceMessage.InvalidUrl)
            )
            return null
        }

        if (!forceNewConnection) {
            val existingSession = synchronized(clientLock) {
                val c = client
                if (c != null && c.isConnected() && connectedUri == uri) {
                    Pair(c, operationGeneration.get())
                } else {
                    null
                }
            }
            if (existingSession != null) {
                return existingSession
            }
        }

        awaitPendingClientDisconnect()

        val session = clientLifecycleMutex.withLock {
            val oldClient = takeAndInvalidateCurrentClient()
            disconnectClient(oldClient)

            val generation = operationGeneration.get()

            mutableState.value = IntifaceUiState(
                isSupported = true,
                isScanning = true,
                statusMessage = IntifaceUiMessage(IntifaceMessage.Connecting)
            )

            val newClient = ButtplugClientWSClient("Pose Guard")
            synchronized(clientLock) {
                client = newClient
                connectedUri = uri
            }
            registerCallbacks(newClient, generation)

            Pair(newClient, generation)
        }

        val newClient = session.first
        val generation = session.second

        try {
            connectClientWithTimeout(newClient, uri)

            if (!isCurrent(newClient, generation)) {
                return null
            }

            return Pair(newClient, generation)
        } catch (error: Throwable) {
            val clientToDisconnect = synchronized(clientLock) {
                if (operationGeneration.get() == generation && client === newClient) {
                    operationGeneration.incrementAndGet()
                    connectedUri = null
                    client.also { client = null }
                } else {
                    null
                }
            }

            if (clientToDisconnect != null) {
                disconnectClient(clientToDisconnect)
                mutableState.value = mutableState.value.copy(
                    isConnected = false,
                    isScanning = false,
                    devices = emptyList(),
                    selectedDevice = null,
                    statusMessage = null,
                    errorMessage = error.toConnectionErrorMessage()
                )
            }

            return null
        }
    }

    private suspend fun connectClientWithTimeout(
        targetClient: ButtplugClientWSClient,
        uri: URI
    ) {
        val connectJob = controllerScope.async {
            targetClient.connect(uri)
        }

        try {
            withTimeout(CONNECT_TIMEOUT_MS) {
                connectJob.await()
            }
        } catch (timeout: TimeoutCancellationException) {
            connectJob.cancel()
            throw timeout
        }
    }

    private suspend fun runSearchDevicesLocked(url: String) {
        mutableState.value = mutableState.value.copy(
            isScanning = true,
            statusMessage = IntifaceUiMessage(IntifaceMessage.Scanning),
            errorMessage = null
        )
        val pair = ensureClientConnected(url, forceNewConnection = true)
        if (pair == null) {
            mutableState.value = mutableState.value.copy(
                isScanning = false,
                statusMessage = null
            )
            return
        }
        val newClient = pair.first
        val generation = pair.second

        try {
            mutableState.value = mutableState.value.copy(
                isConnected = true
            )
            val scanStarted = newClient.startScanning()
            if (!scanStarted) {
                if (isCurrent(newClient, generation)) {
                    mutableState.value = mutableState.value.copy(
                        isScanning = false,
                        statusMessage = null,
                        errorMessage = IntifaceUiMessage(IntifaceMessage.ScanRejected)
                    )
                }
                return
            }
            delay(SCAN_DURATION_MS)
            if (!isCurrent(newClient, generation)) return

            runCatching { newClient.stopScanning() }
            newClient.requestDeviceList()
            val devices = newClient.getDevices()
                .filter { it.getScalarVibrateCount() > 0L }
                .map { it.toDeviceInfo() }
                .sortedBy { it.displayName.lowercase() }
            if (!isCurrent(newClient, generation)) return

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
            val clientToDisconnect = synchronized(clientLock) {
                if (operationGeneration.get() == generation && client === newClient) {
                    connectedUri = null
                    client.also { client = null }
                } else {
                    null
                }
            }
            if (clientToDisconnect != null) {
                disconnectClient(clientToDisconnect)
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

    private suspend fun runConnectToRememberedLocked(url: String, rememberedDevice: IntifaceRememberedDevice) {
        val pair = ensureClientConnected(url) ?: return
        val newClient = pair.first
        val generation = pair.second

        try {
            mutableState.value = mutableState.value.copy(
                isConnected = true,
                isScanning = true,
                statusMessage = IntifaceUiMessage(IntifaceMessage.Connecting),
                errorMessage = null
            )
            val scanStarted = newClient.startScanning()
            if (!scanStarted) {
                if (isCurrent(newClient, generation)) {
                    mutableState.value = mutableState.value.copy(
                        isScanning = false,
                        statusMessage = null,
                        errorMessage = IntifaceUiMessage(IntifaceMessage.ScanRejected)
                    )
                }
                return
            }
            delay(SCAN_DURATION_MS)
            if (!isCurrent(newClient, generation)) return

            runCatching { newClient.stopScanning() }
            newClient.requestDeviceList()
            val devices = newClient.getDevices()
                .filter { it.getScalarVibrateCount() > 0L }
                .map { it.toDeviceInfo() }
                .sortedBy { it.displayName.lowercase() }
            if (!isCurrent(newClient, generation)) return

            val matchedDevice = devices.firstOrNull { it.name == rememberedDevice.name }
                ?: devices.firstOrNull { it.displayName == rememberedDevice.displayName }
                ?: devices.firstOrNull { it.index == rememberedDevice.index }

            if (matchedDevice != null) {
                mutableState.value = mutableState.value.copy(
                    isConnected = newClient.isConnected(),
                    isScanning = false,
                    devices = devices,
                    selectedDevice = matchedDevice,
                    statusMessage = IntifaceUiMessage(
                        IntifaceMessage.SelectedDevice,
                        listOf(matchedDevice.displayName)
                    )
                )
            } else {
                mutableState.value = mutableState.value.copy(
                    isConnected = newClient.isConnected(),
                    isScanning = false,
                    devices = devices,
                    selectedDevice = null,
                    statusMessage = null,
                    errorMessage = IntifaceUiMessage(IntifaceMessage.SavedDeviceNotFound)
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val clientToDisconnect = synchronized(clientLock) {
                if (operationGeneration.get() == generation && client === newClient) {
                    connectedUri = null
                    client.also { client = null }
                } else {
                    null
                }
            }
            if (clientToDisconnect != null) {
                disconnectClient(clientToDisconnect)
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
        if (!vibrationCommandMutex.tryLock()) return@withContext

        try {
            runVibrationTestLocked()
        } finally {
            vibrationCommandMutex.unlock()
        }
    }

    override suspend fun setVibrationStrength(strength: Double) = withContext(Dispatchers.IO) {
        vibrationCommandMutex.withLock {
            runSessionVibrationCommand(strength.coerceIn(0.0, 1.0), stopDevice = false)
        }
    }

    override suspend fun stopVibration() = withContext(Dispatchers.IO) {
        vibrationCommandMutex.withLock {
            runSessionVibrationCommand(0.0, stopDevice = true)
        }
    }

    private fun runSessionVibrationCommand(strength: Double, stopDevice: Boolean) {
        val session = synchronized(clientLock) {
            val c = client
            if (c != null && c.isConnected()) {
                c to operationGeneration.get()
            } else {
                null
            }
        } ?: return

        val activeClient = session.first
        val generation = session.second
        fun updateIfCurrent(update: (IntifaceUiState) -> IntifaceUiState) {
            if (isCurrent(activeClient, generation)) {
                mutableState.value = update(mutableState.value)
            }
        }
        if (!isCurrent(activeClient, generation)) return

        val selected = mutableState.value.selectedDevice
        if (selected == null) {
            updateIfCurrent {
                it.copy(
                    errorMessage = IntifaceUiMessage(IntifaceMessage.SelectedDeviceMissing)
                )
            }
            return
        }
        val device = activeClient.getDevices().firstOrNull { it.getDeviceIndex() == selected.index }
        if (device == null) {
            updateIfCurrent {
                it.copy(
                    selectedDevice = null,
                    errorMessage = IntifaceUiMessage(IntifaceMessage.SelectedDeviceMissing)
                )
            }
            return
        }
        if (device.getScalarVibrateCount() <= 0L) {
            updateIfCurrent {
                it.copy(errorMessage = IntifaceUiMessage(IntifaceMessage.NoVibrateCapability))
            }
            return
        }
        if (stopDevice) {
            val zeroResult = runScalarVibrateIfCurrent(activeClient, generation, device, 0.0)
            val stopResult = runStopDeviceIfCurrent(activeClient, generation, device)

            val error = listOf(zeroResult, stopResult)
                .filterIsInstance<CommandAttempt.Failure>()
                .firstOrNull()
                ?.error

            if (zeroResult is CommandAttempt.Skipped && stopResult is CommandAttempt.Skipped) {
                return
            }

            updateIfCurrent {
                if (error != null) {
                    it.copy(errorMessage = error.toSessionVibrationErrorMessage())
                } else {
                    it.copy(errorMessage = null)
                }
            }
            return
        }

        when (val result = runScalarVibrateIfCurrent(activeClient, generation, device, strength)) {
            CommandAttempt.Skipped -> return
            CommandAttempt.Success -> updateIfCurrent { it.copy(errorMessage = null) }
            is CommandAttempt.Failure -> updateIfCurrent {
                it.copy(errorMessage = result.error.toSessionVibrationErrorMessage())
            }
        }
    }

    private suspend fun runVibrationTestLocked() {
        if (mutableState.value.isTestingVibration) return

        val selected = mutableState.value.selectedDevice
        if (selected == null) {
            mutableState.value = mutableState.value.copy(
                errorMessage = IntifaceUiMessage(IntifaceMessage.SelectDeviceFirst)
            )
            return
        }

        val activeClient = synchronized(clientLock) { client }
        if (activeClient == null || !activeClient.isConnected()) {
            mutableState.value = mutableState.value.copy(
                isConnected = false,
                errorMessage = IntifaceUiMessage(IntifaceMessage.UnableToConnect)
            )
            return
        }
        val generation = operationGeneration.get()

        var deviceToStop: ButtplugClientDevice? = null
        try {
            if (!isCurrent(activeClient, generation)) return
            val device = activeClient.getDevices()
                .firstOrNull { it.getDeviceIndex() == selected.index }
            if (device == null) {
                mutableState.value = mutableState.value.copy(
                    selectedDevice = null,
                    statusMessage = null,
                    errorMessage = IntifaceUiMessage(IntifaceMessage.SelectedDeviceMissing)
                )
                return
            }
            deviceToStop = device
            if (device.getScalarVibrateCount() <= 0L) {
                mutableState.value = mutableState.value.copy(
                    errorMessage = IntifaceUiMessage(IntifaceMessage.NoVibrateCapability)
                )
                return
            }

            mutableState.value = mutableState.value.copy(
                isTestingVibration = true,
                statusMessage = IntifaceUiMessage(IntifaceMessage.TestVibration),
                errorMessage = null
            )
            repeat(TEST_PULSE_COUNT) { pulseIndex ->
                when (val result = runScalarVibrateIfCurrent(
                    activeClient,
                    generation,
                    device,
                    TEST_VIBRATION_STRENGTH
                )) {
                    CommandAttempt.Skipped -> return
                    CommandAttempt.Success -> Unit
                    is CommandAttempt.Failure -> throw result.error
                }
                delay(TEST_PULSE_DURATION_MS)
                
                when (val result = runScalarVibrateIfCurrent(activeClient, generation, device, 0.0)) {
                    CommandAttempt.Skipped -> return
                    CommandAttempt.Success -> Unit
                    is CommandAttempt.Failure -> throw result.error
                }
                
                if (pulseIndex < TEST_PULSE_COUNT - 1) {
                    delay(TEST_PULSE_PAUSE_MS)
                }
            }
            if (isCurrent(activeClient, generation)) {
                mutableState.value = mutableState.value.copy(
                    statusMessage = IntifaceUiMessage(IntifaceMessage.TestVibrationDone)
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (isCurrent(activeClient, generation)) {
                mutableState.value = mutableState.value.copy(
                    statusMessage = null,
                    errorMessage = error.toVibrationErrorMessage()
                )
            }
        } finally {
            deviceToStop?.let { selectedDevice ->
                runScalarVibrateIfCurrent(activeClient, generation, selectedDevice, 0.0)
                runStopDeviceIfCurrent(activeClient, generation, selectedDevice)
            }
            if (isCurrent(activeClient, generation)) {
                mutableState.value = mutableState.value.copy(isTestingVibration = false)
            }
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
        val clientToDisconnect = takeAndInvalidateCurrentClient()
        val disconnectedState = IntifaceUiState(
            isSupported = true,
            statusMessage = IntifaceUiMessage(IntifaceMessage.Disconnected)
        )
        mutableState.value = disconnectedState
        val generation = operationGeneration.get()

        controllerScope.launch {
            clientLifecycleMutex.withLock {
                disconnectClient(clientToDisconnect)
            }

            val stillCurrent = operationGeneration.get() == generation &&
                synchronized(clientLock) { client == null }
            if (stillCurrent) {
                mutableState.value = disconnectedState
            }
        }
    }

    override suspend fun resetConnection() = withContext(Dispatchers.IO) {
        searchDevicesMutex.withLock {
            clientLifecycleMutex.withLock {
                val oldClient = takeAndInvalidateCurrentClient()
                disconnectClient(oldClient)
                mutableState.value = IntifaceUiState(isSupported = true)
            }
        }
    }

    override fun clearTransientMessages() {
        clearTransientMessagesInState()
    }

    private fun registerCallbacks(newClient: ButtplugClientWSClient, generation: Long) {
        newClient.setDeviceAdded(object : IDeviceEvent {
            override fun deviceAdded(device: ButtplugClientDevice) {
                if (!isCurrent(newClient, generation) || mutableState.value.isScanning) return
                runCatching { refreshDevices(newClient, generation) }
            }

            override fun deviceRemoved(deviceIndex: Long) {
                if (!isCurrent(newClient, generation) || mutableState.value.isScanning) return
                runCatching { refreshDevices(newClient, generation) }
            }
        })
        newClient.setDeviceRemoved(object : IDeviceEvent {
            override fun deviceAdded(device: ButtplugClientDevice) = Unit

            override fun deviceRemoved(deviceIndex: Long) {
                if (!isCurrent(newClient, generation) || mutableState.value.isScanning) return
                runCatching { refreshDevices(newClient, generation) }
            }
        })
        newClient.setScanningFinished {
            if (isCurrent(newClient, generation)) {
                mutableState.value = mutableState.value.copy(isScanning = false)
            }
        }
        newClient.setErrorReceived {
            val clientToDisconnect = synchronized(clientLock) {
                if (operationGeneration.get() == generation && client === newClient) {
                    operationGeneration.incrementAndGet()
                    connectedUri = null
                    client.also { client = null }
                } else {
                    null
                }
            }

            if (clientToDisconnect != null) {
                mutableState.value = IntifaceUiState(
                    isSupported = true,
                    isConnected = false,
                    isScanning = false,
                    isTestingVibration = false,
                    devices = emptyList(),
                    selectedDevice = null,
                    statusMessage = null,
                    errorMessage = IntifaceUiMessage(IntifaceMessage.ServerError)
                )

                val disconnectJob = controllerScope.launch {
                    clientLifecycleMutex.withLock {
                        disconnectClient(clientToDisconnect)
                    }
                }
                pendingClientDisconnectJob = disconnectJob
                disconnectJob.invokeOnCompletion {
                    if (pendingClientDisconnectJob === disconnectJob) {
                        pendingClientDisconnectJob = null
                    }
                }
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

    private fun refreshDevices(sourceClient: ButtplugClientWSClient, generation: Long) {
        val devices = sourceClient.getDevices()
            .filter { it.getScalarVibrateCount() > 0L }
            .map { it.toDeviceInfo() }
            .sortedBy { it.displayName.lowercase() }
        if (!isCurrent(sourceClient, generation)) return
        val selected = mutableState.value.selectedDevice
            ?.takeIf { selectedDevice -> devices.any { it.index == selectedDevice.index } }
        if (!isCurrent(sourceClient, generation)) return
        mutableState.value = mutableState.value.copy(devices = devices, selectedDevice = selected)
    }

    private fun takeAndInvalidateCurrentClient(): ButtplugClientWSClient? {
        operationGeneration.incrementAndGet()
        return synchronized(clientLock) {
            connectedUri = null
            client.also { client = null }
        }
    }

    private suspend fun awaitPendingClientDisconnect() {
        pendingClientDisconnectJob?.join()
    }

    private fun clearTransientMessagesInState() {
        mutableState.value = mutableState.value.copy(
            statusMessage = null,
            errorMessage = null
        )
    }

    private fun disconnectClient(targetClient: ButtplugClientWSClient?) {
        if (targetClient == null) return
        runCatching { targetClient.stopAllDevices() }
        runCatching { targetClient.disconnect() }
    }

    private fun isCurrent(candidate: ButtplugClientWSClient, generation: Long): Boolean =
        operationGeneration.get() == generation && synchronized(clientLock) { client === candidate }

    private fun runScalarVibrateIfCurrent(
        sourceClient: ButtplugClientWSClient,
        generation: Long,
        device: ButtplugClientDevice,
        strength: Double
    ): CommandAttempt {
        val future = synchronized(clientLock) {
            if (operationGeneration.get() == generation && client === sourceClient) {
                device.sendScalarVibrateCmd(strength)
            } else {
                null
            }
        } ?: return CommandAttempt.Skipped

        return runCatching {
            requireOk(awaitResponse(future))
        }.fold(
            onSuccess = { CommandAttempt.Success },
            onFailure = { CommandAttempt.Failure(it) }
        )
    }

    private fun runStopDeviceIfCurrent(
        sourceClient: ButtplugClientWSClient,
        generation: Long,
        device: ButtplugClientDevice
    ): CommandAttempt {
        val future = synchronized(clientLock) {
            if (operationGeneration.get() == generation && client === sourceClient) {
                device.sendStopDeviceCmd()
            } else {
                null
            }
        } ?: return CommandAttempt.Skipped

        return runCatching {
            requireOk(awaitResponse(future))
        }.fold(
            onSuccess = { CommandAttempt.Success },
            onFailure = { CommandAttempt.Failure(it) }
        )
    }

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

    private fun requireOk(response: ButtplugMessage) {
        when (response) {
            is Ok -> Unit
            is Error -> throw ButtplugCommandRejectedException(
                response.getErrorMessage()?.takeIf { it.isNotBlank() }
                    ?: COMMAND_REJECTED_FALLBACK
            )
            else -> throw ButtplugCommandRejectedException(
                "$UNEXPECTED_RESPONSE_PREFIX${response.javaClass.simpleName}"
            )
        }
    }

    private fun awaitResponse(future: java.util.concurrent.Future<out ButtplugMessage>): ButtplugMessage {
        return try {
            future.get(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            future.cancel(true)
            throw timeout
        }
    }

    private fun Throwable.toVibrationErrorMessage(): IntifaceUiMessage {
        val detail = message?.takeIf { it.isNotBlank() }
            ?: cause?.message?.takeIf { it.isNotBlank() }
        if (this is ButtplugCommandRejectedException) {
            return if (detail == null) {
                IntifaceUiMessage(IntifaceMessage.CommandRejected)
            } else {
                IntifaceUiMessage(IntifaceMessage.CommandRejectedDetail, listOf(detail))
            }
        }
        return if (detail == null) {
            IntifaceUiMessage(IntifaceMessage.TestVibrationFailed)
        } else {
            IntifaceUiMessage(IntifaceMessage.TestVibrationFailedDetail, listOf(detail))
        }
    }

    private fun Throwable.toSessionVibrationErrorMessage(): IntifaceUiMessage {
        val detail = message?.takeIf { it.isNotBlank() }
            ?: cause?.message?.takeIf { it.isNotBlank() }
        return if (this is ButtplugCommandRejectedException) {
            if (detail == null) IntifaceUiMessage(IntifaceMessage.CommandRejected)
            else IntifaceUiMessage(IntifaceMessage.CommandRejectedDetail, listOf(detail))
        } else {
            if (detail == null) IntifaceUiMessage(IntifaceMessage.UnableToConnect)
            else IntifaceUiMessage(IntifaceMessage.UnableToConnectDetail, listOf(detail))
        }
    }

    private companion object {
        const val SCAN_DURATION_MS = 5_000L
        const val CONNECT_TIMEOUT_MS = 8_000L
        const val TEST_PULSE_COUNT = 3
        const val TEST_VIBRATION_STRENGTH = 0.6
        const val TEST_PULSE_DURATION_MS = 180L
        const val TEST_PULSE_PAUSE_MS = 180L
        const val COMMAND_TIMEOUT_MS = 750L
        const val COMMAND_REJECTED_FALLBACK = "Command rejected"
        const val UNEXPECTED_RESPONSE_PREFIX = "Unexpected response: "
    }
}

private class ButtplugCommandRejectedException(message: String) : RuntimeException(message)
