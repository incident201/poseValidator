package com.example.tracker

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import kotlin.math.abs

private const val FACE_DETECTOR_MODEL_ASSET = "blaze_face_short_range.tflite"

enum class FaceDetectionStatus { NotProcessed, FaceVisible, FaceNotVisible, Error }

data class FaceDetectionOnCrop(
    val status: FaceDetectionStatus,
    val boundingBox: CropFaceRect? = null,
    val keypoints: List<CropFacePoint> = emptyList(),
    val score: Float = 0f,
    val errorMessage: String = ""
)

data class CropFaceRect(val leftPx: Float, val topPx: Float, val rightPx: Float, val bottomPx: Float)
data class CropFacePoint(val x: Float, val y: Float)

class FaceDetectorService(
    private val context: Context,
    initialMinDetectionConfidence: Float = 0.8f
) {
    private val tag = "FaceDetectorService"
    private var faceDetector: FaceDetector? = null
    private var initializationError: String = ""
    private var minDetectionConfidence = initialMinDetectionConfidence.coerceIn(0.5f, 0.95f)

    init { recreateDetector() }

    @Synchronized
    fun setMinDetectionConfidence(value: Float) {
        val normalized = value.coerceIn(0.5f, 0.95f)
        if (abs(normalized - minDetectionConfidence) < 0.001f) return
        minDetectionConfidence = normalized
        recreateDetector()
    }

    @Synchronized
    fun detectOnCrop(cropBitmap: Bitmap): FaceDetectionOnCrop {
        val detector = faceDetector ?: return FaceDetectionOnCrop(FaceDetectionStatus.Error, errorMessage = "init failed: $initializationError")
        var copiedBitmap: Bitmap? = null
        return try {
            val inputBitmap = if (cropBitmap.config == Bitmap.Config.ARGB_8888) cropBitmap else {
                val copy = cropBitmap.copy(Bitmap.Config.ARGB_8888, false)
                copiedBitmap = copy
                copy ?: return FaceDetectionOnCrop(FaceDetectionStatus.Error)
            }
            val result = detector.detect(BitmapImageBuilder(inputBitmap).build())
            val detections = result.detections()
            if (detections.isNullOrEmpty()) return FaceDetectionOnCrop(FaceDetectionStatus.FaceNotVisible)
            val best = detections.maxByOrNull { it.categories().firstOrNull()?.score() ?: 0f }
                ?: return FaceDetectionOnCrop(FaceDetectionStatus.FaceNotVisible)
            val score = best.categories().firstOrNull()?.score() ?: 0f
            val box = best.boundingBox()
            FaceDetectionOnCrop(
                status = FaceDetectionStatus.FaceVisible,
                boundingBox = CropFaceRect(box.left, box.top, box.right, box.bottom),
                keypoints = best.keypoints().orElse(emptyList()).map { CropFacePoint(it.x().coerceIn(0f, 1f), it.y().coerceIn(0f, 1f)) },
                score = score
            )
        } catch (t: Throwable) {
            val error = buildErrorMessage(t)
            Log.e(tag, "Face detection on crop failed model=$FACE_DETECTOR_MODEL_ASSET input=${cropBitmap.width}x${cropBitmap.height}", t)
            FaceDetectionOnCrop(FaceDetectionStatus.Error, errorMessage = error)
        } finally {
            copiedBitmap?.recycle()
        }
    }

    private fun recreateDetector() {
        faceDetector?.close()
        faceDetector = null
        initializationError = ""
        try {
            val baseOptions = BaseOptions.builder().setDelegate(Delegate.CPU).setModelAssetPath(FACE_DETECTOR_MODEL_ASSET).build()
            val options = FaceDetector.FaceDetectorOptions.builder().setBaseOptions(baseOptions).setRunningMode(RunningMode.IMAGE).setMinDetectionConfidence(minDetectionConfidence).build()
            faceDetector = FaceDetector.createFromOptions(context, options)
            Log.i(tag, "Face detector loaded successfully confidence=$minDetectionConfidence")
        } catch (t: Throwable) {
            initializationError = buildErrorMessage(t)
            Log.e(tag, "Failed to initialize face detector", t)
        }
    }

    private fun buildErrorMessage(t: Throwable): String {
        val type = t::class.java.simpleName
        val message = t.message.orEmpty()
        val cause = t.cause?.let { c -> if (c.message.isNullOrBlank()) " cause=${c::class.java.simpleName}" else " cause=${c::class.java.simpleName}: ${c.message}" }.orEmpty()
        return if (message.isNotBlank()) "$type: $message$cause" else "$type$cause"
    }

    @Synchronized
    fun close() {
        faceDetector?.close()
        faceDetector = null
    }
}
