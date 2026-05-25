package com.example.validator

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object GemmaModelManager {
    private const val TAG = "GemmaModelManager"
    const val MODEL_FILENAME = "gemma-4-E4B-it.litertlm"
    private const val DOWNLOAD_URL = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"
    private const val MODEL_PREFS_NAME = "gemma_model_state"
    private const val MODEL_FILENAME_KEY = "model_filename"
    private const val MODEL_SIZE_KEY = "model_size"
    private const val MODEL_LAST_MODIFIED_KEY = "model_last_modified"
    private const val DOWNLOAD_COMPLETED_KEY = "download_completed"
    private const val MODEL_STORE_DIR = "gemma_model_store"

    private const val MIN_MODEL_SIZE_BYTES = 3L * 1024 * 1024 * 1024

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress

    private val _downloadBytesInfo = MutableStateFlow("0 / 0 MB")
    val downloadBytesInfo: StateFlow<String> = _downloadBytesInfo

    fun getModelFile(context: Context): File {
        val dir = File(context.filesDir, MODEL_STORE_DIR).apply { mkdirs() }
        return File(dir, MODEL_FILENAME)
    }

    fun migrateLegacyModelLocationIfNeeded(context: Context) {
        val oldFile = File(context.filesDir, MODEL_FILENAME)
        val newFile = getModelFile(context)
        if (newFile.exists()) return
        if (!oldFile.exists() || oldFile.length() <= MIN_MODEL_SIZE_BYTES) return

        newFile.parentFile?.mkdirs()
        val moved = oldFile.renameTo(newFile)
        if (!moved) {
            oldFile.inputStream().use { input ->
                newFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (newFile.length() != oldFile.length()) {
                newFile.delete()
                return
            }
            oldFile.delete()
        }
        writeModelMetadata(context, newFile)
    }

    private fun writeModelMetadata(context: Context, file: File) {
        context.getSharedPreferences(MODEL_PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(MODEL_FILENAME_KEY, file.name)
            .putLong(MODEL_SIZE_KEY, file.length())
            .putLong(MODEL_LAST_MODIFIED_KEY, file.lastModified())
            .putBoolean(DOWNLOAD_COMPLETED_KEY, true)
            .apply()
    }

    private fun clearModelMetadata(context: Context) {
        context.getSharedPreferences(MODEL_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }


    fun refreshModelMetadata(context: Context) {
        migrateLegacyModelLocationIfNeeded(context)
        val file = getModelFile(context)
        if (file.exists()) {
            writeModelMetadata(context, file)
        }
    }

    fun isModelDownloaded(context: Context): Boolean {
        migrateLegacyModelLocationIfNeeded(context)
        val file = getModelFile(context)
        if (!file.exists() || file.length() <= MIN_MODEL_SIZE_BYTES) return false

        val prefs = context.getSharedPreferences(MODEL_PREFS_NAME, Context.MODE_PRIVATE)
        val hasMeta = prefs.contains(MODEL_SIZE_KEY) && prefs.contains(MODEL_LAST_MODIFIED_KEY)
        if (!hasMeta) {
            Log.w(TAG, "Model file exists but metadata missing, applying one-time migration")
            writeModelMetadata(context, file)
            return true
        }

        val downloaded = prefs.getBoolean(DOWNLOAD_COMPLETED_KEY, false)
        val metaFileName = prefs.getString(MODEL_FILENAME_KEY, null)
        val size = prefs.getLong(MODEL_SIZE_KEY, -1L)
        val modified = prefs.getLong(MODEL_LAST_MODIFIED_KEY, -1L)
        return downloaded && metaFileName == file.name && size == file.length() && modified == file.lastModified()
    }

    suspend fun deleteModel(context: Context): Boolean = withContext(Dispatchers.IO) {
        migrateLegacyModelLocationIfNeeded(context)
        val file = getModelFile(context)
        val tmpFile = File(file.parentFile ?: context.filesDir, "$MODEL_FILENAME.tmp")
        if (tmpFile.exists()) tmpFile.delete()
        clearModelMetadata(context)
        if (file.exists()) file.delete() else false
    }

    suspend fun downloadModel(context: Context, onProgress: (progress: Float, downloadedMB: Float, totalMB: Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        migrateLegacyModelLocationIfNeeded(context)
        val targetFile = getModelFile(context)
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile ?: context.filesDir, "$MODEL_FILENAME.tmp")
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        try {
            if (tempFile.exists()) tempFile.delete()
            if (targetFile.exists()) targetFile.delete()
            clearModelMetadata(context)

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
            outputStream.close(); inputStream.close()
            if (tempFile.renameTo(targetFile)) {
                writeModelMetadata(context, targetFile)
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading model", e)
            if (tempFile.exists()) tempFile.delete()
            false
        } finally {
            try { outputStream?.close(); inputStream?.close(); connection?.disconnect() } catch (_: Exception) {}
        }
    }
}
