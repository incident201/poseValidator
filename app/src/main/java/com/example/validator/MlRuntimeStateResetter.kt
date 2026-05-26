package com.example.validator

import android.content.Context
import android.util.Log
import java.io.File

object MlRuntimeStateResetter {
    private const val TAG = "MlRuntimeStateResetter"
    private const val PREFS_NAME = "ml_runtime_state"
    private const val KEY_LAST_APK_UPDATE_TIME = "last_apk_update_time"

    fun resetIfApkUpdated(context: Context) {
        val appContext = context.applicationContext
        if (isCurrentApkUpdateHandled(appContext)) return

        Log.i(TAG, "APK update detected; resetting ML runtime state")

        logDirectoryState("before filesDir", appContext.filesDir)
        logDirectoryState("before cacheDir", appContext.cacheDir)
        logDirectoryState("before codeCacheDir", appContext.codeCacheDir)
        logDirectoryState("before noBackupFilesDir", appContext.noBackupFilesDir)

        var isResetSuccessful = true

        appContext.filesDir.listFiles().orEmpty().forEach { file ->
            if (file.name == GemmaModelManager.MODEL_FILENAME) return@forEach
            if (!deleteRecursivelyLogged(file)) {
                isResetSuccessful = false
            }
        }

        if (!clearDirectoryContents(appContext.cacheDir)) {
            isResetSuccessful = false
        }
        if (!clearDirectoryContents(appContext.codeCacheDir)) {
            isResetSuccessful = false
        }
        if (!clearDirectoryContents(appContext.noBackupFilesDir)) {
            isResetSuccessful = false
        }

        logDirectoryState("after filesDir", appContext.filesDir)
        logDirectoryState("after cacheDir", appContext.cacheDir)
        logDirectoryState("after codeCacheDir", appContext.codeCacheDir)
        logDirectoryState("after noBackupFilesDir", appContext.noBackupFilesDir)

        if (!isResetSuccessful) {
            throw IllegalStateException("Failed to fully reset ML runtime state after APK update")
        }
    }

    fun markCurrentApkUpdateHandled(context: Context) {
        val appContext = context.applicationContext
        val lastUpdateTime = appContext.packageManager
            .getPackageInfo(appContext.packageName, 0)
            .lastUpdateTime
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val committed = prefs.edit()
            .putLong(KEY_LAST_APK_UPDATE_TIME, lastUpdateTime)
            .commit()
        if (!committed) {
            Log.w(TAG, "Failed to persist handled APK update timestamp")
        }
    }

    fun isCurrentApkUpdateHandled(context: Context): Boolean {
        val appContext = context.applicationContext
        val currentLastUpdateTime = appContext.packageManager
            .getPackageInfo(appContext.packageName, 0)
            .lastUpdateTime
        val savedLastUpdateTime = appContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_APK_UPDATE_TIME, -1L)
        return savedLastUpdateTime == currentLastUpdateTime
    }

    private fun clearDirectoryContents(dir: File): Boolean {
        var success = true
        dir.listFiles().orEmpty().forEach { file ->
            if (!deleteRecursivelyLogged(file)) {
                success = false
            }
        }
        return success
    }

    private fun deleteRecursivelyLogged(file: File): Boolean {
        if (!file.exists()) return true
        file.deleteRecursively()
        if (file.exists()) {
            Log.e(TAG, "Failed to delete path=${file.absolutePath}")
            return false
        }
        return true
    }

    private fun logDirectoryState(label: String, dir: File) {
        Log.i(TAG, "$label path=${dir.absolutePath} exists=${dir.exists()} isDirectory=${dir.isDirectory}")
        dir.listFiles().orEmpty().forEach { file ->
            Log.i(
                TAG,
                "$label entry name=${file.name} isDirectory=${file.isDirectory} length=${file.length()} lastModified=${file.lastModified()}"
            )
        }
    }
}
