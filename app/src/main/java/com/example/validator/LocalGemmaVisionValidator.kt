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

data class LocalGemmaJsonOutput(
    @com.squareup.moshi.Json(name = "person_present") val personPresent: Boolean?,
    @com.squareup.moshi.Json(name = "facing_away") val facingAway: Boolean?,
    @com.squareup.moshi.Json(name = "nude") val nude: Boolean?
)

object LocalGemmaVisionValidator {
    private const val TAG = "LocalGemmaVision"
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val jsonAdapter = moshi.adapter(LocalGemmaJsonOutput::class.java).lenient()

    private var engine: Engine? = null
    private const val GEMMA_IMAGE_MAX_LONG_SIDE = 896
    private const val GEMMA_IMAGE_JPEG_QUALITY = 85

    @Synchronized
    private fun getOrInitializeEngine(context: Context): Engine {
        val current = engine
        if (current != null && current.isInitialized()) {
            return current
        }

        MlRuntimeStateResetter.resetIfApkUpdated(context.applicationContext)

        val modelFile = GemmaModelManager.getModelFile(context)
        if (!modelFile.exists()) {
            throw IllegalStateException("Local Gemma model file is not downloaded! Checked: ${modelFile.absolutePath}")
        }

        Log.i(TAG, "Initializing local LiteRT-LM Engine using ${modelFile.name}")
        
        val liteRtCacheDir = File(context.codeCacheDir, "litertlm_cache").apply { mkdirs() }

        val config = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.GPU(),
            visionBackend = Backend.GPU(),
            cacheDir = liteRtCacheDir.absolutePath
        )
        val newEngine = Engine(config)
        newEngine.initialize()
        engine = newEngine
        Log.i(TAG, "Successfully initialized LiteRT-LM Engine on GPU backend")
        return newEngine
    }

    private fun getResizedBitmap(image: Bitmap, maxSize: Int): Bitmap {
        val width = image.width
        val height = image.height
        val longSide = maxOf(width, height)
        if (longSide <= maxSize) return image
        val scale = maxSize.toFloat() / longSide.toFloat()
        val newWidth = (width * scale).toInt().coerceAtLeast(1)
        val newHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(image, newWidth, newHeight, true)
    }

    suspend fun warmUp(context: Context) = withContext(Dispatchers.IO) {
        getOrInitializeEngine(context.applicationContext)
    }

    suspend fun validatePose(context: Context, bitmap: Bitmap): PoseValidationResult = withContext(Dispatchers.IO) {
        try {
            val localEngine = getOrInitializeEngine(context)
            
            // Re-create a light isolated conversation scope to ensure past images/history do not leak or pollute this check

            // Resize and write bitmap to temporary cached file for SDK input support
            val tempImgFile = File(context.cacheDir, "gemma_vision_frame.jpg")
            if (tempImgFile.exists()) {
                tempImgFile.delete()
            }
            FileOutputStream(tempImgFile).use { out ->
                val resized = getResizedBitmap(bitmap, GEMMA_IMAGE_MAX_LONG_SIDE)
                Log.d(TAG, "Gemma image size before JPEG=${resized.width}x${resized.height}")
                resized.compress(Bitmap.CompressFormat.JPEG, GEMMA_IMAGE_JPEG_QUALITY, out)
            }

            val prompt = """
Look at the image and answer only with JSON.

Questions:
1. Is a person present?
2. Is the person facing away from the camera?
3. Is the person nude?

JSON format:
{"person_present":true,"facing_away":true,"nude":true}

Use only true or false.
""".trimIndent()

            Log.i(TAG, "Sending message packet locally to LiteRT-LM engine (image path: ${tempImgFile.absolutePath})")
            
            val contentsPacket = Contents.of(
                Content.ImageFile(tempImgFile.absolutePath),
                Content.Text(prompt)
            )

            val rawOutput = localEngine.createConversation().use { conversation ->
                val messageOutput = conversation.sendMessage(contentsPacket)

                messageOutput.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString(separator = "\n") { it.text }
            }
            
            Log.i(TAG, "Raw feedback string from LiteRT-LM: $rawOutput")
            val cleanJson = extractJson(rawOutput)
            Log.i(TAG, "Cleaned model response: $cleanJson")

            // Parse response
            val parsedResult = try {
                jsonAdapter.fromJson(cleanJson)
            } catch (t: Throwable) {
                Log.w(TAG, "Standard parser error: ${t.message}. Using fallback regex extraction.", t)
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
            val nude = parsedResult?.nude ?: (
                cleanJson.contains("\"nude\"\\s*:\\s*true".toRegex(RegexOption.IGNORE_CASE))
            )

            // Cleanup the temporary image file
            try {
                if (tempImgFile.exists()) tempImgFile.delete()
            } catch (t: Throwable) {
                // Ignore
            }

            return@withContext PoseValidationResult(
                personPresent = personPresent,
                facingAway = facingAway,
                nude = nude,
                isPassed = personPresent && facingAway && nude,
                rawJson = cleanJson
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Error performing on-device local model validation: ${t.message}", t)
            return@withContext PoseValidationResult(
                personPresent = false,
                facingAway = false,
                nude = false,
                isPassed = false,
                rawJson = "{\"error\": \"Local Gemma validation failed: ${t.message}\"}"
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
        } catch (t: Throwable) {
            Log.e(TAG, "Error closing LiteRT-LM Engine: ${t.message}", t)
        }
    }
}
