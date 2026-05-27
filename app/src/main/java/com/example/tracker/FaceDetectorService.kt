package com.example.tracker

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector

private const val FACE_DETECTOR_MODEL_ASSET = "blaze_face_short_range.tflite"
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
    val score: Float = 0f,
    val errorMessage: String = ""
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
    private var initializationError: String = ""

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setDelegate(Delegate.CPU)
                .setModelAssetPath(FACE_DETECTOR_MODEL_ASSET)
                .build()
            val options = FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setMinDetectionConfidence(MIN_DETECTION_CONFIDENCE)
                .build()
            faceDetector = FaceDetector.createFromOptions(context, options)
            Log.i(tag, "Face detector loaded successfully model=$FACE_DETECTOR_MODEL_ASSET")
        } catch (t: Throwable) {
            initializationError = buildErrorMessage(t)
            Log.e(tag, "Failed to initialize face detector model=$FACE_DETECTOR_MODEL_ASSET", t)
            faceDetector = null
        }
    }

    @Synchronized
    fun detectOnCrop(cropBitmap: Bitmap): FaceDetectionOnCrop {
        val detector = faceDetector ?: return FaceDetectionOnCrop(
            status = FaceDetectionStatus.Error,
            errorMessage = "init failed: $initializationError"
        )
        var copiedBitmap: Bitmap? = null

        return try {
            val inputBitmap: Bitmap =
                if (cropBitmap.config == Bitmap.Config.ARGB_8888) {
                    cropBitmap
                } else {
                    val copy = cropBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    copiedBitmap = copy
                    copy ?: return FaceDetectionOnCrop(FaceDetectionStatus.Error)
                }
            val mpImage = BitmapImageBuilder(inputBitmap).build()
            val result = detector.detect(mpImage)
            val detections = result.detections()
            if (detections.isNullOrEmpty()) {
                return FaceDetectionOnCrop(FaceDetectionStatus.FaceNotVisible)
            }

            val bestDetection = detections.maxByOrNull { detection ->
                detection.categories().firstOrNull()?.score() ?: 0f
            } ?: return FaceDetectionOnCrop(FaceDetectionStatus.FaceNotVisible)

            val score = bestDetection.categories().firstOrNull()?.score() ?: 0f
            val box = bestDetection.boundingBox()
            val faceRect = CropFaceRect(
                leftPx = box.left,
                topPx = box.top,
                rightPx = box.right,
                bottomPx = box.bottom
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
            val error = buildErrorMessage(t)
            Log.e(
                tag,
                "Face detection on crop failed model=$FACE_DETECTOR_MODEL_ASSET input=${cropBitmap.width}x${cropBitmap.height}",
                t
            )
            FaceDetectionOnCrop(
                status = FaceDetectionStatus.Error,
                errorMessage = error
            )
        } finally {
            copiedBitmap?.recycle()
        }
    }

    private fun buildErrorMessage(t: Throwable): String {
        val type = t::class.java.simpleName
        val message = t.message.orEmpty()
        val cause = t.cause?.let { cause ->
            val causeType = cause::class.java.simpleName
            val causeMessage = cause.message.orEmpty()
            if (causeMessage.isNotBlank()) " cause=$causeType: $causeMessage" else " cause=$causeType"
        }.orEmpty()
        return if (message.isNotBlank()) "$type: $message$cause" else "$type$cause"
    }


    @Synchronized
    fun close() {
        faceDetector?.close()
        faceDetector = null
    }
}
