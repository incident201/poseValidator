package com.example.tracker

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector

private const val MIN_DETECTION_CONFIDENCE = 0.5f

data class FaceDetectionOnCrop(
    val isFaceVisible: Boolean,
    val boundingBox: CropFaceRect? = null,
    val keypoints: List<CropFacePoint> = emptyList(),
    val score: Float = 0f
)

data class CropFaceRect(
    val leftPx: Float,
    val topPx: Float,
    val rightPx: Float,
    val bottomPx: Float
)

data class CropFacePoint(
    val x: Float,
    val y: Float
)

class FaceDetectorService(context: Context) {
    private val tag = "FaceDetectorService"
    private var faceDetector: FaceDetector? = null

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("blaze_face_full_range_sparse.tflite")
                .build()
            val options = FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setMinDetectionConfidence(MIN_DETECTION_CONFIDENCE)
                .build()
            faceDetector = FaceDetector.createFromOptions(context, options)
        } catch (t: Throwable) {
            Log.e(tag, "Failed to initialize face detector", t)
            faceDetector = null
        }
    }

    @Synchronized
    fun detectOnCrop(cropBitmap: Bitmap): FaceDetectionOnCrop {
        val detector = faceDetector ?: return FaceDetectionOnCrop(false)
        return try {
            val argbBitmap = if (cropBitmap.config != Bitmap.Config.ARGB_8888) {
                cropBitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                cropBitmap
            }
            val mpImage = BitmapImageBuilder(argbBitmap).build()
            val detections = detector.detect(mpImage).detections()
            if (detections.isNullOrEmpty()) {
                return FaceDetectionOnCrop(false)
            }

            val bestDetection = detections.maxByOrNull { detection ->
                detection.categories().firstOrNull()?.score() ?: 0f
            } ?: return FaceDetectionOnCrop(false)

            val score = bestDetection.categories().firstOrNull()?.score() ?: 0f
            val box = bestDetection.boundingBox()
            val faceRect = CropFaceRect(
                leftPx = box.left,
                topPx = box.top,
                rightPx = box.right,
                bottomPx = box.bottom
            )
            val keypoints = bestDetection.keypoints().map { keypoint ->
                CropFacePoint(
                    x = keypoint.x().coerceIn(0f, 1f),
                    y = keypoint.y().coerceIn(0f, 1f)
                )
            }

            FaceDetectionOnCrop(
                isFaceVisible = true,
                boundingBox = faceRect,
                keypoints = keypoints,
                score = score
            )
        } catch (t: Throwable) {
            Log.e(tag, "Face detection on crop failed", t)
            FaceDetectionOnCrop(false)
        }
    }

    fun close() {
        faceDetector?.close()
        faceDetector = null
    }
}
