package com.example.validator

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Properties

object GemmaModelManager {
    private const val TAG = "GemmaModelManager"
    const val MODEL_FILENAME = "gemma-4-E4B-it.litertlm"
    private const val DOWNLOAD_URL = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"
    private const val EXPECTED_MODEL_SHA256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"
    private const val EXPECTED_MODEL_SIZE_BYTES = 3659530240L
    private const val MODEL_ROOT_DIR_NAME = "gemma_models"
    private const val CURRENT_MODEL_PROPERTIES = "current_model.properties"
    private const val MODEL_PREPARED_PREFS = "model_prepared_state"
    private const val KEY_PREPARED_APK_LAST_UPDATE_TIME = "prepared_apk_last_update_time"

    sealed class ModelPrepareResult {
        data object Ready : ModelPrepareResult()
        data object Missing : ModelPrepareResult()
        data object ChecksumMismatch : ModelPrepareResult()
        data class Error(val message: String) : ModelPrepareResult()
    }

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress

    private val _downloadBytesInfo = MutableStateFlow("0 / 0 MB")
    val downloadBytesInfo: StateFlow<String> = _downloadBytesInfo

    fun getModelFile(context: Context): File {
        val appContext = context.applicationContext
        val propertiesFile = File(File(appContext.filesDir, MODEL_ROOT_DIR_NAME), CURRENT_MODEL_PROPERTIES)
        if (propertiesFile.exists()) {
            runCatching {
                FileInputStream(propertiesFile).use { input ->
                    val properties = Properties().apply { load(input) }
                    properties.getProperty("modelPath")
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::File)
                        ?.takeIf { it.exists() }
                        ?.let { return it }
                }
            }.onFailure { Log.w(TAG, "Failed to read current model properties", it) }
        }

        val legacyFile = File(appContext.filesDir, MODEL_FILENAME)
        if (legacyFile.exists()) return legacyFile
        return legacyFile
    }

    fun isModelDownloaded(context: Context): Boolean {
        val file = getModelFile(context)
        return file.exists() && file.length() == EXPECTED_MODEL_SIZE_BYTES
    }

    suspend fun calculateSha256(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytes = input.read(buffer)
            while (bytes >= 0) {
                if (bytes > 0) digest.update(buffer, 0, bytes)
                bytes = input.read(buffer)
            }
        }
        digest.digest().toHexString()
    }

    suspend fun validateModelFile(file: File): Boolean {
        if (!file.exists()) return false
        if (file.length() != EXPECTED_MODEL_SIZE_BYTES) return false
        val actualSha = calculateSha256(file)
        return actualSha.equals(EXPECTED_MODEL_SHA256, ignoreCase = true)
    }

    suspend fun prepareModelForCurrentApk(context: Context): ModelPrepareResult = withContext(Dispatchers.IO) {
        try {
            val appContext = context.applicationContext
            val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val apkLastUpdateTime = packageInfo.lastUpdateTime
            val modelRootDir = File(appContext.filesDir, MODEL_ROOT_DIR_NAME).apply { mkdirs() }
            val propertiesFile = File(modelRootDir, CURRENT_MODEL_PROPERTIES)
            val currentModel = getModelFile(appContext)

            if (!currentModel.exists()) return@withContext ModelPrepareResult.Missing

            if (!validateModelFile(currentModel)) {
                currentModel.delete()
                propertiesFile.delete()
                cleanupOldModelCopies(appContext, keepDir = File(modelRootDir, "__none__"))
                return@withContext ModelPrepareResult.ChecksumMismatch
            }

            val savedApkTime = appContext.getSharedPreferences(MODEL_PREPARED_PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_PREPARED_APK_LAST_UPDATE_TIME, -1L)

            val insideModelRoot = currentModel.parentFile?.parentFile?.canonicalFile == modelRootDir.canonicalFile
            val pointsToCurrent = if (propertiesFile.exists()) {
                runCatching {
                    FileInputStream(propertiesFile).use { input ->
                        val p = Properties().apply { load(input) }
                        p.getProperty("modelPath") == currentModel.absolutePath
                    }
                }.getOrDefault(false)
            } else false

            if (savedApkTime == apkLastUpdateTime && insideModelRoot && pointsToCurrent) {
                return@withContext ModelPrepareResult.Ready
            }

            val freshDir = File(modelRootDir, "model_${apkLastUpdateTime}_${System.currentTimeMillis()}").apply { mkdirs() }
            val tempFile = File(freshDir, "$MODEL_FILENAME.tmp")
            val finalFile = File(freshDir, MODEL_FILENAME)

            currentModel.inputStream().use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
            if (tempFile.length() != EXPECTED_MODEL_SIZE_BYTES) {
                freshDir.deleteRecursively()
                return@withContext ModelPrepareResult.ChecksumMismatch
            }
            val tempSha = calculateSha256(tempFile)
            if (!tempSha.equals(EXPECTED_MODEL_SHA256, ignoreCase = true)) {
                freshDir.deleteRecursively()
                return@withContext ModelPrepareResult.ChecksumMismatch
            }
            if (!tempFile.renameTo(finalFile)) {
                freshDir.deleteRecursively()
                return@withContext ModelPrepareResult.Error("Failed to finalize model file")
            }

            val props = Properties().apply {
                setProperty("modelPath", finalFile.absolutePath)
                setProperty("sha256", EXPECTED_MODEL_SHA256)
                setProperty("size", finalFile.length().toString())
                setProperty("apkLastUpdateTime", apkLastUpdateTime.toString())
            }
            FileOutputStream(propertiesFile).use { props.store(it, null) }

            val prefsSaved = appContext.getSharedPreferences(MODEL_PREPARED_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_PREPARED_APK_LAST_UPDATE_TIME, apkLastUpdateTime)
                .commit()
            if (!prefsSaved) {
                return@withContext ModelPrepareResult.Error("Failed to persist APK preparation state")
            }

            cleanupOldModelCopies(appContext, keepDir = freshDir)
            ModelPrepareResult.Ready
        } catch (e: Exception) {
            Log.e(TAG, "prepareModelForCurrentApk failed", e)
            ModelPrepareResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun deleteModel(context: Context): Boolean = withContext(Dispatchers.IO) {
        val file = getModelFile(context)
        if (file.exists()) file.delete() else false
    }

    suspend fun downloadModel(context: Context, onProgress: (progress: Float, downloadedMB: Float, totalMB: Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val apkLastUpdateTime = packageInfo.lastUpdateTime
        val modelRoot = File(appContext.filesDir, MODEL_ROOT_DIR_NAME).apply { mkdirs() }
        val freshDir = File(modelRoot, "model_${apkLastUpdateTime}_${System.currentTimeMillis()}").apply { mkdirs() }
        val tempFile = File(freshDir, "$MODEL_FILENAME.tmp")
        val finalFile = File(freshDir, MODEL_FILENAME)
        val propertiesFile = File(modelRoot, CURRENT_MODEL_PROPERTIES)

        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            Log.i(TAG, "Starting download from: $DOWNLOAD_URL")
            connection = URL(DOWNLOAD_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            connection.connect()

            if (connection.responseCode !in 200..299) return@withContext false
            val fileLength = connection.contentLengthLong
            inputStream = connection.inputStream
            outputStream = FileOutputStream(tempFile)

            val data = ByteArray(1024 * 64)
            var total = 0L
            var count: Int
            var lastUpdate = 0L
            while (inputStream.read(data).also { count = it } != -1) {
                total += count
                outputStream.write(data, 0, count)
                val now = System.currentTimeMillis()
                if (now - lastUpdate > 100 || total == fileLength) {
                    lastUpdate = now
                    val progress = if (fileLength > 0) total.toFloat() / fileLength else 0f
                    val downloadedMB = total.toFloat() / (1024 * 1024)
                    val totalMB = fileLength.toFloat() / (1024 * 1024)
                    _downloadProgress.value = progress
                    _downloadBytesInfo.value = String.format("%.1f MB / %.1f MB (%.0f%%)", downloadedMB, totalMB, progress * 100)
                    withContext(Dispatchers.Main) { onProgress(progress, downloadedMB, totalMB) }
                }
            }
            outputStream.flush()

            if (tempFile.length() != EXPECTED_MODEL_SIZE_BYTES) {
                freshDir.deleteRecursively()
                return@withContext false
            }
            val downloadedSha = calculateSha256(tempFile)
            if (!downloadedSha.equals(EXPECTED_MODEL_SHA256, ignoreCase = true)) {
                freshDir.deleteRecursively()
                return@withContext false
            }
            if (!tempFile.renameTo(finalFile)) {
                freshDir.deleteRecursively()
                return@withContext false
            }

            val props = Properties().apply {
                setProperty("modelPath", finalFile.absolutePath)
                setProperty("sha256", EXPECTED_MODEL_SHA256)
                setProperty("size", finalFile.length().toString())
                setProperty("apkLastUpdateTime", apkLastUpdateTime.toString())
            }
            FileOutputStream(propertiesFile).use { props.store(it, null) }
            val prefSaved = appContext.getSharedPreferences(MODEL_PREPARED_PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_PREPARED_APK_LAST_UPDATE_TIME, apkLastUpdateTime).commit()
            if (!prefSaved) return@withContext false

            cleanupOldModelCopies(appContext, keepDir = freshDir)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading model", e)
            freshDir.deleteRecursively()
            false
        } finally {
            runCatching { outputStream?.close() }
            runCatching { inputStream?.close() }
            runCatching { connection?.disconnect() }
        }
    }

    private fun cleanupOldModelCopies(context: Context, keepDir: File) {
        val root = File(context.filesDir, MODEL_ROOT_DIR_NAME)
        root.listFiles()?.forEach { child ->
            if (child.name == CURRENT_MODEL_PROPERTIES) return@forEach
            if (child.isDirectory && child.canonicalFile != keepDir.canonicalFile) {
                child.deleteRecursively()
            }
            if (child.isFile && child.name.endsWith(".tmp")) {
                child.delete()
            }
        }
        keepDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".tmp")) file.delete()
        }

        val activeFile = File(keepDir, MODEL_FILENAME)
        val legacyFile = File(context.filesDir, MODEL_FILENAME)
        if (legacyFile.exists() && legacyFile.canonicalFile != activeFile.canonicalFile) {
            legacyFile.delete()
        }
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { "%02x".format(it) }
}
