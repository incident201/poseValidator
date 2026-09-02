package com.incident201.poseguard.tracker

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class AccelerationMode {
    Auto,
    Gpu,
    Cpu
}

enum class PoseLandmarkerModel(val assetPath: String) {
    Heavy("pose_landmarker_heavy.task"),
    Full("pose_landmarker_full.task")
}

sealed interface AccelerationState {
    data object InitializingCpu : AccelerationState
    data object Cpu : AccelerationState
    data class InitializingGpu(val elapsedMs: Long = 0L) : AccelerationState
    data object Gpu : AccelerationState
    data class CpuFallback(val reason: String) : AccelerationState
    data class Error(val reason: String) : AccelerationState
}

class PoseLandmarkerService(
    context: Context,
    initialAccelerationMode: AccelerationMode,
    private val model: PoseLandmarkerModel,
    private val listener: LandmarkerListener
) {
    private val appContext = context.applicationContext
    private val stateLock = Any()
    private val accelerationPrefs = appContext.getSharedPreferences(
        ACCELERATION_PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val watchdogExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "pose-landmarker-watchdog")
    }

    @Volatile private var isClosed = false
    @Volatile private var activeBackend: LandmarkerBackend? = null

    private var accelerationMode = initialAccelerationMode
    private var cpuBackend: LandmarkerBackend? = null
    private var gpuBackend: LandmarkerBackend? = null
    private var gpuAttempted = false
    private var cpuFallbackReason: String? = null

    interface LandmarkerListener {
        fun onError(error: String)
        fun onResults(result: PoseLandmarks, imageWidth: Int, imageHeight: Int, timestampMs: Long)
        fun onFrameDropped(timestampMs: Long)
        fun onAccelerationStateChanged(state: AccelerationState)
    }

    init {
        synchronized(stateLock) {
            if (initialAccelerationMode == AccelerationMode.Auto) {
                loadCachedGpuFailure()?.let { reason ->
                    gpuAttempted = true
                    cpuFallbackReason = "GPU skipped: $reason"
                    Log.i(
                        TAG,
                        "gpu_probe_skipped cachedResult=unavailable reason=$reason " +
                            "signature=${currentGpuCompatibilitySignature()}"
                    )
                }
            }
            when (initialAccelerationMode) {
                AccelerationMode.Gpu -> startGpuBackendLocked()
                AccelerationMode.Auto,
                AccelerationMode.Cpu -> startCpuBackendLocked()
            }
        }
    }

    fun setAccelerationMode(mode: AccelerationMode) {
        val backendsToClose = mutableListOf<LandmarkerBackend>()
        synchronized(stateLock) {
            if (isClosed || accelerationMode == mode) return
            Log.i(TAG, "acceleration_mode_change from=$accelerationMode to=$mode")
            accelerationMode = mode
            cpuFallbackReason = null

            when (mode) {
                AccelerationMode.Auto -> {
                    clearCachedGpuCompatibility()
                    gpuAttempted = false
                    val currentCpu = cpuBackend
                    when {
                        activeBackend?.delegate == Delegate.GPU -> {
                            cacheGpuSupported()
                            notifyAccelerationState(AccelerationState.Gpu)
                        }
                        currentCpu?.isReady == true -> {
                            activeBackend = currentCpu
                            reportActiveCpuLocked()
                            startGpuBackendLocked()
                        }
                        else -> startCpuBackendLocked()
                    }
                }

                AccelerationMode.Gpu -> {
                    if (activeBackend?.delegate == Delegate.GPU) {
                        cacheGpuSupported()
                        notifyAccelerationState(AccelerationState.Gpu)
                    } else {
                        clearCachedGpuCompatibility()
                        gpuAttempted = false
                        val currentCpu = cpuBackend
                        if (currentCpu?.isReady != true) {
                            cpuBackend = null
                            currentCpu?.let(backendsToClose::add)
                        }
                        startGpuBackendLocked()
                    }
                }

                AccelerationMode.Cpu -> {
                    val currentGpu = gpuBackend
                    gpuBackend = null
                    if (activeBackend === currentGpu || activeBackend?.delegate == Delegate.GPU) {
                        activeBackend = null
                    }
                    currentGpu?.let(backendsToClose::add)

                    val currentCpu = cpuBackend
                    if (currentCpu?.isReady == true) {
                        activeBackend = currentCpu
                        reportActiveCpuLocked()
                    } else {
                        startCpuBackendLocked()
                    }
                }
            }
        }
        backendsToClose.distinct().forEach(LandmarkerBackend::close)
    }

    fun detectLiveStreamFrame(bitmap: Bitmap, timestamp: Long): Boolean {
        if (isClosed) return false
        val backend = activeBackend ?: return false
        return backend.detect(bitmap, timestamp)
    }

    private fun startCpuBackendLocked() {
        if (isClosed) return
        val existing = cpuBackend
        if (existing != null && !existing.isClosing) return

        notifyAccelerationState(AccelerationState.InitializingCpu)
        LandmarkerBackend(Delegate.CPU).also { backend ->
            cpuBackend = backend
            backend.initialize()
        }
    }

    private fun startGpuBackendLocked() {
        if (isClosed || accelerationMode == AccelerationMode.Cpu || gpuAttempted) return
        val existing = gpuBackend
        if (existing != null && !existing.isClosing) return

        gpuAttempted = true
        Log.i(TAG, "gpu_probe_start activeDelegate=${activeBackend?.delegate} model=${model.assetPath}")
        notifyAccelerationState(AccelerationState.InitializingGpu())
        LandmarkerBackend(Delegate.GPU).also { backend ->
            gpuBackend = backend
            backend.initialize()
            scheduleGpuInitializationWatchdog(backend)
        }
    }

    private fun startGpuAfterFirstCpuResult(cpu: LandmarkerBackend) {
        synchronized(stateLock) {
            if (isClosed ||
                accelerationMode != AccelerationMode.Auto ||
                gpuAttempted ||
                activeBackend !== cpu ||
                cpuBackend !== cpu
            ) {
                return
            }
            Log.i(TAG, "gpu_probe_trigger reason=first_cpu_result; CPU remains active during GPU warm-up")
            startGpuBackendLocked()
        }
    }

    private fun scheduleGpuInitializationWatchdog(backend: LandmarkerBackend) {
        GPU_WATCHDOG_LOG_SECONDS.forEach { delaySeconds ->
            watchdogExecutor.schedule({
                synchronized(stateLock) {
                    if (isClosed || gpuBackend !== backend || backend.isReady) return@schedule
                    val elapsedMs = backend.initializationElapsedMs()
                    Log.w(
                        TAG,
                        "delegate_init_pending delegate=GPU elapsedMs=$elapsedMs " +
                            "stage=${backend.initializationStage} workerState=${backend.workerState}"
                    )
                    notifyAccelerationState(AccelerationState.InitializingGpu(elapsedMs))
                }
            }, delaySeconds, TimeUnit.SECONDS)
        }

        watchdogExecutor.schedule({
            val timedOut = synchronized(stateLock) {
                if (isClosed || gpuBackend !== backend || backend.isReady) {
                    false
                } else {
                    gpuBackend = null
                    cpuFallbackReason = "GPU init timeout"
                    cacheGpuUnavailable("GPU init timeout")
                    val currentCpu = cpuBackend
                    if (currentCpu?.isReady == true && activeBackend === currentCpu) {
                        reportActiveCpuLocked()
                    } else {
                        startCpuBackendLocked()
                    }
                    true
                }
            }
            if (timedOut) {
                val elapsedMs = backend.initializationElapsedMs()
                Log.e(
                    TAG,
                    "delegate_init_timeout delegate=GPU elapsedMs=$elapsedMs " +
                        "stage=${backend.initializationStage} workerState=${backend.workerState}; falling back to CPU"
                )
                backend.close()
            }
        }, GPU_INITIALIZATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun onBackendReady(backend: LandmarkerBackend) {
        val backendsToClose = mutableListOf<LandmarkerBackend>()
        synchronized(stateLock) {
            if (isClosed) {
                backendsToClose += backend
            } else {
                when (backend.delegate) {
                    Delegate.CPU -> {
                        if (cpuBackend !== backend) {
                            backendsToClose += backend
                        } else {
                            activeBackend = backend
                            reportActiveCpuLocked()
                            if (accelerationMode == AccelerationMode.Auto && !gpuAttempted) {
                                Log.i(TAG, "gpu_probe_armed waitingFor=first_cpu_result")
                            } else if (accelerationMode != AccelerationMode.Auto) {
                                gpuBackend?.let(backendsToClose::add)
                                gpuBackend = null
                            }
                        }
                    }

                    Delegate.GPU -> {
                        if (gpuBackend !== backend || accelerationMode == AccelerationMode.Cpu) {
                            if (gpuBackend === backend) gpuBackend = null
                            backendsToClose += backend
                        } else {
                            val previous = activeBackend
                            activeBackend = backend
                            if (previous?.delegate == Delegate.CPU) {
                                if (cpuBackend === previous) cpuBackend = null
                                backendsToClose += previous
                            }
                            cpuFallbackReason = null
                            cacheGpuSupported()
                            Log.i(
                                TAG,
                                "delegate_active delegate=GPU initDurationMs=${backend.initializationElapsedMs()}"
                            )
                            notifyAccelerationState(AccelerationState.Gpu)
                        }
                    }

                    else -> backendsToClose += backend
                }
            }
        }
        backendsToClose.distinct().forEach(LandmarkerBackend::close)
    }

    private fun onBackendInitializationFailed(backend: LandmarkerBackend, error: Throwable) {
        val shouldNotify: Boolean
        synchronized(stateLock) {
            when (backend.delegate) {
                Delegate.CPU -> {
                    if (cpuBackend === backend) cpuBackend = null
                    if (activeBackend === backend) activeBackend = null
                    notifyAccelerationState(
                        AccelerationState.Error(error.message ?: "CPU initialization failed")
                    )
                    shouldNotify = true
                }

                Delegate.GPU -> {
                    if (gpuBackend === backend) gpuBackend = null
                    if (activeBackend === backend) activeBackend = null
                    val reason = compactError(error)
                    cpuFallbackReason = reason
                    cacheGpuUnavailable(reason)
                    if (accelerationMode != AccelerationMode.Cpu) {
                        val currentCpu = cpuBackend
                        if (currentCpu?.isReady == true && activeBackend === currentCpu) {
                            reportActiveCpuLocked()
                        } else {
                            startCpuBackendLocked()
                        }
                    } else {
                        notifyAccelerationState(AccelerationState.CpuFallback(reason))
                    }
                    shouldNotify = accelerationMode == AccelerationMode.Cpu
                }

                else -> shouldNotify = true
            }
        }

        if (backend.delegate == Delegate.GPU) {
            Log.w(
                TAG,
                "delegate_init_failed delegate=GPU durationMs=${backend.initializationElapsedMs()} " +
                    "stage=${backend.initializationStage} error=${error.javaClass.simpleName}: ${error.message}; " +
                    "falling back to CPU",
                error
            )
        } else {
            Log.e(
                TAG,
                "delegate_init_failed delegate=CPU durationMs=${backend.initializationElapsedMs()} " +
                    "stage=${backend.initializationStage}",
                error
            )
        }
        if (shouldNotify && !isClosed) {
            deliverError(error.message ?: "Failed to initialize MediaPipe")
        }
        backend.close()
    }

    private fun onBackendRuntimeError(backend: LandmarkerBackend, error: RuntimeException) {
        if (isClosed || activeBackend !== backend) return

        if (backend.delegate != Delegate.GPU) {
            Log.e(TAG, "delegate_runtime_failed delegate=CPU", error)
            notifyAccelerationState(AccelerationState.Error(compactError(error)))
            deliverError(error.message ?: "MediaPipe error")
            return
        }

        synchronized(stateLock) {
            if (activeBackend === backend) activeBackend = null
            if (gpuBackend === backend) gpuBackend = null
            cpuFallbackReason = compactError(error)
            cacheGpuUnavailable(cpuFallbackReason ?: "GPU runtime error")
            if (accelerationMode != AccelerationMode.Cpu) {
                startCpuBackendLocked()
            }
        }
        Log.w(TAG, "delegate_runtime_failed delegate=GPU; falling back to CPU", error)
        backend.close()
    }

    private fun createLandmarker(backend: LandmarkerBackend): PoseLandmarker {
        backend.initializationStage = "building_options"
        Log.i(
            TAG,
            "delegate_init_start delegate=${backend.delegate} mode=$accelerationMode " +
                "thread=${Thread.currentThread().name} model=${model.assetPath} gpuCache=disabled"
        )
        val baseOptionsBuilder = BaseOptions.builder()
            .setModelAssetPath(model.assetPath)
            .setDelegate(backend.delegate)

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptionsBuilder.build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.70f)
            .setMinPosePresenceConfidence(0.70f)
            .setMinTrackingConfidence(0.75f)
            .setResultListener { result, image ->
                val timestampMs = result.timestampMs()
                if (isClosed || activeBackend !== backend) {
                    dropFrame(timestampMs)
                } else {
                    backend.logFirstResult(timestampMs)
                    processResult(result, image.width, image.height, timestampMs)
                    if (backend.delegate == Delegate.CPU) {
                        startGpuAfterFirstCpuResult(backend)
                    }
                }
            }
            .setErrorListener { error -> onBackendRuntimeError(backend, error) }
            .build()

        backend.initializationStage = "create_from_options"
        return PoseLandmarker.createFromOptions(appContext, options)
            .also { backend.initializationStage = "created" }
    }

    private fun processResult(
        result: PoseLandmarkerResult,
        width: Int,
        height: Int,
        timestampMs: Long
    ) {
        if (isClosed) return
        val landmarksList = result.landmarks()
        if (landmarksList.isNullOrEmpty()) {
            deliverResults(PoseLandmarks(), width, height, timestampMs)
            return
        }

        val firstLandmarks = landmarksList[0]
        if (firstLandmarks.size < 33) {
            deliverResults(PoseLandmarks(), width, height, timestampMs)
            return
        }

        fun toPosePoint(index: Int): Point3D {
            val landmark = firstLandmarks[index]
            return Point3D(
                x = landmark.x(),
                y = landmark.y(),
                z = landmark.z(),
                visibility = landmark.visibility().orElse(null),
                presence = landmark.presence().orElse(null)
            )
        }

        val allLandmarks = firstLandmarks.indices.map(::toPosePoint)
        deliverResults(
            PoseLandmarks(
                leftShoulder = allLandmarks[11],
                rightShoulder = allLandmarks[12],
                leftElbow = allLandmarks[13],
                rightElbow = allLandmarks[14],
                leftHip = allLandmarks[23],
                rightHip = allLandmarks[24],
                leftKnee = allLandmarks[25],
                rightKnee = allLandmarks[26],
                allLandmarks = allLandmarks
            ),
            width,
            height,
            timestampMs
        )
    }

    private fun deliverResults(pose: PoseLandmarks, width: Int, height: Int, timestampMs: Long) {
        if (!isClosed) listener.onResults(pose, width, height, timestampMs)
    }

    private fun deliverError(error: String) {
        if (!isClosed) listener.onError(error)
    }

    private fun dropFrame(timestampMs: Long) {
        if (!isClosed) listener.onFrameDropped(timestampMs)
    }

    private fun notifyAccelerationState(state: AccelerationState) {
        if (!isClosed) listener.onAccelerationStateChanged(state)
    }

    private fun reportActiveCpuLocked() {
        Log.i(TAG, "delegate_active delegate=CPU fallbackReason=${cpuFallbackReason ?: "none"}")
        notifyAccelerationState(
            cpuFallbackReason?.let { AccelerationState.CpuFallback(it) } ?: AccelerationState.Cpu
        )
    }

    private fun compactError(error: Throwable): String {
        val message = error.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return if (message.isBlank()) error.javaClass.simpleName else message.take(160)
    }

    private fun loadCachedGpuFailure(): String? {
        val signature = accelerationPrefs.getString(PREF_GPU_SIGNATURE, null)
        if (signature != currentGpuCompatibilitySignature()) return null
        if (accelerationPrefs.getString(PREF_GPU_RESULT, null) != GPU_RESULT_UNAVAILABLE) return null
        return accelerationPrefs.getString(PREF_GPU_FAILURE_REASON, null)
            ?.takeIf { it.isNotBlank() }
            ?: "previous initialization failure"
    }

    private fun cacheGpuSupported() {
        accelerationPrefs.edit()
            .putString(PREF_GPU_SIGNATURE, currentGpuCompatibilitySignature())
            .putString(PREF_GPU_RESULT, GPU_RESULT_SUPPORTED)
            .remove(PREF_GPU_FAILURE_REASON)
            .apply()
        Log.i(TAG, "gpu_probe_cache result=supported")
    }

    private fun cacheGpuUnavailable(reason: String) {
        accelerationPrefs.edit()
            .putString(PREF_GPU_SIGNATURE, currentGpuCompatibilitySignature())
            .putString(PREF_GPU_RESULT, GPU_RESULT_UNAVAILABLE)
            .putString(PREF_GPU_FAILURE_REASON, reason.take(160))
            .apply()
        Log.i(TAG, "gpu_probe_cache result=unavailable reason=${reason.take(160)}")
    }

    private fun clearCachedGpuCompatibility() {
        accelerationPrefs.edit().clear().apply()
        Log.i(TAG, "gpu_probe_cache clearedForManualRetry=true")
    }

    private fun currentGpuCompatibilitySignature(): String =
        "${Build.FINGERPRINT}|$GPU_PROBE_VERSION|${model.assetPath}"

    fun close() {
        val backends = synchronized(stateLock) {
            if (isClosed) return
            isClosed = true
            val current = listOfNotNull(cpuBackend, gpuBackend, activeBackend).distinct()
            cpuBackend = null
            gpuBackend = null
            activeBackend = null
            current
        }
        backends.forEach(LandmarkerBackend::close)
        watchdogExecutor.shutdownNow()
    }

    private inner class LandmarkerBackend(val delegate: Delegate) {
        @Volatile private var workerThread: Thread? = null
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "pose-landmarker-${delegate.name.lowercase()}").also {
                workerThread = it
            }
        }
        private val ready = AtomicBoolean(false)
        private val closing = AtomicBoolean(false)
        private val firstFrameSubmitted = AtomicBoolean(false)
        private val firstResultDelivered = AtomicBoolean(false)
        private var landmarker: PoseLandmarker? = null
        private var initializationStartedAtMs: Long = 0L

        @Volatile var initializationStage: String = "queued"

        val isReady: Boolean get() = ready.get()
        val isClosing: Boolean get() = closing.get()
        val workerState: String get() = workerThread?.state?.name ?: "not_started"

        fun initializationElapsedMs(): Long =
            (SystemClock.elapsedRealtime() - initializationStartedAtMs).coerceAtLeast(0L)

        fun initialize() {
            initializationStartedAtMs = SystemClock.elapsedRealtime()
            try {
                executor.execute {
                    try {
                        val created = createLandmarker(this)
                        if (isClosed || closing.get()) {
                            created.close()
                            return@execute
                        }
                        landmarker = created
                        ready.set(true)
                        Log.i(
                            TAG,
                            "delegate_init_success delegate=$delegate durationMs=${initializationElapsedMs()} " +
                                "thread=${Thread.currentThread().name}"
                        )
                        onBackendReady(this)
                    } catch (error: Throwable) {
                        onBackendInitializationFailed(this, error)
                    }
                }
            } catch (error: RejectedExecutionException) {
                onBackendInitializationFailed(this, error)
            }
        }

        fun detect(bitmap: Bitmap, timestamp: Long): Boolean {
            if (!ready.get() || closing.get() || activeBackend !== this) return false
            return try {
                executor.execute {
                    if (isClosed || closing.get() || activeBackend !== this || bitmap.isRecycled) {
                        dropFrame(timestamp)
                        return@execute
                    }

                    val currentLandmarker = landmarker
                    if (currentLandmarker == null) {
                        dropFrame(timestamp)
                        return@execute
                    }

                    try {
                        if (firstFrameSubmitted.compareAndSet(false, true)) {
                            Log.i(TAG, "delegate_first_frame delegate=$delegate timestampMs=$timestamp")
                        }
                        currentLandmarker.detectAsync(BitmapImageBuilder(bitmap).build(), timestamp)
                    } catch (error: Throwable) {
                        Log.e(TAG, "Error in detectLiveStreamFrame with $delegate delegate", error)
                        dropFrame(timestamp)
                        if (error is RuntimeException) {
                            onBackendRuntimeError(this, error)
                        } else {
                            deliverError(error.message ?: "MediaPipe detect error")
                        }
                    }
                }
                true
            } catch (_: RejectedExecutionException) {
                false
            }
        }

        fun logFirstResult(timestampMs: Long) {
            if (firstResultDelivered.compareAndSet(false, true)) {
                Log.i(TAG, "delegate_first_result delegate=$delegate timestampMs=$timestampMs")
            }
        }

        fun close(onClosed: (() -> Unit)? = null) {
            if (!closing.compareAndSet(false, true)) return
            ready.set(false)
            try {
                executor.execute {
                    val closeStartedAtMs = SystemClock.elapsedRealtime()
                    Log.i(TAG, "delegate_close_start delegate=$delegate")
                    val currentLandmarker = landmarker
                    landmarker = null
                    runCatching { currentLandmarker?.close() }
                        .onFailure { error ->
                            Log.w(TAG, "Failed to close MediaPipe $delegate delegate", error)
                        }
                    Log.i(
                        TAG,
                        "delegate_close_complete delegate=$delegate durationMs=" +
                            (SystemClock.elapsedRealtime() - closeStartedAtMs).coerceAtLeast(0L)
                    )
                    onClosed?.invoke()
                }
            } catch (_: RejectedExecutionException) {
                // The executor is already terminating and no landmarker can still accept work.
            } finally {
                executor.shutdown()
            }
        }
    }

    private companion object {
        const val TAG = "PoseLandmarkerService"
        const val GPU_INITIALIZATION_TIMEOUT_SECONDS = 15L
        val GPU_WATCHDOG_LOG_SECONDS = listOf(3L, 5L, 10L)
        const val ACCELERATION_PREFS_NAME = "pose_landmarker_acceleration"
        const val PREF_GPU_SIGNATURE = "gpu_signature"
        const val PREF_GPU_RESULT = "gpu_result"
        const val PREF_GPU_FAILURE_REASON = "gpu_failure_reason"
        const val GPU_RESULT_SUPPORTED = "supported"
        const val GPU_RESULT_UNAVAILABLE = "unavailable"
        const val GPU_PROBE_VERSION = "mediapipe-1.0.0|no-direct-opencl-v1"
    }
}
