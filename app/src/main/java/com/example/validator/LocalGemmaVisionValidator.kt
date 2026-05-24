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
import kotlinx.coroutines.CancellationException
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
    private const val GEMMA_IMAGE_MAX_LONG_SIDE = 640
    private const val GEMMA_IMAGE_JPEG_QUALITY = 72
    private const val LITERT_LM_VERSION = "0.12.0"
    private const val ENGINE_CACHE_SCHEMA_VERSION = 1
    private const val ENGINE_CACHE_ROOT_DIR = "gemma_engine_cache"

    private fun getEngineCacheDir(context: Context, modelFile: File): File {
        val rootDir = File(context.filesDir, ENGINE_CACHE_ROOT_DIR)
        val cacheSubDirName = "litertlm_${LITERT_LM_VERSION}_schema_${ENGINE_CACHE_SCHEMA_VERSION}_${modelFile.nameWithoutExtension}_${modelFile.length()}_${modelFile.lastModified()}"
        return File(rootDir, cacheSubDirName)
    }

    private fun deleteRecursivelySafe(target: File) {
        if (!target.exists()) return
        target.walkBottomUp().forEach { file ->
            if (!file.delete()) {
                Log.w(TAG, "Unable to delete file/dir during cache cleanup: ${file.absolutePath}")
            }
        }
    }

    private fun deleteRuntimeCacheRoot(context: Context) {
        deleteRecursivelySafe(File(context.filesDir, ENGINE_CACHE_ROOT_DIR))
    }

    private suspend fun runVisionWarmup(context: Context, localEngine: Engine): String {
        var warmupBitmap: Bitmap? = null
        val tempWarmupImage = File(context.cacheDir, "gemma_warmup_${System.nanoTime()}.jpg")
        try {
            warmupBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.GRAY)
            }
            FileOutputStream(tempWarmupImage).use { out ->
                warmupBitmap.compress(Bitmap.CompressFormat.JPEG, GEMMA_IMAGE_JPEG_QUALITY, out)
            }
            return localEngine.createConversation().use { conversation ->
                val output = conversation.sendMessage(
                    Contents.of(
                        Content.ImageFile(tempWarmupImage.absolutePath),
                        Content.Text("Warm-up vision request. Reply exactly: OK")
                    )
                )
                output.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString(separator = "\n") { it.text }
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            if (tempWarmupImage.exists()) {
                tempWarmupImage.delete()
            }
            warmupBitmap?.recycle()
        }
    }

    suspend fun prepare(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val localEngine = getOrInitializeEngine(context)
            val warmupOutput = runVisionWarmup(context, localEngine)
            Log.i(TAG, "LiteRT-LM warm-up completed: $warmupOutput")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (firstError: Throwable) {
            Log.e(TAG, "LiteRT-LM prepare failed on attempt 1, rebuilding runtime cache", firstError)
            close()
            deleteRuntimeCacheRoot(context)
            try {
                val retryEngine = getOrInitializeEngine(context)
                val warmupOutput = runVisionWarmup(context, retryEngine)
                Log.i(TAG, "LiteRT-LM warm-up completed after runtime cache rebuild: $warmupOutput")
                true
            } catch (e: CancellationException) {
                throw e
            } catch (secondError: Throwable) {
                Log.e(TAG, "LiteRT-LM prepare failed after runtime cache rebuild", secondError)
                false
            }
        }
    }

    suspend fun rebuildRuntimeCache(context: Context): Boolean = withContext(Dispatchers.IO) {
        close()
        deleteRuntimeCacheRoot(context)
        prepare(context)
    }

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
        val cacheDir = getEngineCacheDir(context, modelFile)

        fun createEngine(): Engine {
            cacheDir.mkdirs()
            return Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.GPU(),
                    visionBackend = Backend.GPU(),
                    cacheDir = cacheDir.absolutePath
                )
            )
        }

        var newEngine: Engine? = null
        try {
            newEngine = createEngine()
            newEngine.initialize()
            engine = newEngine
            Log.i(TAG, "Successfully initialized LiteRT-LM Engine on GPU backend")
            return newEngine
        } catch (firstError: Throwable) {
            Log.e(TAG, "Engine.initialize failed (attempt 1), rebuilding runtime cache", firstError)
            try {
                newEngine?.close()
            } catch (_: Throwable) {
            }
            engine = null
            deleteRecursivelySafe(cacheDir)
            cacheDir.mkdirs()

            val retryEngine = createEngine()
            try {
                retryEngine.initialize()
                engine = retryEngine
                Log.i(TAG, "Engine.initialize succeeded on retry after cache rebuild")
                return retryEngine
            } catch (secondError: Throwable) {
                try {
                    retryEngine.close()
                } catch (_: Throwable) {
                }
                engine = null
                throw secondError
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
        val tempImgFile = File(context.cacheDir, "gemma_vision_frame_${System.nanoTime()}.jpg")
        try {
            val localEngine = getOrInitializeEngine(context)
            
            // Re-create a light isolated conversation scope to ensure past images/history do not leak or pollute this check

            // Resize and write bitmap to temporary cached file for SDK input support
            FileOutputStream(tempImgFile).use { out ->
                val resized = getResizedBitmap(bitmap, GEMMA_IMAGE_MAX_LONG_SIDE)
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

            return@withContext PoseValidationResult(
                personPresent = personPresent,
                facingAway = facingAway,
                nude = nude,
                isPassed = personPresent && facingAway && nude,
                rawJson = cleanJson
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Error performing on-device local model validation: ${t.message}", t)
            return@withContext PoseValidationResult(
                personPresent = false,
                facingAway = false,
                nude = false,
                isPassed = false,
                rawJson = "{\"error\": \"Local Gemma validation failed: ${t.message}\"}",
                technicalError = t.message ?: "Unknown LiteRT-LM runtime error"
            )
        } finally {
            try {
                if (tempImgFile.exists()) tempImgFile.delete()
            } catch (_: Throwable) {
            }
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
