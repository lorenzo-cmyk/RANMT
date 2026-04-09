package dev.ranmt.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

fun formatDateTime(epochMs: Long): String = dateFormat.format(Date(epochMs))

fun formatDuration(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "${mins}m ${secs}s"
}

fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) String.format(Locale.US, "%.1f MB", mb) else String.format(
        Locale.US,
        "%.0f KB",
        kb
    )
}

fun formatPct(value: Double): String = String.format(Locale.US, "%.1f%%", value)

fun formatJitter(value: Double): String = String.format(Locale.US, "%.1f ms", value)
