package com.example.validator

import android.content.Context
import android.graphics.Bitmap
import com.example.tracker.PoseLandmarks

data class PoseValidationResult(
    val personPresent: Boolean,
    val facingAway: Boolean,
    val nude: Boolean,
    val isPassed: Boolean,
    val rawJson: String? = null,
    val technicalError: String? = null
)

object GemmaPoseValidator {
    fun hasCompletedInitialRuntimePreparation(context: Context): Boolean {
        return LocalGemmaVisionValidator.hasCompletedInitialRuntimePreparation(context)
    }

    suspend fun prepare(context: Context): Boolean {
        return LocalGemmaVisionValidator.prepare(context)
    }

    suspend fun rebuildRuntimeCache(context: Context): Boolean {
        return LocalGemmaVisionValidator.rebuildRuntimeCache(context)
    }

    fun wasPreviousPrepareInterrupted(context: Context): Boolean {
        return LocalGemmaVisionValidator.wasPreviousPrepareInterrupted(context)
    }

    fun requiresRuntimeResetAfterAppUpdate(context: Context): Boolean {
        return LocalGemmaVisionValidator.requiresRuntimeResetAfterAppUpdate(context)
    }

    suspend fun hardResetRuntimeStatePreservingModel(context: Context): Boolean {
        return LocalGemmaVisionValidator.hardResetRuntimeStatePreservingModel(context)
    }

    suspend fun clearRuntimeStateOnly(context: Context) {
        LocalGemmaVisionValidator.clearRuntimeStateOnly(context)
    }

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
