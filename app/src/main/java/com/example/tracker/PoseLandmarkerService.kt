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
    private var poseLandmarker: PoseLandmarker? = null
    var isSimulated: Boolean = false
        private set

    interface LandmarkerListener {
        fun onError(error: String)
        fun onResults(result: PoseLandmarks, imageWidth: Int, imageHeight: Int)
    }

    init {
        setupLandmarker()
    }

    private fun setupLandmarker() {
        try {
            // Check if the asset exists
            val assetExists = try {
                context.assets.open("pose_landmarker_full.task").close()
                true
            } catch (e: Exception) {
                false
            }

            if (!assetExists) {
                Log.w(TAG, "pose_landmarker_full.task not found in assets. Falling back to Simulated landmarks.")
                isSimulated = true
                return
            }

            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("pose_landmarker_full.task")
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, image ->
                    processResult(result, image.width, image.height)
                }
                .setErrorListener { error ->
                    Log.e(TAG, "MediaPipe error: ${error.message}")
                    listener.onError(error.message ?: "Unknown MediaPipe error")
                }
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            Log.i(TAG, "MediaPipe Pose Landmarker loaded successfully!")
            isSimulated = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPipe Pose Landmarker: ${e.message}. Using Simulated landmarks.", e)
            isSimulated = true
        }
    }

    fun detectLiveStreamFrame(imageProxy: androidx.camera.core.ImageProxy, timestamp: Long) {
        if (isSimulated) {
            // In simulation, we bypass real frame execution and generate matching pose landmarks
            val simulatedPose = generateSimulatedPose(timestamp)
            listener.onResults(simulatedPose, imageProxy.width, imageProxy.height)
            return
        }

        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            // Note: In real stream, we can pass to MediaPipe.
            // But since this is a heavy task and might crash on emulator, we ensure safety.
            // If poseLandmarker is available, map inputs.
            val landmarker = poseLandmarker
            if (landmarker != null) {
                val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
                landmarker.detectAsync(mpImage, timestamp)
            } else {
                val simulatedPose = generateSimulatedPose(timestamp)
                listener.onResults(simulatedPose, imageProxy.width, imageProxy.height)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in detectLiveStreamFrame", e)
        }
    }

    private fun processResult(result: PoseLandmarkerResult, width: Int, height: Int) {
        val landmarksList = result.landmarks()
        if (landmarksList.isNullOrEmpty()) {
            return
        }

        val firstLandmarks = landmarksList[0]
        if (firstLandmarks.size < 33) return

        val pose = PoseLandmarks(
            leftShoulder = Point3D(firstLandmarks[11].x(), firstLandmarks[11].y(), firstLandmarks[11].z()),
            rightShoulder = Point3D(firstLandmarks[12].x(), firstLandmarks[12].y(), firstLandmarks[12].z()),
            leftElbow = Point3D(firstLandmarks[13].x(), firstLandmarks[13].y(), firstLandmarks[13].z()),
            rightElbow = Point3D(firstLandmarks[14].x(), firstLandmarks[14].y(), firstLandmarks[14].z()),
            leftHip = Point3D(firstLandmarks[23].x(), firstLandmarks[23].y(), firstLandmarks[23].z()),
            rightHip = Point3D(firstLandmarks[24].x(), firstLandmarks[24].y(), firstLandmarks[24].z()),
            leftKnee = Point3D(firstLandmarks[25].x(), firstLandmarks[25].y(), firstLandmarks[25].z()),
            rightKnee = Point3D(firstLandmarks[26].x(), firstLandmarks[26].y(), firstLandmarks[26].z())
        )

        listener.onResults(pose, width, height)
    }

    // Generate simulated ideal posture with minor noise
    fun generateSimulatedPose(
        timestamp: Long,
        driftOffset: Float = 0f,
        motionOffset: Float = 0f
    ): PoseLandmarks {
        // Human kneeling facing away model parameters
        // Add tiny breathing oscillations to emulate realistic noise
        val breathe = kotlin.math.sin(timestamp.toDouble() / 500.0).toFloat() * 0.005f
        val driftX = driftOffset
        val driftY = driftOffset * 0.5f

        // Random jitter (motion noise)
        val jitterX = ((timestamp % 17).toFloat() / 1700f) * motionOffset
        val jitterY = ((timestamp % 13).toFloat() / 1300f) * motionOffset

        val offsetTotalX = driftX + jitterX
        val offsetTotalY = driftY + jitterY + breathe

        return PoseLandmarks(
            leftShoulder = Point3D(0.40f + offsetTotalX, 0.35f + offsetTotalY, 0.1f),
            rightShoulder = Point3D(0.60f + offsetTotalX, 0.35f + offsetTotalY, 0.1f),
            leftElbow = Point3D(0.35f + offsetTotalX, 0.45f + offsetTotalY, 0.2f),
            rightElbow = Point3D(0.65f + offsetTotalX, 0.45f + offsetTotalY, 0.2f),
            leftHip = Point3D(0.42f + offsetTotalX, 0.60f + offsetTotalY, 0.0f),
            rightHip = Point3D(0.58f + offsetTotalX, 0.60f + offsetTotalY, 0.0f),
            leftKnee = Point3D(0.45f + offsetTotalX, 0.80f + offsetTotalY, -0.1f),
            rightKnee = Point3D(0.55f + offsetTotalX, 0.80f + offsetTotalY, -0.1f)
        )
    }

    private fun imageProxyToBitmap(image: androidx.camera.core.ImageProxy): Bitmap {
        val planeProxy = image.planes[0]
        val buffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    fun close() {
        poseLandmarker?.close()
    }
}
