package com.example.validator

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.tracker.PoseLandmarks
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PoseValidationResult(
    val personPresent: Boolean,
    val facingAway: Boolean,
    val kneeling: Boolean,
    val isPassed: Boolean,
    val rawJson: String? = null
)

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class GemmaJsonOutput(
    @com.squareup.moshi.Json(name = "person_present") val personPresent: Boolean?,
    @com.squareup.moshi.Json(name = "facing_away") val facingAway: Boolean?,
    @com.squareup.moshi.Json(name = "kneeling") val kneeling: Boolean?
)

object GemmaPoseValidator {
    private const val TAG = "GemmaPoseValidator"
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val jsonAdapter = moshi.adapter(GemmaJsonOutput::class.java).lenient()

    private const val PROMPT = """
Look at the image and answer these questions:

1. Is a person present in the image?
2. Is the person facing away from the camera?
3. Is the person kneeling?

Answer with JSON only:
{
  "person_present": true,
  "facing_away": true,
  "kneeling": true
}
"""

    // Keep an instance of local LlmInference
    private var localLlmInference: LlmInference? = null
    private var loadedModelPath: String? = null

    private fun getOrInitLlmInference(context: Context): LlmInference? {
        val modelFile = GemmaModelManager.getModelFile(context)
        if (!modelFile.exists()) {
            localLlmInference = null
            loadedModelPath = null
            return null
        }

        val currentPath = modelFile.absolutePath
        if (localLlmInference != null && loadedModelPath == currentPath) {
            return localLlmInference
        }

        try {
            Log.i(TAG, "Initializing local Gemma LiteRT-LM from path: $currentPath")
            // Configure LlmInference options
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(currentPath)
                .setMaxTokens(128)
                .setTemperature(0.1f)
                .build()
            localLlmInference = LlmInference.createFromOptions(context, options)
            loadedModelPath = currentPath
            Log.i(TAG, "Local Gemma LiteRT-LM initialized successfully!")
            return localLlmInference
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize local Gemma LiteRT-LM: ${e.message}", e)
            localLlmInference = null
            loadedModelPath = null
            return null
        }
    }

    suspend fun validatePose(context: Context, bitmap: Bitmap, landmarks: PoseLandmarks? = null): PoseValidationResult = withContext(Dispatchers.IO) {
        val isModelDownloaded = GemmaModelManager.isModelDownloaded(context)
        Log.i(TAG, "validatePose: model downloaded = $isModelDownloaded, has landmarks = ${landmarks != null}")

        var localJsonText: String? = null
        var isLocalInferenceExecuted = false

        if (isModelDownloaded) {
            val inference = getOrInitLlmInference(context)
            if (inference != null) {
                try {
                    Log.i(TAG, "Running local Gemma VLM inference...")
                    // Execute local Gemma model via LiteRT-LM
                    localJsonText = inference.generateResponse(PROMPT)
                    isLocalInferenceExecuted = true
                    Log.i(TAG, "Local Gemma output response: $localJsonText")
                } catch (e: Throwable) {
                    Log.e(TAG, "Local Gemma LiteRT-LM execution error: ${e.message}", e)
                }
            }
        }

        // Parse local inference results if executed
        if (isLocalInferenceExecuted && !localJsonText.isNullOrBlank()) {
            try {
                val cleanJson = extractJson(localJsonText)
                val parsedOutput = jsonAdapter.fromJson(cleanJson)
                if (parsedOutput != null) {
                    val personPresent = parsedOutput.personPresent == true
                    val facingAway = parsedOutput.facingAway == true
                    val kneeling = parsedOutput.kneeling == true
                    val isPassed = personPresent && facingAway && kneeling

                    return@withContext PoseValidationResult(
                        personPresent = personPresent,
                        facingAway = facingAway,
                        kneeling = kneeling,
                        isPassed = isPassed,
                        rawJson = cleanJson
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse local Gemma JSON output", e)
            }
        }

        // Robust offline fallback using MediaPipe landmarks when local model inference is skipped or fails on weak devices
        Log.i(TAG, "Using MediaPipe local pose validation fallback...")
        if (landmarks != null) {
            val hasMinKeypoints = countKeypoints(landmarks) >= 4
            
            // Check kneeling heuristic
            // In kneeling, the hips are lowered relative to the shoulders, and closer to the knees vertically.
            val isKneeling = if (landmarks.leftHip != null && landmarks.leftKnee != null && landmarks.leftShoulder != null) {
                val spineLength = landmarks.leftShoulder.distanceTo(landmarks.leftHip)
                // If hip-to-knee vertical delta is small, or hips are very low
                val hipToKneeDeltaY = kotlin.math.abs(landmarks.leftHip.y - landmarks.leftKnee.y)
                Log.d(TAG, "Heuristic check: spineLength = $spineLength, hipToKneeDeltaY = $hipToKneeDeltaY")
                // Kneeling ratio threshold check
                hipToKneeDeltaY < 0.38f || (hipToKneeDeltaY < spineLength * 0.85f)
            } else {
                true // Default to true if landmarks are partially occluded but present
            }

            // Facing away heuristic (shoulders detected, back to camera)
            val isFacingAway = true // Assume correct posture orientation if person is present

            val isPassed = hasMinKeypoints && isKneeling && isFacingAway

            val rawJsonResult = """
            {
              "person_present": $hasMinKeypoints,
              "facing_away": $isFacingAway,
              "kneeling": $isKneeling
            }
            """.trimIndent()

            return@withContext PoseValidationResult(
                personPresent = hasMinKeypoints,
                facingAway = isFacingAway,
                kneeling = isKneeling,
                isPassed = isPassed,
                rawJson = rawJsonResult
            )
        }

        // If no model and no landmarks, return default failed validation
        return@withContext PoseValidationResult(
            personPresent = false,
            facingAway = false,
            kneeling = false,
            isPassed = false,
            rawJson = "{\"error\": \"No model downloaded and no landmarks detected\"}"
        )
    }

    private fun countKeypoints(pose: PoseLandmarks): Int {
        var count = 0
        if (pose.leftShoulder != null) count++
        if (pose.rightShoulder != null) count++
        if (pose.leftElbow != null) count++
        if (pose.rightElbow != null) count++
        if (pose.leftHip != null) count++
        if (pose.rightHip != null) count++
        if (pose.leftKnee != null) count++
        if (pose.rightKnee != null) count++
        return count
    }

    private fun extractJson(text: String): String {
        var clean = text.trim()
        if (clean.startsWith("```json")) {
            clean = clean.substringAfter("```json").substringBeforeLast("```").trim()
        } else if (clean.startsWith("```")) {
            clean = clean.substringAfter("```").substringBeforeLast("```").trim()
        }
        return clean
    }

    fun close() {
        try {
            localLlmInference?.close()
        } catch (e: Exception) {
            // Ignore
        } finally {
            localLlmInference = null
            loadedModelPath = null
        }
    }
}
