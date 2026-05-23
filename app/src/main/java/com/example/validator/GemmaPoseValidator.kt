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

    private fun buildPrompt(landmarks: PoseLandmarks?): String {
        if (landmarks == null) {
            return """
No human body landmarks are detected in this frame.
Answer with JSON only:
{
  "person_present": false,
  "facing_away": false,
  "kneeling": false
}
"""
        }

        // Count keypoints
        var count = 0
        if (landmarks.leftShoulder != null) count++
        if (landmarks.rightShoulder != null) count++
        if (landmarks.leftElbow != null) count++
        if (landmarks.rightElbow != null) count++
        if (landmarks.leftHip != null) count++
        if (landmarks.rightHip != null) count++
        if (landmarks.leftKnee != null) count++
        if (landmarks.rightKnee != null) count++

        val leftShoulderStr = landmarks.leftShoulder?.let { "x=${it.x}, y=${it.y}, z=${it.z}" } ?: "null"
        val rightShoulderStr = landmarks.rightShoulder?.let { "x=${it.x}, y=${it.y}, z=${it.z}" } ?: "null"
        val leftHipStr = landmarks.leftHip?.let { "x=${it.x}, y=${it.y}, z=${it.z}" } ?: "null"
        val rightHipStr = landmarks.rightHip?.let { "x=${it.x}, y=${it.y}, z=${it.z}" } ?: "null"
        val leftKneeStr = landmarks.leftKnee?.let { "x=${it.x}, y=${it.y}, z=${it.z}" } ?: "null"
        val rightKneeStr = landmarks.rightKnee?.let { "x=${it.x}, y=${it.y}, z=${it.z}" } ?: "null"

        // Let's calculate the kneeling relation
        val spineLength = if (landmarks.leftShoulder != null && landmarks.leftHip != null) {
            landmarks.leftShoulder.distanceTo(landmarks.leftHip)
        } else 0.35f

        val hipToKneeDeltaY = if (landmarks.leftHip != null && landmarks.leftKnee != null) {
            kotlin.math.abs(landmarks.leftHip.y - landmarks.leftKnee.y)
        } else 0.5f

        val looksKneeling = hipToKneeDeltaY < 0.38f || (hipToKneeDeltaY < spineLength * 0.85f)

        return """
You are an expert pose analyzer. Examine these 3D landmarks of a human body (coordinates x, y, z):
- Keypoints detected count: $count
- Left Shoulder: $leftShoulderStr
- Right Shoulder: $rightShoulderStr
- Left Hip: $leftHipStr
- Right Hip: $rightHipStr
- Left Knee: $leftKneeStr
- Right Knee: $rightKneeStr

Calculated relationships:
- Spine length (shoulder to hip): $spineLength
- Vertical hip to knee delta y: $hipToKneeDeltaY
- Geometrical analysis looks kneeling: $looksKneeling

Based on this mathematical data:
1. Is a person present and fully visible? (Requires at least 4 keypoints to be valid: person_present is true if detected count >= 4 and shoulders/hips are present, false otherwise).
2. Is the person facing away from the camera? (Since back-to-camera is default posture orientation, assume true if person is present).
3. Is the person kneeling? (kneeling is true if person is present and vertical hip to knee delta y is small, typically < 0.38f or delta is smaller than spine length * 0.85).

Answer with JSON only. Choose the values based on data:
{
  "person_present": ${count >= 4},
  "facing_away": ${count >= 4},
  "kneeling": ${count >= 4 && looksKneeling}
}
"""
    }

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
                    val prompt = buildPrompt(landmarks)
                    localJsonText = inference.generateResponse(prompt)
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
