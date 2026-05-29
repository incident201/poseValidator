package com.example.tracker

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class PoseLandmarkerService(
    private val context: Context,
    private val listener: LandmarkerListener
) {
    private val TAG = "PoseLandmarkerService"
    private val lifecycleLock = Any()
    @Volatile private var isClosed = false
    private var poseLandmarker: PoseLandmarker? = null

    interface LandmarkerListener {
        fun onError(error: String)
        fun onResults(result: PoseLandmarks, imageWidth: Int, imageHeight: Int, timestampMs: Long)
    }

    init {
        setupLandmarker()
    }

    private fun setupLandmarker() {
        initializeRealLandmarker()
    }

    private fun initializeRealLandmarker() {
        try {
            Log.i(TAG, "Initializing MediaPipe Pose Landmarker from assets")
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("pose_landmarker_heavy.task")
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
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

            synchronized(lifecycleLock) {
                if (isClosed) return
                poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            }
            Log.i(TAG, "MediaPipe Pose Landmarker loaded successfully from assets")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize MediaPipe Pose Landmarker", t)
            deliverError(t.message ?: "Failed to initialize MediaPipe")
        }
    }

    fun detectLiveStreamFrame(bitmap: Bitmap, timestamp: Long): Boolean {
        return synchronized(lifecycleLock) {
            if (isClosed) return@synchronized false
            val landmarker = poseLandmarker ?: return@synchronized false

            try {
                val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
                landmarker.detectAsync(mpImage, timestamp)
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Error in detectLiveStreamFrame", t)
                deliverError(t.message ?: "MediaPipe detect error")
                false
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

        val allLandmarks = firstLandmarks.map { landmark ->
            Point3D(landmark.x(), landmark.y(), landmark.z())
        }
        Log.d(TAG, "MediaPipe returned allLandmarks=${allLandmarks.size}")

        val pose = PoseLandmarks(
            leftShoulder = Point3D(firstLandmarks[11].x(), firstLandmarks[11].y(), firstLandmarks[11].z()),
            rightShoulder = Point3D(firstLandmarks[12].x(), firstLandmarks[12].y(), firstLandmarks[12].z()),
            leftElbow = Point3D(firstLandmarks[13].x(), firstLandmarks[13].y(), firstLandmarks[13].z()),
            rightElbow = Point3D(firstLandmarks[14].x(), firstLandmarks[14].y(), firstLandmarks[14].z()),
            leftHip = Point3D(firstLandmarks[23].x(), firstLandmarks[23].y(), firstLandmarks[23].z()),
            rightHip = Point3D(firstLandmarks[24].x(), firstLandmarks[24].y(), firstLandmarks[24].z()),
            leftKnee = Point3D(firstLandmarks[25].x(), firstLandmarks[25].y(), firstLandmarks[25].z()),
            rightKnee = Point3D(firstLandmarks[26].x(), firstLandmarks[26].y(), firstLandmarks[26].z()),
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
        val landmarker = synchronized(lifecycleLock) {
            if (isClosed && poseLandmarker == null) return
            isClosed = true
            val current = poseLandmarker
            poseLandmarker = null
            current
        }
        landmarker?.close()
    }
}
