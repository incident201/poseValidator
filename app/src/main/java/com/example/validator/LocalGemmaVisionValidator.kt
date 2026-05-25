package com.example.validator

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
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
    private const val RUNTIME_PREFS_NAME = "gemma_runtime_state"
    private const val INITIAL_PREPARATION_KEY = "initial_preparation_key"
    private const val PREPARE_IN_PROGRESS_KEY = "prepare_in_progress"
    private const val PREPARE_STARTED_AT_KEY = "prepare_started_at"
    private const val NATIVE_CRASH_RECOVERY_COUNT_KEY = "native_crash_recovery_count"
    private const val LAST_SUCCESSFUL_APP_VERSION_CODE_KEY = "last_successful_app_version_code"
    private const val DEEP_RECOVERY_ATTEMPT_COUNT_KEY = "deep_recovery_attempt_count"

    private fun getInitialPreparationKey(context: Context): String {
        GemmaModelManager.migrateLegacyModelLocationIfNeeded(context)
        val modelFile = GemmaModelManager.getModelFile(context)
        return "litertlm_${LITERT_LM_VERSION}_schema_${ENGINE_CACHE_SCHEMA_VERSION}_${modelFile.nameWithoutExtension}_${modelFile.length()}_${modelFile.lastModified()}"
    }

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
        clearRuntimeStateFilesAndMarkers(
            context = context,
            clearInitialPreparation = false,
            clearAppVersionMarker = false
        )
    }

    fun hasCompletedInitialRuntimePreparation(context: Context): Boolean {
        val modelFile = GemmaModelManager.getModelFile(context)
        if (!modelFile.exists()) return false
        val prefs = context.getSharedPreferences(RUNTIME_PREFS_NAME, Context.MODE_PRIVATE)
        val savedMarker = prefs.getString(INITIAL_PREPARATION_KEY, null) ?: return false
        return savedMarker == getInitialPreparationKey(context)
    }

    private fun markInitialRuntimePreparationCompleted(context: Context) {
        context.getSharedPreferences(RUNTIME_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(INITIAL_PREPARATION_KEY, getInitialPreparationKey(context))
            .apply()
    }



    fun wasPreviousPrepareInterrupted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(RUNTIME_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREPARE_IN_PROGRESS_KEY, false)
    }

    private fun markPrepareStarted(context: Context) {
        context.getSharedPreferences(RUNTIME_PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(PREPARE_IN_PROGRESS_KEY, true)
            .putLong(PREPARE_STARTED_AT_KEY, System.currentTimeMillis())
            .apply()
    }

    private fun markPrepareFinished(context: Context) {
        context.getSharedPreferences(RUNTIME_PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(PREPARE_IN_PROGRESS_KEY)
            .remove(PREPARE_STARTED_AT_KEY)
            .apply()
    }



    fun getDeepRecoveryAttemptCount(context: Context): Int {
        val prefs = context.getSharedPreferences(RUNTIME_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(DEEP_RECOVERY_ATTEMPT_COUNT_KEY, 0)
    }

    fun incrementDeepRecoveryAttemptCount(context: Context) {
        val prefs = context.getSharedPreferences(RUNTIME_PREFS_NAME, Context.MODE_PRIVATE)
        val next = prefs.getInt(DEEP_RECOVERY_ATTEMPT_COUNT_KEY, 0) + 1
        prefs.edit().putInt(DEEP_RECOVERY_ATTEMPT_COUNT_KEY, next).apply()
    }

    private fun clearDeepRecoveryAttemptCount(context: Context) {
        context.getSharedPreferences(RUNTIME_PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(DEEP_RECOVERY_ATTEMPT_COUNT_KEY)
            .apply()
    }

    private fun deepCleanAppDataPreservingModel(context: Context) {
        close()
        val modelFile = GemmaModelManager.getModelFile(context)
        val protectedModelPath = modelFile.canonicalPath
        val protectedModelDirPath = modelFile.parentFile!!.canonicalPath

        fun deleteSafely(file: File) {
            if (!file.exists()) return
            val cp = runCatching { file.canonicalPath }.getOrNull() ?: return
            if (cp == protectedModelPath || cp == protectedModelDirPath) return
            deleteRecursivelySafe(file)
        }

        val dataDir = context.applicationInfo.dataDir?.let { File(it) }
        deleteSafely(context.cacheDir)
        context.codeCacheDir?.let { deleteSafely(it) }
        deleteSafely(context.noBackupFilesDir)
        dataDir?.let {
            deleteSafely(File(it, "shared_prefs"))
            deleteSafely(File(it, "databases"))
            it.listFiles()?.forEach { child ->
                if (child.name.startsWith("app_")) deleteSafely(child)
            }
        }

        context.filesDir.listFiles()?.forEach { child ->
            val cp = runCatching { child.canonicalPath }.getOrNull()
            if (cp == protectedModelDirPath) return@forEach
            deleteSafely(child)
        }

        deleteSafely(File(context.filesDir, GemmaModelManager.MODEL_FILENAME))
        deleteSafely(File(modelFile.parentFile, "${GemmaModelManager.MODEL_FILENAME}.tmp"))

        context.filesDir.mkdirs()
        context.cacheDir.mkdirs()
        context.codeCacheDir?.mkdirs()
        modelFile.parentFile?.mkdirs()
    }

    private fun getCurrentAppVersionCode(context: Context): Long {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }

    fun requiresRuntimeResetAfterAppUpdate(context: Context): Boolean {
        val modelFile = GemmaModelManager.getModelFile(context)
        if (!modelFile.exists()) return false

        val prefs = context.getSharedPreferences(RUNTIME_PREFS_NAME, Context.MODE_PRIVATE)
        val currentVersion = getCurrentAppVersionCode(context)

        if (!prefs.contains(LAST_SUCCESSFUL_APP_VERSION_CODE_KEY)) {
            return hasCompletedInitialRuntimePreparation(context)
        }

        val lastSuccessfulVersion = prefs.getLong(LAST_SUCCESSFUL_APP_VERSION_CODE_KEY, -1L)
        return lastSuccessfulVersion != currentVersion
    }

    private fun markRuntimePreparedForCurrentAppVersion(context: Context) {
        context.getSharedPreferences(RUNTIME_PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(LAST_SUCCESSFUL_APP_VERSION_CODE_KEY, getCurrentAppVersionCode(context))
            .apply()
    }

    private fun clearRuntimeStateFilesAndMarkers(
        context: Context,
        clearInitialPreparation: Boolean,
        clearAppVersionMarker: Boolean
    ) {
        close()
        deleteRecursivelySafe(File(context.filesDir, ENGINE_CACHE_ROOT_DIR))

        context.cacheDir.listFiles()?.forEach { file ->
            val name = file.name.lowercase()
            if (name.startsWith("gemma_") || name.startsWith("litert") || name.startsWith("mediapipe") || name.startsWith("tensorflow") || name.startsWith("tflite")) {
                deleteRecursivelySafe(file)
            }
        }

        context.codeCacheDir?.let { deleteRecursivelySafe(it) }

        val prefs = context.getSharedPreferences(RUNTIME_PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .remove(PREPARE_IN_PROGRESS_KEY)
            .remove(PREPARE_STARTED_AT_KEY)
        if (clearInitialPreparation) editor.remove(INITIAL_PREPARATION_KEY)
        if (clearAppVersionMarker) editor.remove(LAST_SUCCESSFUL_APP_VERSION_CODE_KEY)
        editor.apply()

        File(context.filesDir, "${GemmaModelManager.MODEL_FILENAME}.tmp").takeIf { it.exists() }?.delete()
    }

    private fun clearInitialRuntimePreparationMarker(context: Context) {
        context.getSharedPreferences(RUNTIME_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(INITIAL_PREPARATION_KEY)
            .apply()
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
        markPrepareStarted(context)
        try {
            val localEngine = getOrInitializeEngine(context)
            val warmupOutput = runVisionWarmup(context, localEngine)
            Log.i(TAG, "LiteRT-LM warm-up completed: $warmupOutput")
            markInitialRuntimePreparationCompleted(context)
            markRuntimePreparedForCurrentAppVersion(context)
            clearDeepRecoveryAttemptCount(context)
            markPrepareFinished(context)
            true
        } catch (e: CancellationException) {
            markPrepareFinished(context)
            throw e
        } catch (firstError: Throwable) {
            Log.e(TAG, "LiteRT-LM prepare failed on attempt 1, rebuilding runtime cache", firstError)
            close()
            deleteRuntimeCacheRoot(context)
            try {
                val retryEngine = getOrInitializeEngine(context)
                val warmupOutput = runVisionWarmup(context, retryEngine)
                Log.i(TAG, "LiteRT-LM warm-up completed after runtime cache rebuild: $warmupOutput")
                markInitialRuntimePreparationCompleted(context)
                markRuntimePreparedForCurrentAppVersion(context)
                clearDeepRecoveryAttemptCount(context)
                markPrepareFinished(context)
                true
            } catch (e: CancellationException) {
                markPrepareFinished(context)
                throw e
            } catch (secondError: Throwable) {
                Log.e(TAG, "LiteRT-LM prepare failed after runtime cache rebuild", secondError)
                clearInitialRuntimePreparationMarker(context)
                markPrepareFinished(context)
                false
            }
        }
    }

    suspend fun hardResetRuntimeStatePreservingModel(context: Context): Boolean = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(RUNTIME_PREFS_NAME, Context.MODE_PRIVATE)
        val recoveries = prefs.getInt(NATIVE_CRASH_RECOVERY_COUNT_KEY, 0) + 1

        val modelFile = GemmaModelManager.getModelFile(context)
        if (!modelFile.exists()) return@withContext false

        clearRuntimeStateFilesAndMarkers(
            context = context,
            clearInitialPreparation = true,
            clearAppVersionMarker = false
        )

        prefs.edit().putInt(NATIVE_CRASH_RECOVERY_COUNT_KEY, recoveries).apply()

        prepare(context)
    }

    suspend fun deepResetAppDataPreservingModel(context: Context): Boolean = withContext(Dispatchers.IO) {
        GemmaModelManager.migrateLegacyModelLocationIfNeeded(context)
        val modelFile = GemmaModelManager.getModelFile(context)
        if (!modelFile.exists()) return@withContext false

        deepCleanAppDataPreservingModel(context)
        GemmaModelManager.refreshModelMetadata(context)
        prepare(context)
    }

    suspend fun clearRuntimeStateOnly(context: Context) = withContext(Dispatchers.IO) {
        clearRuntimeStateFilesAndMarkers(
            context = context,
            clearInitialPreparation = true,
            clearAppVersionMarker = true
        )
    }

    suspend fun rebuildRuntimeCache(context: Context): Boolean = withContext(Dispatchers.IO) {
        clearInitialRuntimePreparationMarker(context)
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
