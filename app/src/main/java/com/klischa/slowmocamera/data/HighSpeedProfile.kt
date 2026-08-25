package com.klischa.slowmocamera.data

import android.util.Range
import android.util.Size

/**
 * Профиль разрешения и частоты кадров для Constrained High Speed режима.
 */
data class HighSpeedProfile(
    val size: Size,
    val fpsRange: Range<Int>,
    val isConstrainedSupported: Boolean = true
) {
    val fps: Int
        get() = fpsRange.upper

    val label: String
        get() {
            val resLabel = when {
                size.width == 1920 && size.height == 1080 -> "1080p"
                size.width == 1280 && size.height == 720 -> "720p"
                size.width == 3840 && size.height == 2160 -> "4K"
                else -> "${size.width}x${size.height}"
            }
            return "$resLabel @ ${fps}fps"
        }

    /**
     * Рекомендуемый битрейт в битах/сек в зависимости от разрешения и FPS.
     */
    val recommendedBitRate: Int
        get() {
            val pixelsPerSecond = size.width.toLong() * size.height.toLong() * fps.toLong()
            return when {
                pixelsPerSecond > 1920L * 1080L * 120L -> 50_000_000 // 50 Mbps для 1080p@240
                pixelsPerSecond > 1280L * 720L * 120L -> 35_000_000  // 35 Mbps для 1080p@120 / 720p@240
                else -> 20_000_000                                   // 20 Mbps для 720p@120
            }
        }
}
