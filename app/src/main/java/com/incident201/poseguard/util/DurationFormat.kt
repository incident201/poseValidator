package com.incident201.poseguard.util

import java.util.Locale

fun formatDurationHms(totalSeconds: Long): String {
    val safeSeconds = totalSeconds.coerceAtLeast(0L)
    val hours = safeSeconds / 3600
    val minutes = (safeSeconds % 3600) / 60
    val seconds = safeSeconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

fun formatDurationHms(totalSeconds: Int): String {
    return formatDurationHms(totalSeconds.toLong())
}

fun formatDurationHmsFromMillis(elapsedMs: Long): String {
    return formatDurationHms(elapsedMs.coerceAtLeast(0L) / 1000L)
}
