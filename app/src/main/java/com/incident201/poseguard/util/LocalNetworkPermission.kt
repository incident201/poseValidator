package com.incident201.poseguard.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.incident201.poseguard.BuildConfig

private const val ANDROID_17_API = 37

fun shouldRequestLocalNetworkPermission(): Boolean {
    return BuildConfig.HAS_NETWORK_FEATURES && Build.VERSION.SDK_INT >= ANDROID_17_API
}

fun hasRequiredLocalNetworkPermission(context: Context): Boolean {
    return !shouldRequestLocalNetworkPermission() ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_LOCAL_NETWORK
        ) == PackageManager.PERMISSION_GRANTED
}
