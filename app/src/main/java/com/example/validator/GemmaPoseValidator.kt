package com.example.validator

import android.content.Context
import android.graphics.Bitmap
import com.example.tracker.PoseLandmarks

data class PoseValidationResult(
    val personPresent: Boolean,
    val facingAway: Boolean,
    val naked: Boolean,
    val isPassed: Boolean,
    val rawJson: String? = null
)

object GemmaPoseValidator {
    
    // Explicit 2-argument vision-only validation
    suspend fun validatePose(
        context: Context,
        bitmap: Bitmap
    ): PoseValidationResult {
        return LocalGemmaVisionValidator.validatePose(context, bitmap)
    }

    // Explicit 3-argument signature for backward compatibility, landmarks are explicitly ignored,
    // as Gemma local verification must run solely on the visual bitmap input.
    suspend fun validatePose(
        context: Context,
        bitmap: Bitmap,
        landmarks: PoseLandmarks?
    ): PoseValidationResult {
        return LocalGemmaVisionValidator.validatePose(context, bitmap)
    }

    fun close() {
        LocalGemmaVisionValidator.close()
    }
}
