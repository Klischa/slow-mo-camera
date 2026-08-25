package com.klischa.slowmocamera.data

import android.media.MediaRecorder

/**
 * Поддерживаемые выходные форматы и кодеки.
 */
enum class OutputFormatType(
    val extension: String,
    val mimeType: String,
    val outputFormat: Int,
    val videoEncoder: Int,
    val displayName: String
) {
    MP4_H264(
        extension = "mp4",
        mimeType = "video/mp4",
        outputFormat = MediaRecorder.OutputFormat.MPEG_4,
        videoEncoder = MediaRecorder.VideoEncoder.H264,
        displayName = "MP4 (H.264 / AVC)"
    ),

    WEBM_VP9(
        extension = "webm",
        mimeType = "video/webm",
        outputFormat = MediaRecorder.OutputFormat.WEBM,
        videoEncoder = MediaRecorder.VideoEncoder.VP9,
        displayName = "WebM (VP9)"
    );

    companion object {
        fun defaultFormat(): OutputFormatType = MP4_H264
    }
}
