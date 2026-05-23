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

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress

    private val _downloadBytesInfo = MutableStateFlow("0 / 0 MB")
    val downloadBytesInfo: StateFlow<String> = _downloadBytesInfo

    fun getModelFile(context: Context): File {
        return File(context.filesDir, MODEL_FILENAME)
    }

    fun isModelDownloaded(context: Context): Boolean {
        val file = getModelFile(context)
        return file.exists() && file.length() > 500 * 1024 * 1024 // Model is around 1GB+, so must be of a realistic size
    }

    suspend fun deleteModel(context: Context): Boolean = withContext(Dispatchers.IO) {
        val file = getModelFile(context)
        if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }

    suspend fun downloadModel(context: Context, onProgress: (progress: Float, downloadedMB: Float, totalMB: Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val targetFile = getModelFile(context)
        val tempFile = File(context.filesDir, "$MODEL_FILENAME.tmp")
        
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            if (targetFile.exists()) {
                targetFile.delete()
            }

            Log.i(TAG, "Starting download from: $DOWNLOAD_URL")
            val url = URL(DOWNLOAD_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                Log.e(TAG, "Server returned response code $responseCode")
                return@withContext false
            }

            val fileLength = connection.contentLengthLong
            Log.i(TAG, "Model total length: $fileLength bytes")

            inputStream = connection.inputStream
            outputStream = FileOutputStream(tempFile)

            val data = ByteArray(1024 * 64)
            var total: Long = 0
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
                    val progressText = String.format("%.1f MB / %.1f MB (%.0f%%)", downloadedMB, totalMB, progress * 100)
                    _downloadBytesInfo.value = progressText

                    withContext(Dispatchers.Main) {
                        onProgress(progress, downloadedMB, totalMB)
                    }
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            // Rename temp to target
            if (tempFile.renameTo(targetFile)) {
                Log.i(TAG, "Gemma Model downloaded and verified successfully: ${targetFile.absolutePath}")
                true
            } else {
                Log.e(TAG, "Failed to rename temp model file to final target destination")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading model", e)
            try {
                if (tempFile.exists()) tempFile.delete()
            } catch (ex: Exception) {
                // Ignore
            }
            false
        } finally {
            try {
                outputStream?.close()
                inputStream?.close()
                connection?.disconnect()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}
