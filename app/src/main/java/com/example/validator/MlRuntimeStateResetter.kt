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
        val lastUpdateTime = appContext.packageManager
            .getPackageInfo(appContext.packageName, 0)
            .lastUpdateTime

        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedLastUpdateTime = prefs.getLong(KEY_LAST_APK_UPDATE_TIME, -1L)
        if (savedLastUpdateTime == lastUpdateTime) return

        Log.i(TAG, "APK update detected; resetting ML runtime state")

        logDirectoryState("before filesDir", appContext.filesDir)
        logDirectoryState("before cacheDir", appContext.cacheDir)
        logDirectoryState("before codeCacheDir", appContext.codeCacheDir)
        logDirectoryState("before noBackupFilesDir", appContext.noBackupFilesDir)

        appContext.filesDir.listFiles().orEmpty().forEach { file ->
            if (file.name == GemmaModelManager.MODEL_FILENAME) return@forEach
            file.deleteRecursively()
        }

        clearDirectoryContents(appContext.cacheDir)
        clearDirectoryContents(appContext.codeCacheDir)
        clearDirectoryContents(appContext.noBackupFilesDir)

        logDirectoryState("after filesDir", appContext.filesDir)
        logDirectoryState("after cacheDir", appContext.cacheDir)
        logDirectoryState("after codeCacheDir", appContext.codeCacheDir)
        logDirectoryState("after noBackupFilesDir", appContext.noBackupFilesDir)

        prefs.edit().putLong(KEY_LAST_APK_UPDATE_TIME, lastUpdateTime).apply()
    }

    private fun clearDirectoryContents(dir: File) {
        dir.listFiles().orEmpty().forEach { file ->
            file.deleteRecursively()
        }
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
