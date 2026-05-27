package com.example.tracker

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import kotlin.math.roundToInt

private const val MIN_DETECTION_CONFIDENCE = 0.5f

enum class FaceDetectionStatus {
    NotProcessed,
    FaceVisible,
    FaceNotVisible,
    Error
}

data class FaceDetectionOnCrop(
    val status: FaceDetectionStatus,
    val boundingBox: CropFaceRect? = null,
    val keypoints: List<CropFacePoint> = emptyList(),
    val score: Float = 0f
) {
    val isFaceVisible: Boolean
        get() = status == FaceDetectionStatus.FaceVisible
}

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
        val detector = faceDetector ?: return FaceDetectionOnCrop(FaceDetectionStatus.Error)
        var copiedBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null

        return try {
            val inputBitmap: Bitmap =
                if (cropBitmap.config == Bitmap.Config.ARGB_8888) {
                    cropBitmap
                } else {
                    val copy = cropBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    copiedBitmap = copy
                    copy ?: return FaceDetectionOnCrop(FaceDetectionStatus.Error)
                }
            val detectorInputBitmap =
                if (inputBitmap.width < 256 || inputBitmap.height < 256) {
                    val scale = 256f / minOf(inputBitmap.width, inputBitmap.height).toFloat()
                    val scaledWidth = (inputBitmap.width * scale).roundToInt().coerceAtLeast(1)
                    val scaledHeight = (inputBitmap.height * scale).roundToInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(inputBitmap, scaledWidth, scaledHeight, true).also {
                        scaledBitmap = it
                    }
                } else {
                    inputBitmap
                }
            val scaleX = detectorInputBitmap.width.toFloat() / inputBitmap.width.toFloat()
            val scaleY = detectorInputBitmap.height.toFloat() / inputBitmap.height.toFloat()

            val mpImage = BitmapImageBuilder(detectorInputBitmap).build()
            val detections = detector.detect(mpImage).detections()
            if (detections.isNullOrEmpty()) {
                return FaceDetectionOnCrop(FaceDetectionStatus.FaceNotVisible)
            }

            val bestDetection = detections.maxByOrNull { detection ->
                detection.categories().firstOrNull()?.score() ?: 0f
            } ?: return FaceDetectionOnCrop(FaceDetectionStatus.FaceNotVisible)

            val score = bestDetection.categories().firstOrNull()?.score() ?: 0f
            val box = bestDetection.boundingBox()
            val faceRect = CropFaceRect(
                leftPx = box.left / scaleX,
                topPx = box.top / scaleY,
                rightPx = box.right / scaleX,
                bottomPx = box.bottom / scaleY
            )
            val keypoints = bestDetection.keypoints()
                .orElse(emptyList())
                .map { keypoint ->
                    CropFacePoint(
                        x = keypoint.x().coerceIn(0f, 1f),
                        y = keypoint.y().coerceIn(0f, 1f)
                    )
                }

            FaceDetectionOnCrop(
                status = FaceDetectionStatus.FaceVisible,
                boundingBox = faceRect,
                keypoints = keypoints,
                score = score
            )
        } catch (t: Throwable) {
            Log.e(tag, "Face detection on crop failed", t)
            FaceDetectionOnCrop(FaceDetectionStatus.Error)
        } finally {
            scaledBitmap?.recycle()
            copiedBitmap?.recycle()
        }
    }


    @Synchronized
    fun close() {
        faceDetector?.close()
        faceDetector = null
    }
}
