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
    private val inFlightBitmaps = LinkedHashMap<Long, Bitmap>()
    private val inFlightLock = Any()

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
                    val timestampMs = result.timestampMs()
                    try {
                        processResult(result, image.width, image.height, timestampMs)
                    } finally {
                        releaseInFlightBitmap(timestampMs)
                    }
                }
                .setErrorListener { error ->
                    Log.e(TAG, "MediaPipe error: ${error.message}")
                    listener.onError(error.message ?: "Unknown MediaPipe error")
                }
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            Log.i(TAG, "MediaPipe Pose Landmarker loaded successfully from assets")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize MediaPipe Pose Landmarker", t)
            listener.onError(t.message ?: "Failed to initialize MediaPipe")
        }
    }

    fun detectLiveStreamFrame(bitmap: Bitmap, timestamp: Long) {
        val landmarker = poseLandmarker ?: return

        var mediaPipeBitmap: Bitmap? = null
        try {
            mediaPipeBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            registerInFlightBitmap(timestamp, mediaPipeBitmap)

            val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(mediaPipeBitmap).build()
            landmarker.detectAsync(mpImage, timestamp)
            mediaPipeBitmap = null
        } catch (t: Throwable) {
            mediaPipeBitmap?.let { recycleBitmapSafely(it) }
            releaseInFlightBitmap(timestamp)
            Log.e(TAG, "Error in detectLiveStreamFrame", t)
            listener.onError(t.message ?: "MediaPipe detect error")
        }
    }


    private fun registerInFlightBitmap(timestamp: Long, bitmap: Bitmap) {
        synchronized(inFlightLock) {
            inFlightBitmaps[timestamp] = bitmap
        }
    }

    private fun releaseInFlightBitmap(timestamp: Long) {
        val bitmap = synchronized(inFlightLock) {
            inFlightBitmaps.remove(timestamp)
        }
        recycleBitmapSafely(bitmap)
    }

    private fun recycleBitmapSafely(bitmap: Bitmap?) {
        if (bitmap == null) return
        try {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to recycle MediaPipe bitmap", t)
        }
    }

    private fun processResult(result: PoseLandmarkerResult, width: Int, height: Int, timestampMs: Long) {
        val landmarksList = result.landmarks()
        if (landmarksList.isNullOrEmpty()) {
            listener.onResults(PoseLandmarks(), width, height, timestampMs)
            return
        }

        val firstLandmarks = landmarksList[0]
        if (firstLandmarks.size < 33) {
            listener.onResults(PoseLandmarks(), width, height, timestampMs)
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

        listener.onResults(pose, width, height, timestampMs)
    }

    fun close() {
        val bitmapsToRecycle = synchronized(inFlightLock) {
            val values = inFlightBitmaps.values.toList()
            inFlightBitmaps.clear()
            values
        }
        bitmapsToRecycle.forEach { recycleBitmapSafely(it) }
        poseLandmarker?.close()
        poseLandmarker = null
    }
}
