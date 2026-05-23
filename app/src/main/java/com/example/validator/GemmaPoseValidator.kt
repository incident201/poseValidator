package com.example.validator

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.api.Content
import com.example.api.GenerateContentRequest
import com.example.api.GenerationConfig
import com.example.api.InlineData
import com.example.api.Part
import com.example.api.RetrofitClient
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

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
    private val TAG = "GemmaPoseValidator"
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

    suspend fun validatePose(bitmap: Bitmap): PoseValidationResult = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.w(TAG, "API key is empty or placeholder. Validation failing gracefully.")
            return@withContext PoseValidationResult(
                personPresent = false,
                facingAway = false,
                kneeling = false,
                isPassed = false,
                rawJson = "Error: GEMINI_API_KEY is not defined in Secrets panel"
            )
        }

        try {
            // Compress bitmap
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            val base64Data = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            val request = GenerateContentRequest(
                contents = listOf(
                    Content(
                        parts = listOf(
                            Part(text = PROMPT),
                            Part(
                                inlineData = InlineData(
                                    mimeType = "image/jpeg",
                                    data = base64Data
                                )
                            )
                        )
                    )
                ),
                generationConfig = GenerationConfig(
                    responseMimeType = "application/json",
                    temperature = 0.1f
                )
            )

            val response = RetrofitClient.service.generateContent(apiKey, request)
            val jsonText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            Log.d(TAG, "Gemma/Gemini output JSON: $jsonText")

            if (jsonText == null) {
                return@withContext PoseValidationResult(false, false, false, false, "No response from AI")
            }

            // Extract JSON block
            val cleanJson = extractJson(jsonText)

            val parsedOutput = jsonAdapter.fromJson(cleanJson)
                ?: return@withContext PoseValidationResult(false, false, false, false, cleanJson)

            val personPresent = parsedOutput.personPresent == true
            val facingAway = parsedOutput.facingAway == true
            val kneeling = parsedOutput.kneeling == true
            val isPassed = personPresent && facingAway && kneeling

            PoseValidationResult(
                personPresent = personPresent,
                facingAway = facingAway,
                kneeling = kneeling,
                isPassed = isPassed,
                rawJson = cleanJson
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error matching pose via Gemma", e)
            PoseValidationResult(
                personPresent = false,
                facingAway = false,
                kneeling = false,
                isPassed = false,
                rawJson = "Error: ${e.localizedMessage ?: "Unknown network error"}"
            )
        }
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
}
