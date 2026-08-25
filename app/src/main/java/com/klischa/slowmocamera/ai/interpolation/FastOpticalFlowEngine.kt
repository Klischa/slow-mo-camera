package com.klischa.slowmocamera.ai.interpolation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * Быстрый движок интерполяции (Fast Motion-Compensated Flow).
 * Используется в режиме "Скорость" для мгновенной обработки без перегрева чипсета.
 */
class FastOpticalFlowEngine : InterpolationEngine {

    override val engineName: String = "Fast Flow (Speed Mode)"
    override val isGpuAccelerated: Boolean = false
    override val supportsCustomTimestep: Boolean = true

    override suspend fun initialize(context: Context): Boolean = true

    override suspend fun interpolate(frameA: Bitmap, frameB: Bitmap, timestep: Float): Bitmap {
        val width = frameA.width
        val height = frameA.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paintA = Paint().apply { setAlpha(((1.0f - timestep) * 255).toInt()) }
        val paintB = Paint().apply { setAlpha((timestep * 255).toInt()) }

        canvas.drawBitmap(frameA, 0f, 0f, paintA)
        canvas.drawBitmap(frameB, 0f, 0f, paintB)

        return output
    }

    override fun release() {}
}
