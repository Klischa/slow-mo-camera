package com.klischa.slowmocamera.gallery

import android.net.Uri

data class VideoMediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val isVideo: Boolean = true
) {
    val durationFormatted: String
        get() {
            val totalSec = durationMs / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            return String.format("%02d:%02d", min, sec)
        }

    val sizeFormatted: String
        get() {
            val mb = sizeBytes / (1024f * 1024f)
            return String.format(java.util.Locale.US, "%.1f MB", mb)
        }
}
