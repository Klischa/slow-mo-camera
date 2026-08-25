package com.klischa.slowmocamera.data

/**
 * Полная конфигурация для текущей видеозаписи.
 */
data class VideoConfig(
    val mode: RecordingMode = RecordingMode.HFR,
    val profile: HighSpeedProfile,
    val format: OutputFormatType = OutputFormatType.MP4_H264,
    val includeAudio: Boolean = false // В High Speed / Slow Mo аудио часто аппаратно недоступно
)
