package com.example.tracker

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.io.File
import java.io.FileOutputStream
import java.net.URL

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
        val localFile = File(context.filesDir, "pose_landmarker_full.task")
        
        if (localFile.exists() && localFile.length() > 5 * 1024 * 1024) {
            initializeRealLandmarker(localFile)
        } else {
            isSimulated = true
            // Asynchronously download model in background
            Thread {
                try {
                    Log.i(TAG, "Downloading pose_landmarker_full.task to ${localFile.absolutePath}...")
                    val url = URL("https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/latest/pose_landmarker_full.task")
                    val connection = url.openConnection()
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000
                    
                    val inputStream = connection.getInputStream()
                    val tempFile = File(context.filesDir, "pose_landmarker_full.task.tmp")
                    val outputStream = FileOutputStream(tempFile)
                    
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.close()
                    inputStream.close()
                    
                    if (tempFile.renameTo(localFile)) {
                        Log.i(TAG, "pose_landmarker_full.task downloaded successfully!")
                        initializeRealLandmarker(localFile)
                    } else {
                        Log.e(TAG, "Failed to rename temp pose landmarker model file")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download pose landmarker model: ${e.message}", e)
                }
            }.start()
        }
    }

    private fun initializeRealLandmarker(modelFile: File) {
        try {
            Log.i(TAG, "Initializing MediaPipe Pose Landmarker from local path: ${modelFile.absolutePath}")
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelFile.absolutePath)
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
            isSimulated = false
            Log.i(TAG, "MediaPipe Pose Landmarker loaded successfully from local file!")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPipe Pose Landmarker: ${e.message}", e)
            isSimulated = true
        }
    }

    fun detectLiveStreamFrame(bitmap: Bitmap, timestamp: Long) {
        val landmarker = poseLandmarker
        if (landmarker != null) {
            try {
                val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
                landmarker.detectAsync(mpImage, timestamp)
            } catch (e: Exception) {
                Log.e(TAG, "Error in detectLiveStreamFrame", e)
            }
        } else {
            // Model not loaded yet (or downloading). Report empty landmarks to prevent faking a person.
            listener.onResults(PoseLandmarks(), bitmap.width, bitmap.height)
        }
    }

    private fun processResult(result: PoseLandmarkerResult, width: Int, height: Int) {
        val landmarksList = result.landmarks()
        if (landmarksList.isNullOrEmpty()) {
            listener.onResults(PoseLandmarks(), width, height)
            return
        }

        val firstLandmarks = landmarksList[0]
        if (firstLandmarks.size < 33) {
            listener.onResults(PoseLandmarks(), width, height)
            return
        }

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

    fun close() {
        poseLandmarker?.close()
    }
}
