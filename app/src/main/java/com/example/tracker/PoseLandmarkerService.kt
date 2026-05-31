package com.example.tracker

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class PoseLandmarkerService(
    private val context: Context,
    private val listener: LandmarkerListener
) {
    private val TAG = "PoseLandmarkerService"
    private val lifecycleLock = Any()
    private val inferenceExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PoseLandmarkerInferenceThread").apply { isDaemon = true }
    }
    @Volatile private var isClosed = false
    @Volatile private var poseLandmarker: PoseLandmarker? = null
    @Volatile private var activeDelegate: Delegate? = null

    interface LandmarkerListener {
        fun onError(error: String)
        fun onResults(result: PoseLandmarks, imageWidth: Int, imageHeight: Int, timestampMs: Long)
    }

    init {
        setupLandmarker()
    }

    private fun setupLandmarker() {
        runOnInferenceThread("initialize Pose Landmarker") {
            initializeRealLandmarker()
        }
    }

    private fun initializeRealLandmarker() {
        if (isClosed) return

        Log.i(TAG, "Initializing MediaPipe Pose Landmarker from assets with delegate=GPU")
        val landmarker = try {
            createLandmarker(Delegate.GPU).also {
                activeDelegate = Delegate.GPU
                Log.i(TAG, "Pose Landmarker loaded successfully with delegate=${Delegate.GPU}")
            }
        } catch (gpuError: Throwable) {
            Log.w(TAG, "Failed to initialize Pose Landmarker with delegate=GPU, falling back to CPU", gpuError)
            if (isClosed) return
            try {
                createLandmarker(Delegate.CPU).also {
                    activeDelegate = Delegate.CPU
                    Log.i(TAG, "Pose Landmarker loaded successfully with delegate=${Delegate.CPU}")
                }
            } catch (cpuError: Throwable) {
                activeDelegate = null
                Log.e(TAG, "Failed to initialize Pose Landmarker with delegate=CPU", cpuError)
                deliverError(cpuError.message ?: "Failed to initialize MediaPipe")
                return
            }
        }

        synchronized(lifecycleLock) {
            if (isClosed) {
                landmarker.close()
            } else {
                poseLandmarker = landmarker
            }
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
                if (!isClosed) {
                    processResult(result, image.width, image.height, result.timestampMs())
                }
            }
            .setErrorListener { error ->
                Log.e(TAG, "MediaPipe error: ${error.message}")
                deliverError(error.message ?: "Unknown MediaPipe error")
            }
            .build()

        return PoseLandmarker.createFromOptions(context, options)
    }

    fun detectLiveStreamFrame(bitmap: Bitmap, timestamp: Long): Boolean {
        if (isClosed) return false
        if (poseLandmarker == null) return false

        return runOnInferenceThread("detect pose frame") {
            val landmarker = poseLandmarker ?: return@runOnInferenceThread

            try {
                val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
                landmarker.detectAsync(mpImage, timestamp)
            } catch (t: Throwable) {
                Log.e(TAG, "Error in detectLiveStreamFrame delegate=$activeDelegate", t)
                deliverError(t.message ?: "MediaPipe detect error")
            }
        }
    }

    private fun processResult(result: PoseLandmarkerResult, width: Int, height: Int, timestampMs: Long) {
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

    private fun deliverError(error: String) {
        synchronized(lifecycleLock) {
            if (!isClosed) {
                listener.onError(error)
            }
        }
    }

    fun close() {
        synchronized(lifecycleLock) {
            if (isClosed) return
            isClosed = true
        }

        try {
            inferenceExecutor.execute {
                try {
                    poseLandmarker?.close()
                } catch (t: Throwable) {
                    Log.e(TAG, "Error closing Pose Landmarker delegate=$activeDelegate", t)
                } finally {
                    poseLandmarker = null
                    activeDelegate = null
                    inferenceExecutor.shutdown()
                }
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "Pose Landmarker inference executor rejected close", e)
            poseLandmarker = null
            activeDelegate = null
            inferenceExecutor.shutdown()
        }
    }

    private fun runOnInferenceThread(operation: String, block: () -> Unit): Boolean {
        if (isClosed || inferenceExecutor.isShutdown) return false
        return try {
            inferenceExecutor.execute(block)
            true
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "Pose Landmarker inference executor rejected operation=$operation", e)
            false
        }
    }
}
