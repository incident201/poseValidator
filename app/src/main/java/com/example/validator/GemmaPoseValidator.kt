package com.example.validator

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.api.GenerateContentRequest
import com.example.api.Content
import com.example.api.Part
import com.example.api.InlineData
import com.example.api.GenerationConfig
import com.example.api.RetrofitClient
import com.example.tracker.PoseLandmarks
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import com.example.BuildConfig

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

    private fun buildPrompt(): String {
        return """
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
""".trimIndent()
    }

    private fun failedResult(rawJson: String? = null): PoseValidationResult {
        return PoseValidationResult(
            personPresent = false,
            facingAway = false,
            kneeling = false,
            isPassed = false,
            rawJson = rawJson
        )
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        val resized = getResizedBitmap(bitmap, 768)
        resized.compress(Bitmap.CompressFormat.JPEG, 85, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private fun getResizedBitmap(image: Bitmap, maxSize: Int): Bitmap {
        var width = image.width
        var height = image.height

        val bitmapRatio = width.toFloat() / height.toFloat()
        if (bitmapRatio > 1) {
            width = maxSize
            height = (width / bitmapRatio).toInt()
        } else {
            height = maxSize
            width = (height * bitmapRatio).toInt()
        }
        return Bitmap.createScaledBitmap(image, width, height, true)
    }

    suspend fun validatePose(
        context: Context,
        bitmap: Bitmap,
        landmarks: PoseLandmarks?
    ): PoseValidationResult = withContext(Dispatchers.IO) {

        if (landmarks == null) {
            return@withContext failedResult()
        }

        val apiKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }

        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e(TAG, "Gemini API key is missing or not configured!")
            return@withContext failedResult("{\"error\": \"Gemini API key is not configured\"}")
        }

        val prompt = buildPrompt()
        val base64Image = try {
            bitmapToBase64(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode bitmap to Base64: ${e.message}", e)
            return@withContext failedResult()
        }

        val request = GenerateContentRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(text = prompt),
                        Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Image))
                    )
                )
            ),
            generationConfig = GenerationConfig(
                responseMimeType = "application/json",
                temperature = 0.1f
            )
        )

        Log.i(TAG, "Running multimodal validation via Gemini API...")
        val responseText = try {
            val response = RetrofitClient.service.generateContent(apiKey, request)
            response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
        } catch (e: Exception) {
            Log.e(TAG, "Vertex/Gemini multimodal web API call failed: ${e.message}", e)
            null
        } ?: return@withContext failedResult()

        val cleanJson = extractJson(responseText)
        Log.i(TAG, "Multimodal API raw response: $responseText | Cleaned JSON: $cleanJson")

        val parsed = try {
            jsonAdapter.fromJson(cleanJson)
        } catch (e: Exception) {
            null
        } ?: return@withContext failedResult(cleanJson)

        val personPresent = parsed.personPresent == true
        val facingAway = parsed.facingAway == true
        val kneeling = parsed.kneeling == true

        return@withContext PoseValidationResult(
            personPresent = personPresent,
            facingAway = facingAway,
            kneeling = kneeling,
            isPassed = personPresent && facingAway && kneeling,
            rawJson = cleanJson
        )
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
        // No-op for network API, kept for interface compatibility
    }
}
