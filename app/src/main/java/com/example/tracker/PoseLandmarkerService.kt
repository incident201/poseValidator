package com.example.tracker

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

enum class PoseLandmarkerDelegateMode {
    Initializing,
    GPU,
    CPU,
    Unavailable
}

class PoseLandmarkerService(
    private val context: Context,
    private val listener: LandmarkerListener
) {
    private val TAG = "PoseLandmarkerService"
    private val lifecycleLock = Any()
    @Volatile private var isClosed = false
    @Volatile private var activeDelegate: Delegate? = null
    private var poseLandmarker: PoseLandmarker? = null
    private var submittedSinceLastResult = 0
    private var totalSubmittedFrames = 0L
    private var totalResultCallbacks = 0L
    private var totalRuntimeErrors = 0L
    private var pendingCpuFallbackReason: String? = null

    private companion object {
        private const val GPU_FRAMES_WITHOUT_RESULT_LIMIT = 30
    }

    interface LandmarkerListener {
        fun onError(error: String)
        fun onDelegateModeChanged(mode: PoseLandmarkerDelegateMode)
        fun onResults(result: PoseLandmarks, imageWidth: Int, imageHeight: Int, timestampMs: Long)
    }

    init {
        setupLandmarker()
    }

    private fun setupLandmarker() {
        initializeRealLandmarker()
    }

    private fun initializeRealLandmarker() {
        notifyDelegateMode(PoseLandmarkerDelegateMode.Initializing)
        Log.i(TAG, "Initializing Pose Landmarker with delegate=GPU")

        try {
            val gpuLandmarker = createLandmarker(Delegate.GPU)
            synchronized(lifecycleLock) {
                if (isClosed) {
                    gpuLandmarker.close()
                    return
                }
                activeDelegate = Delegate.GPU
                poseLandmarker = gpuLandmarker
            }
            notifyDelegateMode(PoseLandmarkerDelegateMode.GPU)
            Log.i(TAG, "Pose Landmarker loaded with delegate=GPU")
            return
        } catch (gpuError: Throwable) {
            Log.w(TAG, "GPU Pose Landmarker initialization failed, falling back to CPU", gpuError)
        }

        try {
            val cpuLandmarker = createLandmarker(Delegate.CPU)
            synchronized(lifecycleLock) {
                if (isClosed) {
                    cpuLandmarker.close()
                    return
                }
                activeDelegate = Delegate.CPU
                poseLandmarker = cpuLandmarker
            }
            notifyDelegateMode(PoseLandmarkerDelegateMode.CPU)
            Log.i(TAG, "Pose Landmarker loaded with delegate=CPU")
        } catch (cpuError: Throwable) {
            synchronized(lifecycleLock) {
                poseLandmarker = null
                activeDelegate = null
            }
            notifyDelegateMode(PoseLandmarkerDelegateMode.Unavailable)
            Log.e(TAG, "Failed to initialize MediaPipe Pose Landmarker with CPU fallback", cpuError)
            deliverError(cpuError.message ?: "Failed to initialize MediaPipe")
        }
    }

    private fun createLandmarker(delegate: Delegate): PoseLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_heavy.task")
            .setDelegate(delegate)
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.70f)
            .setMinPosePresenceConfidence(0.70f)
            .setMinTrackingConfidence(0.75f)
            .setResultListener { result, image ->
                processResult(result, image.width, image.height, result.timestampMs())
            }
            .setErrorListener { error ->
                val message = error.message ?: "Unknown MediaPipe error"
                Log.e(TAG, "MediaPipe error delegate=$activeDelegate: $message")

                val shouldDeliverError = synchronized(lifecycleLock) {
                    totalRuntimeErrors++
                    if (!isClosed && activeDelegate == Delegate.GPU) {
                        pendingCpuFallbackReason = "MediaPipe errorListener: $message"
                        false
                    } else {
                        activeDelegate != Delegate.GPU
                    }
                }

                if (shouldDeliverError) {
                    deliverError(message)
                }
            }
            .build()

        return PoseLandmarker.createFromOptions(context, options)
    }

    fun detectLiveStreamFrame(bitmap: Bitmap, timestamp: Long): Boolean {
        return synchronized(lifecycleLock) {
            if (isClosed) return@synchronized false

            val fallbackReason = pendingCpuFallbackReason
            if (fallbackReason != null && activeDelegate == Delegate.GPU) {
                val switched = switchToCpu(fallbackReason)
                if (!switched) return@synchronized false
            }

            val landmarker = poseLandmarker ?: return@synchronized false

            try {
                val mpImage = BitmapImageBuilder(bitmap).build()
                landmarker.detectAsync(mpImage, timestamp)
                totalSubmittedFrames++

                if (activeDelegate == Delegate.GPU) {
                    submittedSinceLastResult++

                    if (submittedSinceLastResult >= GPU_FRAMES_WITHOUT_RESULT_LIMIT) {
                        pendingCpuFallbackReason =
                            "GPU produced no result callbacks after $submittedSinceLastResult submitted frames"
                    }
                }

                true
            } catch (t: Throwable) {
                Log.e(TAG, "Error in detectLiveStreamFrame delegate=$activeDelegate", t)

                if (activeDelegate == Delegate.GPU) {
                    fallbackToCpuAndRetry(bitmap, timestamp, t)
                } else {
                    totalRuntimeErrors++
                    deliverError(t.message ?: "MediaPipe detect error")
                    false
                }
            }
        }
    }

    private fun switchToCpu(reason: String): Boolean {
        Log.w(TAG, "Switching Pose Landmarker to CPU: reason=$reason, ${fallbackCounters()}")

        var cpuLandmarker: PoseLandmarker? = null
        return try {
            val gpuLandmarker = poseLandmarker
            poseLandmarker = null
            gpuLandmarker?.close()
            cpuLandmarker = createLandmarker(Delegate.CPU)
            poseLandmarker = cpuLandmarker
            activeDelegate = Delegate.CPU
            submittedSinceLastResult = 0
            pendingCpuFallbackReason = null
            notifyDelegateMode(PoseLandmarkerDelegateMode.CPU)
            true
        } catch (cpuError: Throwable) {
            val counters = fallbackCounters()
            runCatching { cpuLandmarker?.close() }
            poseLandmarker = null
            activeDelegate = null
            submittedSinceLastResult = 0
            pendingCpuFallbackReason = null
            notifyDelegateMode(PoseLandmarkerDelegateMode.Unavailable)
            Log.e(TAG, "CPU fallback failed: reason=$reason, $counters", cpuError)
            deliverError(cpuError.message ?: "MediaPipe CPU fallback failed")
            false
        }
    }

    private fun fallbackToCpuAndRetry(bitmap: Bitmap, timestamp: Long, cause: Throwable): Boolean {
        totalRuntimeErrors++
        Log.w(
            TAG,
            "Runtime GPU detect failed, switching Pose Landmarker to CPU: " +
                "reason=${cause.message ?: cause.javaClass.simpleName}, ${fallbackCounters()}",
            cause
        )

        var cpuLandmarker: PoseLandmarker? = null
        return try {
            val gpuLandmarker = poseLandmarker
            poseLandmarker = null
            gpuLandmarker?.close()
            cpuLandmarker = createLandmarker(Delegate.CPU)
            poseLandmarker = cpuLandmarker
            activeDelegate = Delegate.CPU
            submittedSinceLastResult = 0
            pendingCpuFallbackReason = null
            notifyDelegateMode(PoseLandmarkerDelegateMode.CPU)

            val mpImage = BitmapImageBuilder(bitmap).build()
            cpuLandmarker.detectAsync(mpImage, timestamp)
            totalSubmittedFrames++
            true
        } catch (cpuError: Throwable) {
            val counters = fallbackCounters()
            runCatching { cpuLandmarker?.close() }
            poseLandmarker = null
            activeDelegate = null
            submittedSinceLastResult = 0
            pendingCpuFallbackReason = null
            notifyDelegateMode(PoseLandmarkerDelegateMode.Unavailable)
            Log.e(TAG, "CPU fallback failed: $counters", cpuError)
            deliverError(cpuError.message ?: "MediaPipe CPU fallback failed")
            false
        }
    }

    private fun fallbackCounters(): String {
        return "submitted=$totalSubmittedFrames, " +
            "results=$totalResultCallbacks, " +
            "errors=$totalRuntimeErrors, " +
            "framesWithoutResult=$submittedSinceLastResult"
    }

    private fun processResult(result: PoseLandmarkerResult, width: Int, height: Int, timestampMs: Long) {
        synchronized(lifecycleLock) {
            if (isClosed) return
            submittedSinceLastResult = 0
            totalResultCallbacks++
        }

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

        val allLandmarks = firstLandmarks.indices.map { index ->
            toPosePoint(index)
        }
        Log.d(TAG, "MediaPipe returned allLandmarks=${allLandmarks.size}")

        val pose = PoseLandmarks(
            leftShoulder = allLandmarks[11],
            rightShoulder = allLandmarks[12],
            leftElbow = allLandmarks[13],
            rightElbow = allLandmarks[14],
            leftHip = allLandmarks[23],
            rightHip = allLandmarks[24],
            leftKnee = allLandmarks[25],
            rightKnee = allLandmarks[26],
            allLandmarks = allLandmarks
        )

        deliverResults(pose, width, height, timestampMs)
    }

    private fun deliverResults(pose: PoseLandmarks, width: Int, height: Int, timestampMs: Long) {
        synchronized(lifecycleLock) {
            if (!isClosed) {
                listener.onResults(pose, width, height, timestampMs)
            }
        }
    }

    private fun notifyDelegateMode(mode: PoseLandmarkerDelegateMode) {
        listener.onDelegateModeChanged(mode)
    }

    private fun deliverError(error: String) {
        synchronized(lifecycleLock) {
            if (!isClosed) {
                listener.onError(error)
            }
        }
    }

    fun close() {
        val landmarker = synchronized(lifecycleLock) {
            if (isClosed && poseLandmarker == null) return
            isClosed = true
            val current = poseLandmarker
            poseLandmarker = null
            activeDelegate = null
            submittedSinceLastResult = 0
            pendingCpuFallbackReason = null
            current
        }
        landmarker?.close()
    }
}
