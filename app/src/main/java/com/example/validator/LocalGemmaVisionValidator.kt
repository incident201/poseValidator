package com.example.validator

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class LocalGemmaJsonOutput(
    @com.squareup.moshi.Json(name = "person_present") val personPresent: Boolean?,
    @com.squareup.moshi.Json(name = "facing_away") val facingAway: Boolean?,
    @com.squareup.moshi.Json(name = "kneeling") val kneeling: Boolean?
)

object LocalGemmaVisionValidator {
    private const val TAG = "LocalGemmaVision"
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val jsonAdapter = moshi.adapter(LocalGemmaJsonOutput::class.java).lenient()

    private var engine: Engine? = null

    @Synchronized
    private fun getOrInitializeEngine(context: Context): Engine {
        val current = engine
        if (current != null && current.isInitialized()) {
            return current
        }

        val modelFile = GemmaModelManager.getModelFile(context)
        if (!modelFile.exists()) {
            throw IllegalStateException("Local Gemma model file is not downloaded! Checked: ${modelFile.absolutePath}")
        }

        Log.i(TAG, "Initializing local LiteRT-LM Engine using ${modelFile.name}")
        
        // Setup GPU Configuration with CPU fallback
        return try {
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                visionBackend = Backend.GPU(),
                cacheDir = context.cacheDir.absolutePath
            )
            val newEngine = Engine(config)
            newEngine.initialize()
            engine = newEngine
            Log.i(TAG, "Successfully initialized LiteRT-LM Engine on GPU backend")
            newEngine
        } catch (e: Exception) {
            Log.w(TAG, "Failed GPU initialization: ${e.message}. Cascading down to CPU fallback...", e)
            try {
                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    visionBackend = Backend.CPU(),
                    cacheDir = context.cacheDir.absolutePath
                )
                val newEngine = Engine(config)
                newEngine.initialize()
                engine = newEngine
                Log.i(TAG, "Successfully initialized LiteRT-LM Engine on CPU fallback")
                newEngine
            } catch (ex: Exception) {
                Log.e(TAG, "Failed critically to load LiteRT-LM model even on CPU fallback: ${ex.message}", ex)
                throw ex
            }
        }
    }

    private fun getResizedBitmap(image: Bitmap, maxSize: Int): Bitmap {
        var width = image.width
        var height = image.height
        val ratio = width.toFloat() / height.toFloat()
        if (ratio > 1) {
            width = maxSize
            height = (width / ratio).toInt()
        } else {
            height = maxSize
            width = (height * ratio).toInt()
        }
        return Bitmap.createScaledBitmap(image, width, height, true)
    }

    suspend fun validatePose(context: Context, bitmap: Bitmap): PoseValidationResult = withContext(Dispatchers.IO) {
        try {
            val localEngine = getOrInitializeEngine(context)
            
            // Re-create a light isolated conversation scope to ensure past images/history do not leak or pollute this check
            val conversation = localEngine.createConversation()

            // Resize and write bitmap to temporary cached file for SDK input support
            val tempImgFile = File(context.cacheDir, "gemma_vision_frame.jpg")
            if (tempImgFile.exists()) {
                tempImgFile.delete()
            }
            FileOutputStream(tempImgFile).use { out ->
                val resized = getResizedBitmap(bitmap, 768)
                resized.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }

            val prompt = """
Look at the image and answer only with JSON.

Questions:
1. Is a person present?
2. Is the person facing away from the camera?
3. Is the person kneeling?

JSON format:
{"person_present":true,"facing_away":true,"kneeling":true}

Use only true or false.
""".trimIndent()

            Log.i(TAG, "Sending message packet locally to LiteRT-LM engine (image path: ${tempImgFile.absolutePath})")
            
            val contentList = listOf(
                Content.ImageFile(tempImgFile.absolutePath),
                Content.Text(prompt)
            )
            val contentsPacket = Contents.of(contentList)
            
            val messageOutput = conversation.sendMessage(contentsPacket)
            val rawOutput = messageOutput.contents.contents.filterIsInstance<Content.Text>().firstOrNull()?.text ?: ""
            
            Log.i(TAG, "Raw feedback string from LiteRT-LM: $rawOutput")
            val cleanJson = extractJson(rawOutput)
            Log.i(TAG, "Cleaned model response: $cleanJson")

            // Parse response
            val parsedResult = try {
                jsonAdapter.fromJson(cleanJson)
            } catch (e: Exception) {
                Log.w(TAG, "Standard parser error: ${e.message}. Using fallback regex extraction.", e)
                null
            }

            // Fallback Regex
            val personPresent = parsedResult?.personPresent ?: (
                cleanJson.contains("\"person_present\"\\s*:\\s*true".toRegex(RegexOption.IGNORE_CASE)) || 
                cleanJson.contains("\"personPresent\"\\s*:\\s*true".toRegex(RegexOption.IGNORE_CASE))
            )
            val facingAway = parsedResult?.facingAway ?: (
                cleanJson.contains("\"facing_away\"\\s*:\\s*true".toRegex(RegexOption.IGNORE_CASE)) || 
                cleanJson.contains("\"facingAway\"\\s*:\\s*true".toRegex(RegexOption.IGNORE_CASE))
            )
            val kneeling = parsedResult?.kneeling ?: (
                cleanJson.contains("\"kneeling\"\\s*:\\s*true".toRegex(RegexOption.IGNORE_CASE))
            )

            // Cleanup the temporary image file
            try {
                if (tempImgFile.exists()) tempImgFile.delete()
            } catch (e: Exception) {
                // Ignore
            }

            return@withContext PoseValidationResult(
                personPresent = personPresent,
                facingAway = facingAway,
                kneeling = kneeling,
                isPassed = personPresent && facingAway && kneeling,
                rawJson = cleanJson
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error performing on-device local model validation: ${e.message}", e)
            return@withContext PoseValidationResult(
                personPresent = false,
                facingAway = false,
                kneeling = false,
                isPassed = false,
                rawJson = "{\"error\": \"Local Gemma validation failed: ${e.message}\"}"
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

    @Synchronized
    fun close() {
        try {
            engine?.close()
            engine = null
            Log.i(TAG, "LiteRT-LM Engine closed and resource handles cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing LiteRT-LM Engine: ${e.message}", e)
        }
    }
}
