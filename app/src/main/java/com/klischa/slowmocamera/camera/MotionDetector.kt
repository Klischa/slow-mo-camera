package com.klischa.slowmocamera.camera

import android.media.Image
import android.os.SystemClock
import java.nio.ByteBuffer

/**
 * Программный детектор движения по разнице яркости (Luminance) кадров.
 * Используется для автоматического старта Slow-Mo записи при появлении движения в кадре.
 */
class MotionDetector(
    var isEnabled: Boolean = false,
    var sensitivityThresholdPercent: Float = 12.0f, // Порог срабатывания в %
    var cooldownMs: Long = 3000L,
    private val onMotionDetected: () -> Unit
) {
    private var previousBuffer: ByteArray? = null
    private var lastTriggerTime: Long = 0L

    fun processImage(image: Image) {
        if (!isEnabled) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerTime < cooldownMs) return

        val plane = image.planes[0] // Y plane (яркость)
        val buffer = plane.buffer
        val width = image.width
        val height = image.height

        // Выполняем субдискретизацию (берем каждый 8-й пиксель для мгновенного анализа без просадки FPS)
        val step = 8
        val sampleWidth = width / step
        val sampleHeight = height / step
        val currentSamples = ByteArray(sampleWidth * sampleHeight)

        var idx = 0
        for (y in 0 until height step step) {
            val rowOffset = y * plane.rowStride
            for (x in 0 until width step step) {
                val pos = rowOffset + x * plane.pixelStride
                if (pos < buffer.limit()) {
                    currentSamples[idx++] = buffer.get(pos)
                }
            }
        }

        val prev = previousBuffer
        previousBuffer = currentSamples

        if (prev != null && prev.size == currentSamples.size) {
            var diffSum = 0L
            val totalPixels = currentSamples.size

            for (i in 0 until totalPixels) {
                val diff = Math.abs((currentSamples[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF))
                if (diff > 25) { // Порог изменения яркости отдельного пикселя
                    diffSum++
                }
            }

            val motionRatio = (diffSum.toFloat() / totalPixels.toFloat()) * 100f
            if (motionRatio >= sensitivityThresholdPercent) {
                lastTriggerTime = now
                onMotionDetected()
            }
        }
    }

    fun reset() {
        previousBuffer = null
    }
}
